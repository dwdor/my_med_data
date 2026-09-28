"""The agentic harness: turns an extracted incident bundle + a read-only
source checkout into a root-cause analysis prompt, and runs some agent
against it.

The harness is intentionally pluggable behind a tiny interface so the
default (shelling out to the Claude Code CLI in headless/plan mode) can be
swapped for a different agent runner without touching the server or the
extraction/isolation code.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

TEXT_SUFFIXES = {
    ".txt", ".log", ".json", ".xml", ".yaml", ".yml", ".md", ".csv",
    ".properties", ".ini", ".cfg", ".stacktrace", ".trace",
}


@dataclass
class HarnessResult:
    report: str
    returncode: int
    raw_stdout: str
    raw_stderr: str
    timed_out: bool = False


class AgenticHarness:
    def run(self, prompt: str, *, cwd: Path, timeout: int) -> HarnessResult:  # pragma: no cover
        raise NotImplementedError


class ClaudeCodeHarness(AgenticHarness):
    """Invokes the Claude Code CLI in non-interactive print mode.

    The prompt is always passed on stdin -- never interpolated into argv or
    a shell string -- so upload content cannot inject extra CLI flags or
    escape into a shell. ``--permission-mode plan`` keeps the agent in a
    read/investigate posture rather than letting it edit the checkout.
    """

    def __init__(self, cmd: list[str]):
        self.cmd = cmd

    def run(self, prompt: str, *, cwd: Path, timeout: int) -> HarnessResult:
        try:
            proc = subprocess.run(
                self.cmd,
                input=prompt,
                cwd=str(cwd),
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            return HarnessResult(
                report="",
                returncode=-1,
                raw_stdout=(exc.stdout or ""),
                raw_stderr=(exc.stderr or "") + "\nharness timed out",
                timed_out=True,
            )
        return HarnessResult(
            report=proc.stdout.strip(),
            returncode=proc.returncode,
            raw_stdout=proc.stdout,
            raw_stderr=proc.stderr,
        )


class StubHarness(AgenticHarness):
    """Deterministic fallback used in tests and when no real agent binary is
    configured/available. Never used unless explicitly selected -- the
    server logs a loud warning if it falls back to this automatically.
    """

    def run(self, prompt: str, *, cwd: Path, timeout: int) -> HarnessResult:
        report = (
            "# Root-cause analysis (STUB HARNESS)\n\n"
            "No real agentic harness was available; this is a placeholder "
            "report so the pipeline can be exercised end to end.\n\n"
            f"Prompt was {len(prompt)} characters. Analysis cwd: {cwd}\n"
        )
        return HarnessResult(report=report, returncode=0, raw_stdout=report, raw_stderr="")


def select_harness(cmd: list[str]) -> AgenticHarness:
    binary = cmd[0] if cmd else None
    if binary and shutil.which(binary):
        return ClaudeCodeHarness(cmd)
    return StubHarness()


def _iter_manifest_lines(extracted_files: list[Path], root: Path) -> list[str]:
    lines = []
    for path in sorted(extracted_files):
        rel = path.relative_to(root)
        try:
            size = path.stat().st_size
        except OSError:
            size = -1
        lines.append(f"- {rel} ({size} bytes)")
    return lines


def build_prompt(
    *,
    metadata: dict,
    extracted_root: Path,
    extracted_files: list[Path],
    repo_worktree: Path,
    resolved_ref: str,
    max_inline_bytes_per_file: int,
    max_inline_files: int,
) -> str:
    manifest_lines = _iter_manifest_lines(extracted_files, extracted_root)

    inlined_sections = []
    inlined_count = 0
    for path in sorted(extracted_files):
        if inlined_count >= max_inline_files:
            break
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            raw = path.read_bytes()
        except OSError:
            continue
        text = raw[:max_inline_bytes_per_file].decode("utf-8", errors="replace")
        truncated = len(raw) > max_inline_bytes_per_file
        rel = path.relative_to(extracted_root)
        suffix = "\n... [truncated]" if truncated else ""
        inlined_sections.append(f"### {rel}\n```\n{text}{suffix}\n```")
        inlined_count += 1

    stack_trace = metadata.get("stack_trace")
    stack_trace_block = f"\n## Reported stack trace\n```\n{stack_trace}\n```\n" if stack_trace else ""

    return f"""You are performing root-cause analysis for a production incident that was
automatically reported by a monitoring/crash-reporting system. You have
read access to the source repository, checked out at the commit that was
actually running when the incident occurred. Do not modify any files;
your job is investigation and reporting, not fixing.

## Incident metadata
```json
{json.dumps(metadata, indent=2, default=str)}
```
{stack_trace_block}
## Source checkout
Read-only checkout path: {repo_worktree}
Resolved commit: {resolved_ref}

## Extracted diagnostic bundle
Bundle root: {extracted_root}
Files ({len(extracted_files)} total):
{chr(10).join(manifest_lines) if manifest_lines else '(no files extracted)'}

## Inlined file contents (subset, may be truncated)
{chr(10).join(inlined_sections) if inlined_sections else '(no text files were inlined; read them from disk if needed)'}

## Task
Correlate the diagnostic evidence above against the source code in the
checkout to determine the most likely root cause(s) of this incident.
Produce a Markdown report with these sections:

1. **Summary** -- one paragraph, plain language.
2. **Root cause hypotheses** -- ranked by confidence, each with supporting
   evidence and specific `file:line` references into the checkout.
3. **Evidence trail** -- how the log/stack-trace evidence maps to the code
   path that produced it.
4. **Suggested fix** -- described in prose/diff-sketch only; do not edit
   files.
5. **Open questions** -- anything you could not confirm from the available
   evidence.
"""
