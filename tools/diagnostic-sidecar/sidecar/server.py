from __future__ import annotations

import json
import logging
import re
import shutil
import threading
import time
import traceback
import uuid
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import harness as harness_mod
from .config import Config
from .extract import UnsafeArchiveError, safe_extract_zip
from .jobs import JobStore, Status
from .multipart import MultipartError, parse_content_type, parse_multipart
from .workspace import WorktreeError, create_analysis_worktree, remove_worktree

log = logging.getLogger("sidecar")

INCIDENT_PATH_RE = re.compile(r"^/v1/incidents/([A-Za-z0-9_-]+)$")


class Sidecar:
    def __init__(self, config: Config, harness: harness_mod.AgenticHarness | None = None):
        self.config = config
        self.jobs = JobStore(config.data_dir)
        self.executor = ThreadPoolExecutor(max_workers=config.worker_threads)
        if harness is not None:
            self.harness = harness
        else:
            self.harness = harness_mod.select_harness(config.harness_cmd)
            if isinstance(self.harness, harness_mod.StubHarness):
                log.warning(
                    "harness binary %r not found on PATH; falling back to StubHarness "
                    "(reports will be placeholders)",
                    config.harness_cmd[0] if config.harness_cmd else None,
                )
        self._reaper_stop = threading.Event()
        self._reaper_thread = threading.Thread(target=self._reap_loop, daemon=True)
        self._reaper_thread.start()

    def _reap_loop(self) -> None:
        while not self._reaper_stop.wait(3600):
            try:
                self.jobs.reap_expired(self.config.incident_ttl_seconds)
            except Exception:
                log.exception("incident reaper failed")

    def shutdown(self) -> None:
        self._reaper_stop.set()
        self.executor.shutdown(wait=False)

    # ---- incident intake -------------------------------------------------

    def submit_incident(self, metadata: dict, bundle_bytes: bytes) -> str:
        incident_id = metadata.get("incident_id") or uuid.uuid4().hex
        metadata["incident_id"] = incident_id
        self.jobs.create(incident_id, metadata)

        incident_dir = self.jobs.incident_dir(incident_id)
        incident_dir.mkdir(parents=True, exist_ok=True)
        (incident_dir / "upload.zip").write_bytes(bundle_bytes)

        self.executor.submit(self._run_pipeline, incident_id)
        return incident_id

    def _run_pipeline(self, incident_id: str) -> None:
        incident = self.jobs.get(incident_id)
        assert incident is not None
        incident_dir = self.jobs.incident_dir(incident_id)
        worktree_dir = incident_dir / "repo-worktree"

        try:
            self.jobs.update(incident_id, status=Status.EXTRACTING)
            extract_dir = incident_dir / "extracted"
            result = safe_extract_zip(
                (incident_dir / "upload.zip").read_bytes(),
                extract_dir,
                max_files=self.config.max_zip_files,
                max_single_file_bytes=self.config.max_single_file_bytes,
                max_total_uncompressed_bytes=self.config.max_total_uncompressed_bytes,
            )

            ref = incident.metadata.get("git_ref") or "HEAD"
            worktree_path, resolved_ref = create_analysis_worktree(
                self.config.repo_path, ref, worktree_dir
            )

            try:
                self.jobs.update(incident_id, status=Status.ANALYZING, resolved_ref=resolved_ref)
                prompt = harness_mod.build_prompt(
                    metadata=incident.metadata,
                    extracted_root=result.root,
                    extracted_files=result.files,
                    repo_worktree=worktree_path,
                    resolved_ref=resolved_ref,
                    max_inline_bytes_per_file=self.config.max_inline_bytes_per_file,
                    max_inline_files=self.config.max_inline_files,
                )
                (incident_dir / "prompt.txt").write_text(prompt)

                outcome = self.harness.run(
                    prompt, cwd=worktree_path, timeout=self.config.harness_timeout_seconds
                )
            finally:
                remove_worktree(self.config.repo_path, worktree_path)

            if outcome.timed_out:
                self.jobs.update(
                    incident_id, status=Status.FAILED, error="harness timed out"
                )
                return
            if outcome.returncode != 0:
                self.jobs.update(
                    incident_id,
                    status=Status.FAILED,
                    error=f"harness exited {outcome.returncode}: {outcome.raw_stderr[-2000:]}",
                )
                return

            (incident_dir / "report.md").write_text(outcome.report)
            self.jobs.update(incident_id, status=Status.DONE, report=outcome.report)

        except (UnsafeArchiveError, WorktreeError) as exc:
            self.jobs.update(incident_id, status=Status.FAILED, error=str(exc))
        except Exception as exc:  # pragma: no cover - defensive
            log.exception("pipeline failed for incident %s", incident_id)
            self.jobs.update(
                incident_id, status=Status.FAILED, error=f"internal error: {exc}"
            )
        finally:
            if worktree_dir.exists():
                shutil.rmtree(worktree_dir, ignore_errors=True)


