from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Config:
    """Runtime configuration for the sidecar.

    All limits exist because this process accepts unauthenticated (unless
    ``auth_token`` is set) file uploads and then shells out to ``git`` and to
    an agentic harness with access to real source code. Every knob below is a
    guard against a hostile or malformed upload, not a convenience.
    """

    repo_path: Path
    data_dir: Path
    host: str = "127.0.0.1"
    port: int = 8787
    auth_token: str | None = field(default_factory=lambda: os.environ.get("SIDECAR_AUTH_TOKEN"))

    max_request_bytes: int = 200 * 1024 * 1024  # 200 MiB request body cap
    max_zip_files: int = 5000
    max_single_file_bytes: int = 100 * 1024 * 1024
    max_total_uncompressed_bytes: int = 500 * 1024 * 1024

    # How much of any individual extracted text file gets inlined into the
    # prompt sent to the harness. Everything beyond this is still on disk and
    # the harness can be told to read it, but we don't blow the prompt budget.
    max_inline_bytes_per_file: int = 64 * 1024
    max_inline_files: int = 40

    harness_timeout_seconds: int = 15 * 60
    worker_threads: int = 2
    incident_ttl_seconds: int = 7 * 24 * 3600

    # Command template for the agentic harness. The prompt is always sent on
    # stdin, never interpolated into argv, so this list is safe to leave as
    # a fixed default. Override via --harness-cmd to point at a different
    # agent binary/wrapper.
    harness_cmd: list[str] = field(
        default_factory=lambda: [
            "claude",
            "-p",
            "--output-format",
            "text",
            "--permission-mode",
            "plan",
        ]
    )

    def __post_init__(self) -> None:
        self.repo_path = Path(self.repo_path).resolve()
        self.data_dir = Path(self.data_dir).resolve()
