#!/usr/bin/env python3
"""Entrypoint for the diagnostic sidecar.

Usage:
    python3 main.py --repo /path/to/local/git/repo --data-dir ./sidecar-data

See README.md for the API contract and design notes.
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sidecar.config import Config  # noqa: E402
from sidecar.server import run_server  # noqa: E402


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="path to the local git repository to analyze against")
    parser.add_argument("--data-dir", default="./sidecar-data", help="where uploads/extractions/reports are stored")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--harness-cmd", default=None, help="override the agent command (space-separated)")
    parser.add_argument("--harness-timeout", type=int, default=15 * 60)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--max-request-mb", type=int, default=200)
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    config = Config(
        repo_path=Path(args.repo),
        data_dir=Path(args.data_dir),
        host=args.host,
        port=args.port,
        harness_timeout_seconds=args.harness_timeout,
        worker_threads=args.workers,
        max_request_bytes=args.max_request_mb * 1024 * 1024,
    )
    if args.harness_cmd:
        config.harness_cmd = args.harness_cmd.split()

    if not (config.repo_path / ".git").exists():
        raise SystemExit(f"{config.repo_path} does not look like a git repository (no .git)")

    run_server(config)


if __name__ == "__main__":
    main()
