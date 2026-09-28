from __future__ import annotations

import io
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid
import zipfile
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sidecar.config import Config
from sidecar.extract import UnsafeArchiveError, safe_extract_zip
from sidecar.harness import HarnessResult, StubHarness
from sidecar.multipart import parse_content_type, parse_multipart
from sidecar.server import Sidecar, make_handler


def _multipart_body(fields: dict[str, bytes], files: dict[str, tuple[str, bytes]]) -> tuple[bytes, str]:
    boundary = "----test-boundary-" + uuid.uuid4().hex
    buf = io.BytesIO()

    def write_part(name, filename, data):
        buf.write(f"--{boundary}\r\n".encode())
        if filename:
            buf.write(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
            buf.write(b"Content-Type: application/octet-stream\r\n")
        else:
            buf.write(f'Content-Disposition: form-data; name="{name}"\r\n'.encode())
        buf.write(b"\r\n")
        buf.write(data)
        buf.write(b"\r\n")

    for name, data in fields.items():
        write_part(name, None, data)
    for name, (filename, data) in files.items():
        write_part(name, filename, data)

    buf.write(f"--{boundary}--\r\n".encode())
    return buf.getvalue(), boundary


class MultipartTests(unittest.TestCase):
    def test_roundtrip_preserves_binary_edges(self):
        # Binary payload deliberately ends in bytes that look like CRLF, to
        # catch the classic "strip(b'\r\n')" bug that would corrupt it.
        binary_blob = b"PK\x03\x04some-fake-zip-bytes" + b"\r\n" * 3
        body, boundary = _multipart_body(
            fields={"metadata": json.dumps({"title": "x"}).encode()},
            files={"bundle": ("bundle.zip", binary_blob)},
        )
        parts = parse_multipart(body, boundary)
        self.assertEqual(parts["metadata"][0].data, json.dumps({"title": "x"}).encode())
        self.assertEqual(parts["bundle"][0].data, binary_blob)
        self.assertEqual(parts["bundle"][0].filename, "bundle.zip")

    def test_parse_content_type(self):
        main, params = parse_content_type('multipart/form-data; boundary="abc123"')
        self.assertEqual(main, "multipart/form-data")
        self.assertEqual(params["boundary"], "abc123")


class ExtractTests(unittest.TestCase):
    def _make_zip(self, entries: dict[str, bytes]) -> bytes:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            for name, data in entries.items():
                zf.writestr(name, data)
        return buf.getvalue()

    def test_rejects_path_traversal(self):
        blob = self._make_zip({"../../etc/evil": b"pwned"})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(UnsafeArchiveError):
                safe_extract_zip(
                    blob, Path(tmp) / "dest",
                    max_files=100, max_single_file_bytes=10_000, max_total_uncompressed_bytes=100_000,
                )

    def test_rejects_absolute_path(self):
        blob = self._make_zip({"/etc/evil": b"pwned"})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(UnsafeArchiveError):
                safe_extract_zip(
                    blob, Path(tmp) / "dest",
                    max_files=100, max_single_file_bytes=10_000, max_total_uncompressed_bytes=100_000,
                )

    def test_rejects_oversized_archive(self):
        blob = self._make_zip({"log.txt": b"x" * 5000})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(UnsafeArchiveError):
                safe_extract_zip(
                    blob, Path(tmp) / "dest",
                    max_files=100, max_single_file_bytes=10_000, max_total_uncompressed_bytes=1000,
                )

    def test_extracts_well_formed_archive(self):
        blob = self._make_zip({"a/b.log": b"hello", "c.json": b"{}"})
        with tempfile.TemporaryDirectory() as tmp:
            result = safe_extract_zip(
                blob, Path(tmp) / "dest",
                max_files=100, max_single_file_bytes=10_000, max_total_uncompressed_bytes=100_000,
            )
            names = {p.relative_to(result.root).as_posix() for p in result.files}
            self.assertEqual(names, {"a/b.log", "c.json"})
            self.assertEqual((result.root / "a/b.log").read_bytes(), b"hello")


def _init_temp_repo(tmp: Path) -> Path:
    repo = tmp / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
    (repo / "app.py").write_text("def handler():\n    raise ValueError('boom')\n")
    subprocess.run(["git", "add", "app.py"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "initial"], cwd=repo, check=True)
    return repo


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self._tmp_ctx = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp_ctx.name)
        self.repo = _init_temp_repo(self.tmp)
        self.data_dir = self.tmp / "data"

        self.config = Config(repo_path=self.repo, data_dir=self.data_dir)
        # Force the deterministic stub harness so tests never shell out to a
        # real agent binary, even if one happens to be on PATH.
        self.sidecar = Sidecar(self.config, harness=StubHarness())
        handler_cls = make_handler(self.sidecar)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler_cls)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.sidecar.shutdown()
        self._tmp_ctx.cleanup()

    def _post_incident(self, metadata: dict, bundle: bytes) -> dict:
        body, boundary = _multipart_body(
            fields={"metadata": json.dumps(metadata).encode()},
            files={"bundle": ("bundle.zip", bundle)},
        )
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/v1/incidents",
            data=body,
            method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        with urllib.request.urlopen(req) as resp:
            self.assertEqual(resp.status, 202)
            return json.loads(resp.read())

    def _get_incident(self, incident_id: str) -> dict:
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/v1/incidents/{incident_id}") as resp:
            return json.loads(resp.read())

    def _make_zip(self, entries: dict[str, bytes]) -> bytes:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            for name, data in entries.items():
                zf.writestr(name, data)
        return buf.getvalue()

    def test_health(self):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/healthz") as resp:
            self.assertEqual(resp.status, 200)

    def test_full_pipeline_reaches_done(self):
        bundle = self._make_zip({"crash.log": b"ValueError: boom\n  at app.py:2"})
        created = self._post_incident(
            {"title": "crash in handler", "stack_trace": "ValueError: boom"}, bundle
        )
        incident_id = created["incident_id"]
        self.assertEqual(created["status"], "queued")

        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            status = self._get_incident(incident_id)
            if status["status"] in ("done", "failed"):
                break
            time.sleep(0.1)

        self.assertEqual(status["status"], "done", status)
        self.assertIn("STUB HARNESS", status["report"])
        self.assertIsNotNone(status["resolved_ref"])

        # The disposable worktree must not linger after the run.
        self.assertFalse((self.data_dir / "incidents" / incident_id / "repo-worktree").exists())
        # And the companion repo's own worktree list should be clean too.
        out = subprocess.run(
            ["git", "-C", str(self.repo), "worktree", "list", "--porcelain"],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertEqual(out.count("worktree "), 1)  # just the main repo

    def test_malicious_bundle_marks_incident_failed(self):
        bundle = self._make_zip({"../../evil": b"pwned"})
        created = self._post_incident({"title": "bad zip"}, bundle)
        incident_id = created["incident_id"]

        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            status = self._get_incident(incident_id)
            if status["status"] in ("done", "failed"):
                break
            time.sleep(0.1)

        self.assertEqual(status["status"], "failed", status)
        self.assertIn("unsafe path", status["error"])

    def test_missing_fields_rejected(self):
        body, boundary = _multipart_body(fields={"metadata": b"{}"}, files={})
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/v1/incidents",
            data=body,
            method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req)
        self.assertEqual(ctx.exception.code, 400)

    def test_auth_required_when_configured(self):
        self.sidecar.config.auth_token = "secret"
        try:
            bundle = self._make_zip({"a.log": b"hi"})
            body, boundary = _multipart_body(
                fields={"metadata": b"{}"}, files={"bundle": ("b.zip", bundle)}
            )
            req = urllib.request.Request(
                f"http://127.0.0.1:{self.port}/v1/incidents",
                data=body,
                method="POST",
                headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            )
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req)
            self.assertEqual(ctx.exception.code, 401)
        finally:
            self.sidecar.config.auth_token = None


if __name__ == "__main__":
    unittest.main()
