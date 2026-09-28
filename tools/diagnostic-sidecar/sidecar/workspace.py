"""Isolated, disposable checkouts of the sidecar's companion repo.

The agentic harness is given read access to source code so it can correlate
an incident against the code that produced it. It must never be pointed at
the developer's live working directory: the harness may run build tools,
read arbitrary files, or (depending on how it's invoked) write scratch
files, and a live tree may have uncommitted changes that don't match what
actually shipped. Instead we create a ``git worktree`` checked out at the
ref the incident says was running, hand that to the harness, and tear it
down afterwards.
"""
from __future__ import annotations

import subprocess
from pathlib import Path


class WorktreeError(RuntimeError):
    pass


def resolve_ref(repo_path: Path, ref: str) -> str | None:
    """Return the commit sha for ``ref`` if it exists in the repo, else None."""
    proc = subprocess.run(
        ["git", "-C", str(repo_path), "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def create_analysis_worktree(repo_path: Path, ref: str, workdir: Path) -> tuple[Path, str]:
    """Create a detached, read-intent worktree at ``ref`` under ``workdir``.

    Returns the worktree path and the resolved commit sha actually used.
    Falls back to HEAD if ``ref`` cannot be resolved in this repo.
    """
    resolved = resolve_ref(repo_path, ref) or resolve_ref(repo_path, "HEAD")
    if resolved is None:
        raise WorktreeError(f"could not resolve ref {ref!r} or HEAD in {repo_path}")

    workdir.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        ["git", "-C", str(repo_path), "worktree", "add", "--detach", str(workdir), resolved],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise WorktreeError(f"git worktree add failed: {proc.stderr.strip()}")
    return workdir, resolved


def remove_worktree(repo_path: Path, workdir: Path) -> None:
    subprocess.run(
        ["git", "-C", str(repo_path), "worktree", "remove", "--force", str(workdir)],
        capture_output=True,
        text=True,
    )
    # Belt and suspenders: `worktree remove` can refuse in edge cases
    # (e.g. the dir was already gone). Never leave a dangling admin entry.
    subprocess.run(
        ["git", "-C", str(repo_path), "worktree", "prune"],
        capture_output=True,
        text=True,
    )
