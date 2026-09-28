"""Minimal, dependency-free multipart/form-data parser.

The stdlib's ``cgi`` module is deprecated/removed, and pulling in a web
framework just to parse two form fields is more dependency surface than this
sidecar needs. This parser handles the well-formed subset of RFC 7578 that
every HTTP client (curl -F, requests, browsers) produces, and nothing more.

Binary safety note: earlier drafts of this used ``bytes.strip(b"\\r\\n")``
to trim part boundaries, which is wrong for binary payloads (a zip file can
legitimately end in bytes that look like CR/LF). This implementation only
ever removes an *exact* 2-byte ``\\r\\n`` sequence from a known position, so
binary blob content is never corrupted.
"""
from __future__ import annotations

from dataclasses import dataclass


class MultipartError(ValueError):
    pass


@dataclass
class Part:
    name: str
    data: bytes
    filename: str | None = None
    content_type: str | None = None


def parse_content_type(header_value: str) -> tuple[str, dict[str, str]]:
    pieces = header_value.split(";")
    main = pieces[0].strip().lower()
    params: dict[str, str] = {}
    for piece in pieces[1:]:
        if "=" not in piece:
            continue
        key, _, value = piece.strip().partition("=")
        value = value.strip().strip('"')
        params[key.strip().lower()] = value
    return main, params


def parse_multipart(body: bytes, boundary: str) -> dict[str, list[Part]]:
    delimiter = ("--" + boundary).encode("latin-1")
    raw_parts = body.split(delimiter)
    if len(raw_parts) < 3:
        raise MultipartError("body does not contain a valid multipart envelope")

    result: dict[str, list[Part]] = {}
    # raw_parts[0] is the preamble before the first boundary (usually empty),
    # raw_parts[-1] is the closing "--\r\n" after the final boundary.
    for raw in raw_parts[1:-1]:
        if raw.startswith(b"\r\n"):
            raw = raw[2:]
        elif raw.startswith(b"\n"):
            raw = raw[1:]
        if raw.endswith(b"\r\n"):
            raw = raw[:-2]
        elif raw.endswith(b"\n"):
            raw = raw[:-1]

        header_blob, sep, data = raw.partition(b"\r\n\r\n")
        if not sep:
            continue

        headers: dict[str, str] = {}
        for line in header_blob.split(b"\r\n"):
            if not line:
                continue
            key, _, value = line.decode("latin-1").partition(":")
            headers[key.strip().lower()] = value.strip()

        disposition = headers.get("content-disposition", "")
        if "form-data" not in disposition.lower():
            continue

        name = None
        filename = None
        for piece in disposition.split(";"):
            piece = piece.strip()
            if piece.startswith("name="):
                name = piece[len("name="):].strip('"')
            elif piece.startswith("filename="):
                filename = piece[len("filename="):].strip('"')

        if name is None:
            continue

        result.setdefault(name, []).append(
            Part(name=name, data=data, filename=filename, content_type=headers.get("content-type"))
        )

    if not result:
        raise MultipartError("no form-data parts found in body")
    return result
