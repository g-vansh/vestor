"""Tiny stdlib HTTP helpers — no third-party deps (keeps the Pi image lean).

JSON + raw-bytes GET with a timeout, a real User-Agent (some feeds 403 the
default urllib UA), and transparent gzip handling.
"""
from __future__ import annotations

import gzip
import json
import urllib.request
import urllib.error

_UA = "vestor-led-wall/1.0 (+https://github.com/g-vansh/vestor)"
DEFAULT_TIMEOUT = 8.0


def get_bytes(url: str, timeout: float = DEFAULT_TIMEOUT, headers: dict | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": _UA, "Accept-Encoding": "gzip", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        return raw


def get_json(url: str, timeout: float = DEFAULT_TIMEOUT, headers: dict | None = None):
    return json.loads(get_bytes(url, timeout=timeout, headers=headers).decode("utf-8"))
