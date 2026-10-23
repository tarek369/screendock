"""Plaintext request/response tracing for pyatv's HTTP/RTSP connection.

pyatv logs the raw (pre-encryption) bytes of every message at DEBUG level, but
that includes key material (SETUP "shk", pair-setup TLVs). This module wraps
HttpConnection.send_and_receive and emits a decoded, redacted trace instead,
and filters pyatv's own raw-byte log records.
"""
import logging
import plistlib
from typing import Any, Optional

from pyatv.support import http

TRACE = logging.getLogger("airplay2.trace")

SECRET_KEYS = {"shk", "eiv", "ekey", "pk", "key"}
PAIR_PATHS = ("/pair-setup", "/pair-verify", "/pair-pin-start")
TLV_NAMES = {0: "Method", 1: "Identifier", 2: "Salt", 3: "PublicKey", 4: "Proof",
             5: "EncryptedData", 6: "SeqNo", 7: "Error", 8: "BackOff", 9: "Certificate",
             10: "Signature", 11: "Permissions", 12: "FragmentData", 13: "FragmentLast",
             17: "Name", 19: "Flags"}


def _redact(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {k: ("<redacted %d bytes>" % len(v) if k in SECRET_KEYS and isinstance(v, bytes)
                    else _redact(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_redact(v) for v in obj]
    if isinstance(obj, bytes):
        return obj if len(obj) <= 16 else "<%d bytes>" % len(obj)
    return obj


def _tlv(body: bytes) -> str:
    out, i = [], 0
    while i + 2 <= len(body):
        t, ln = body[i], body[i + 1]
        val = body[i + 2:i + 2 + ln]
        shown = val.hex() if t in (0, 6, 7, 19) and ln <= 4 else "<%dB>" % ln
        out.append("%s(%d)=%s" % (TLV_NAMES.get(t, t), ln, shown))
        i += 2 + ln
    return "TLV8[" + ", ".join(out) + "]"


def describe_body(path: str, body: Any) -> str:
    if not body:
        return "(no body)"
    if isinstance(body, str):
        raw = body.encode("utf-8")
    elif isinstance(body, bytes):
        raw = body
    else:
        return repr(body)
    if raw.startswith(b"bplist00") or raw.startswith(b"<?xml"):
        try:
            return "plist " + repr(_redact(plistlib.loads(raw)))
        except Exception as ex:  # noqa: BLE001 - trace must never break the flow
            return "undecodable plist (%s) %dB" % (ex, len(raw))
    if path.split("?")[0] in PAIR_PATHS:
        return _tlv(raw)
    try:
        return "text %r" % raw.decode("utf-8")[:400]
    except UnicodeDecodeError:
        return "binary %dB" % len(raw)


def _fmt_headers(headers: Any) -> str:
    return "".join("\n    %s: %s" % (k, v) for k, v in dict(headers or {}).items())


def install() -> None:
    """Wrap HttpConnection.send_and_receive with tracing (idempotent)."""
    orig = http.HttpConnection.send_and_receive
    if getattr(orig, "_traced", False):
        return

    async def traced(self, method, uri, protocol="HTTP/1.1", user_agent=http.USER_AGENT,
                     content_type=None, headers=None, body=None, allow_error=False,
                     timeout=10):
        hdrs = dict(headers or {})
        if content_type:
            hdrs["Content-Type"] = content_type
        TRACE.info(">> %s %s %s%s\n    BODY: %s", method, uri, protocol,
                   _fmt_headers(hdrs), describe_body(uri, body))
        try:
            resp = await orig(self, method, uri, protocol, user_agent, content_type,
                              headers, body, allow_error, timeout)
        except Exception as ex:
            TRACE.info("<< ERROR for %s %s: %s: %s", method, uri, type(ex).__name__, ex)
            raise
        TRACE.info("<< %s %s %s%s\n    BODY: %s", resp.protocol, resp.code, resp.message,
                   _fmt_headers(resp.headers), describe_body(uri, resp.body))
        return resp

    traced._traced = True  # type: ignore[attr-defined]
    http.HttpConnection.send_and_receive = traced  # type: ignore[assignment]


class DropRawBytes(logging.Filter):
    """Drop pyatv log records that print raw wire bytes or key material."""

    NOISY = ("pyatv.support.http", "pyatv.auth.", "pyatv.protocols.airplay.auth.",
             "pyatv.support.chacha20", "pyatv.core.scan", "pyatv.support.net")

    def filter(self, record: logging.LogRecord) -> bool:
        if record.name == "pyatv.protocols.airplay.channels":
            return "Got message" in record.getMessage()
        return not record.name.startswith(self.NOISY)


def setup_logging(verbose: bool, trace_file: Optional[str]) -> None:
    fmt = logging.Formatter("%(asctime)s.%(msecs)03d %(name)s: %(message)s", "%H:%M:%S")
    handlers = []
    if verbose:
        handlers.append(logging.StreamHandler())
    if trace_file:
        handlers.append(logging.FileHandler(trace_file, mode="w"))
    for h in handlers:
        h.setFormatter(fmt)
        h.addFilter(DropRawBytes())
        logging.getLogger().addHandler(h)
    logging.getLogger().setLevel(logging.DEBUG if handlers else logging.WARNING)
    logging.getLogger("asyncio").setLevel(logging.WARNING)
    logging.getLogger("zeroconf").setLevel(logging.WARNING)
    install()