def make_handler(sidecar: Sidecar) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "diagnostic-sidecar/1.0"

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003
            log.info("%s - %s", self.address_string(), fmt % args)

        def _send_json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, default=str).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            token = sidecar.config.auth_token
            if not token:
                return True
            provided = self.headers.get("Authorization", "")
            return provided == f"Bearer {token}"

        def do_GET(self):  # noqa: N802
            if self.path == "/healthz":
                self._send_json(HTTPStatus.OK, {"status": "ok"})
                return

            match = INCIDENT_PATH_RE.match(self.path)
            if match:
                if not self._authorized():
                    self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                incident = sidecar.jobs.get(match.group(1))
                if incident is None:
                    self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown incident"})
                    return
                payload = {
                    "incident_id": incident.incident_id,
                    "status": incident.status,
                    "created_at": incident.created_at,
                    "updated_at": incident.updated_at,
                    "resolved_ref": incident.resolved_ref,
                    "error": incident.error,
                    "report": incident.report,
                }
                self._send_json(HTTPStatus.OK, payload)
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_POST(self):  # noqa: N802
            if self.path != "/v1/incidents":
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            if not self._authorized():
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return

            content_length_hdr = self.headers.get("Content-Length")
            if content_length_hdr is None:
                self._send_json(HTTPStatus.LENGTH_REQUIRED, {"error": "Content-Length required"})
                return
            try:
                content_length = int(content_length_hdr)
            except ValueError:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid Content-Length"})
                return
            if content_length > sidecar.config.max_request_bytes:
                self._send_json(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    {"error": f"request exceeds max size of {sidecar.config.max_request_bytes} bytes"},
                )
                return

            content_type_hdr = self.headers.get("Content-Type", "")
            main_type, params = parse_content_type(content_type_hdr)
            if main_type != "multipart/form-data" or "boundary" not in params:
                self._send_json(
                    HTTPStatus.BAD_REQUEST,
                    {"error": "expected multipart/form-data with a boundary"},
                )
                return

            body = self.rfile.read(content_length)
            try:
                parts = parse_multipart(body, params["boundary"])
            except MultipartError as exc:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"malformed body: {exc}"})
                return

            if "metadata" not in parts or "bundle" not in parts:
                self._send_json(
                    HTTPStatus.BAD_REQUEST,
                    {"error": "request must include 'metadata' and 'bundle' form fields"},
                )
                return

            try:
                metadata = json.loads(parts["metadata"][0].data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"metadata is not valid JSON: {exc}"})
                return
            if not isinstance(metadata, dict):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "metadata must be a JSON object"})
                return

            bundle_bytes = parts["bundle"][0].data
            if not bundle_bytes:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "bundle is empty"})
                return

            try:
                incident_id = sidecar.submit_incident(metadata, bundle_bytes)
            except Exception:
                log.exception("failed to submit incident")
                self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "failed to accept incident"})
                return

            self._send_json(
                HTTPStatus.ACCEPTED,
                {
                    "incident_id": incident_id,
                    "status": Status.QUEUED,
                    "report_url": f"/v1/incidents/{incident_id}",
                },
            )

    return Handler


def run_server(config: Config, harness: harness_mod.AgenticHarness | None = None) -> None:
    sidecar = Sidecar(config, harness=harness)
    handler_cls = make_handler(sidecar)
    httpd = ThreadingHTTPServer((config.host, config.port), handler_cls)
    log.info("diagnostic sidecar listening on %s:%s (repo=%s)", config.host, config.port, config.repo_path)
    try:
        httpd.serve_forever()
    finally:
        sidecar.shutdown()
        httpd.server_close()
