"""Safe extraction of an uploaded zip blob into an isolated tmp directory.

Untrusted zip archives are a classic attack surface: path traversal
("zip-slip") entries that escape the destination directory, symlink entries
that point outside it, and decompression bombs that exhaust disk. This
module guards against all three before a single byte is written.
"""
from __future__ import annotations

import io
import stat
import zipfile
from dataclasses import dataclass, field
from pathlib import Path


class UnsafeArchiveError(ValueError):
    pass


@dataclass
class ExtractResult:
    root: Path
    files: list[Path] = field(default_factory=list)
    total_bytes: int = 0


def safe_extract_zip(
    zip_bytes: bytes,
    dest_dir: Path,
    *,
    max_files: int,
    max_single_file_bytes: int,
    max_total_uncompressed_bytes: int,
) -> ExtractResult:
    dest_dir = dest_dir.resolve()
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest_prefix = str(dest_dir) + "/"

    try:
        zf = zipfile.ZipFile(io.BytesIO(zip_bytes))
    except zipfile.BadZipFile as exc:
        raise UnsafeArchiveError(f"not a valid zip file: {exc}") from exc

    infos = zf.infolist()
    if len(infos) > max_files:
        raise UnsafeArchiveError(f"archive has too many entries ({len(infos)} > {max_files})")

    planned: list[tuple[zipfile.ZipInfo, Path]] = []
    total = 0
    for info in infos:
        target = (dest_dir / info.filename).resolve()
        if target != dest_dir and not str(target).startswith(dest_prefix):
            raise UnsafeArchiveError(f"unsafe path escapes destination: {info.filename!r}")

        mode = (info.external_attr >> 16) & 0xFFFF
        if mode and stat.S_ISLNK(mode):
            raise UnsafeArchiveError(f"symlink entries are not allowed: {info.filename!r}")

        if not info.is_dir():
            if info.file_size > max_single_file_bytes:
                raise UnsafeArchiveError(
                    f"entry too large: {info.filename!r} ({info.file_size} bytes)"
                )
            total += info.file_size
            if total > max_total_uncompressed_bytes:
                raise UnsafeArchiveError(
                    "archive exceeds max uncompressed size (possible zip bomb)"
                )

        planned.append((info, target))

    written: list[Path] = []
    for info, target in planned:
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, open(target, "wb") as dst:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                dst.write(chunk)
        written.append(target)

    return ExtractResult(root=dest_dir, files=written, total_bytes=total)
