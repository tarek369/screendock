#!/usr/bin/env python3
"""AirPlay 2 video (URL playback) test sender built on pyatv.

Drives the AirPlay 2 "play_url" flow (HAP transient pairing -> encrypted RTSP ->
SETUP -> RECORD -> POST /play -> playback-info polling) against any receiver and
prints/records a decoded plaintext trace of every request and response.

    airplay2_sender.py discover
    airplay2_sender.py play  "LGwebOSTV" --duration 10 --trace-file out.txt
    airplay2_sender.py play  192.168.20.209:38545 --mp4
    airplay2_sender.py scrub "LGwebOSTV" 30
    airplay2_sender.py rate  "LGwebOSTV" 0
    airplay2_sender.py stop  "LGwebOSTV"

AirPlay sessions live and die with the TCP connection, so scrub/rate/stop each
start playback first, wait, apply the action, observe, then stop.
Use --actions for an arbitrary script, e.g. "wait:4,scrub:30,rate:0,wait:2,rate:1,stop".
"""
import argparse
import asyncio
import logging
import plistlib
import sys
from typing import Any, Dict, List, Optional, Tuple

import pyatv
from pyatv import exceptions
from pyatv.auth.hap_pairing import NO_CREDENTIALS, TRANSIENT_CREDENTIALS
from pyatv.protocols.airplay.player import timing_server
from pyatv.protocols.raop.protocols import StreamContext
from pyatv.protocols.raop.protocols.airplayv2 import AirPlayV2
from pyatv.support.http import decode_bplist_from_body, http_connect
from pyatv.support.rtsp import RtspSession

import rpctrace as trace_mod

HLS_URL = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8"
# Progressive (non-HLS) H.264/AAC mp4 for the plain-file path.
PROGRESSIVE_MP4_URL = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

PLAY_HEADERS = {
    "User-Agent": "AirPlay/550.10",
    "X-Apple-ProtocolVersion": "1",
    "X-Apple-Stream-ID": "1",
}

LOG = logging.getLogger("airplay2.sender")


def out(msg: str) -> None:
    print(msg, flush=True)
    LOG.info("# %s", msg)


def _norm(text: str) -> str:
    return "".join(c for c in text.lower() if c.isalnum())


async def resolve(target: str, timeout: int) -> Tuple[str, int, str]:
    """Return (host, port, name) for 'host:port' or a (partial) device name."""
    if ":" in target and not target.startswith("["):
        host, _, port = target.rpartition(":")
        if port.isdigit():
            return host, int(port), target
    loop = asyncio.get_running_loop()
    for conf in await pyatv.scan(loop, timeout=timeout):
        svc = conf.get_service(pyatv.Protocol.AirPlay)
        if svc and _norm(target) in _norm(conf.name):
            return str(conf.address), svc.port, conf.name
    raise SystemExit("no AirPlay receiver matching %r found" % target)


async def discover(args: argparse.Namespace) -> int:
    for conf in await pyatv.scan(asyncio.get_running_loop(), timeout=args.timeout):
        svc = conf.get_service(pyatv.Protocol.AirPlay)
        if svc:
            props = dict(svc.properties)
            out("%-40s %s:%d features=%s model=%s" % (
                conf.name, conf.address, svc.port, props.get("features", props.get("ft")),
                props.get("model", props.get("am"))))
    return 0


class Session:
    """One AirPlay 2 video session = one TCP connection."""

    def __init__(self, host: str, port: int, pairing: str) -> None:
        self.host, self.port, self.pairing = host, port, pairing
        self.rtsp: Optional[RtspSession] = None
        self.proto: Optional[AirPlayV2] = None

    async def start(self, url: str, position: float, play_protocol: str = "http") -> Any:
        """pyatv's AirPlayV2.play_url sequence, with the /play framing selectable."""
        conn = await http_connect(self.host, self.port)
        self.rtsp = RtspSession(conn)
        ctx = StreamContext()
        ctx.credentials = TRANSIENT_CREDENTIALS if self.pairing == "transient" else NO_CREDENTIALS
        self.proto = AirPlayV2(ctx, self.rtsp)
        async with timing_server(self.rtsp) as srv:
            self._timing = srv
            await self.proto._setup_base(srv.port)
            await self.proto.start_feedback()
            await self.rtsp.record()
            body = self.proto_play_body(url, position)
            hdrs = dict(PLAY_HEADERS, **{"X-Apple-Session-ID": self.proto.uuid,
                                         "Content-Type": "application/x-apple-binary-plist"})
            if play_protocol == "http":  # what pyatv does
                resp = await self.rtsp.connection.post(
                    "/play", headers=hdrs, body=plistlib.dumps(body, fmt=plistlib.FMT_BINARY),
                    allow_error=True)
            else:
                resp = await self.rtsp.exchange("POST", uri="/play", headers=hdrs, body=body,
                                                allow_error=True)
            if resp.code < 400:
                for key, val in (("isInterestedInDateRange", True), ("actionAtItemEnd", 0)):
                    await self.rtsp.exchange("PUT", uri="/setProperty?%s" % key,
                                             body={"value": val}, allow_error=True)
                await self.rtsp.exchange("POST", uri="/rate?value=1.000000", allow_error=True)
            return resp

    def proto_play_body(self, url: str, position: float) -> Dict[str, Any]:
        return {
            "Content-Location": url, "Start-Position-Seconds": position,
            "uuid": self.proto.uuid, "streamType": 1, "mediaType": "file",
            "mightSupportStorePastisKeyRequests": True, "playbackRestrictions": 0,
            "volume": 1.0, "SenderMACAddress": "AA:BB:CC:DD:EE:FF", "model": "iPhone14,3",
            "clientBundleID": "dev.pyatv.GPU", "clientProcName": "dev.pyatv.GPU",
            "osBuildVersion": "20G1116", "rate": 1.0,
        }

    async def playback_info(self) -> Dict[str, Any]:
        resp = await self.rtsp.connection.get("/playback-info", allow_error=True)
        if resp.code != 200 or not resp.body:
            return {"_status": resp.code}
        try:
            return decode_bplist_from_body(resp)
        except Exception:  # noqa: BLE001
            return {"_status": resp.code, "_undecodable": True}

    async def action(self, name: str, arg: Optional[str]) -> None:
        r = self.rtsp
        if name == "rate":
            await r.exchange("POST", uri="/rate?value=%.6f" % float(arg), allow_error=True)
        elif name == "scrub":
            await r.exchange("POST", uri="/scrub?position=%.6f" % float(arg), allow_error=True)
        elif name == "getscrub":
            await r.exchange("GET", uri="/scrub", allow_error=True)
        elif name == "stop":
            await r.exchange("POST", uri="/stop", allow_error=True)
        elif name == "getprop":
            await r.exchange("POST", uri="/getProperty?%s" % arg, allow_error=True)
        elif name == "setprop":
            key, _, val = (arg or "").partition("=")
            await r.exchange("PUT", uri="/setProperty?%s" % key,
                             body={"value": val.lower() in ("1", "true")}, allow_error=True)
        else:
            raise SystemExit("unknown action %r" % name)

    def close(self) -> None:
        if self.proto:
            self.proto.teardown()
        if self.rtsp:
            self.rtsp.connection.close()


PROBES = [
    ("GET", "/info", "RTSP/1.0", None), ("POST", "/play", "RTSP/1.0", {"Content-Location": "http://x/y.m3u8"}),
    ("POST", "/reverse", "HTTP/1.1", None), ("POST", "/fp-setup", "RTSP/1.0", None),
    ("GET", "/playback-info", "RTSP/1.0", None), ("POST", "/stop", "RTSP/1.0", None),
    ("GET", "/server-info", "HTTP/1.1", None), ("POST", "/command", "RTSP/1.0", {}),
    ("POST", "/action", "RTSP/1.0", {"type": "playlistInsert"}),
    ("GET", "/scrub", "RTSP/1.0", None), ("OPTIONS", "*", "RTSP/1.0", None),
]


async def probe(args: argparse.Namespace) -> int:
    """Pair + SETUP + RECORD, then report which routes the receiver implements."""
    host, port, name = await resolve(args.target, args.scan_timeout)
    sess = Session(host, port, args.pairing)
    conn = await http_connect(host, port)
    sess.rtsp = RtspSession(conn)
    ctx = StreamContext()
    ctx.credentials = TRANSIENT_CREDENTIALS if args.pairing == "transient" else NO_CREDENTIALS
    sess.proto = AirPlayV2(ctx, sess.rtsp)
    try:
        async with timing_server(sess.rtsp) as srv:
            await sess.proto._setup_base(srv.port)
            await sess.rtsp.record()
            for method, uri, proto, body in PROBES:
                uri = sess.rtsp.uri if uri == "*" else uri
                r = await sess.rtsp.exchange(method, uri=uri, protocol=proto, body=body, allow_error=True)
                out("%-5s %-16s %-8s -> %s %s" % (method, uri[:16], proto, r.code, r.message))
    except Exception as ex:  # noqa: BLE001
        out("FAILED: %s: %s" % (type(ex).__name__, ex))
        return 2
    finally:
        sess.close()
    return 0


def parse_actions(script: str) -> List[Tuple[str, Optional[str]]]:
    items = []
    for part in filter(None, (p.strip() for p in script.split(","))):
        name, _, arg = part.partition(":")
        items.append((name.lower(), arg or None))
    return items


def summarize(info: Dict[str, Any]) -> str:
    keys = ("duration", "position", "rate", "readyToPlay", "playbackBufferEmpty",
            "playbackBufferFull", "playbackLikelyToKeepUp", "error", "_status")
    return ", ".join("%s=%s" % (k, info[k]) for k in keys if k in info)


async def run(args: argparse.Namespace, script: List[Tuple[str, Optional[str]]]) -> int:
    host, port, name = await resolve(args.target, args.scan_timeout)
    url = args.url or (PROGRESSIVE_MP4_URL if args.mp4 else HLS_URL)
    out("target=%s (%s:%d) pairing=%s url=%s" % (name, host, port, args.pairing, url))
    sess = Session(host, port, args.pairing)
    rc = 0
    try:
        try:
            resp = await sess.start(url, args.position, args.play_protocol)
        except Exception as ex:  # noqa: BLE001
            out("FAILED during pairing/setup/play: %s: %s" % (type(ex).__name__, ex))
            return 2
        out("POST /play -> %s %s" % (resp.code, resp.message))
        if resp.code >= 400:
            return 3
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        pending = list(script)
        next_action_at = 0.0
        while loop.time() - t0 < args.duration:
            info = await sess.playback_info()
            out("t=%4.1fs playback-info: %s" % (loop.time() - t0, summarize(info)))
            if "error" in info:
                rc = 4
                break
            if pending and loop.time() - t0 >= next_action_at:
                act, arg = pending.pop(0)
                if act == "wait":
                    next_action_at = loop.time() - t0 + float(arg or 1)
                else:
                    out("action %s %s" % (act, arg or ""))
                    await sess.action(act, arg)
            await asyncio.sleep(1.0)
        info = await sess.playback_info()
        out("final playback-info: %s" % summarize(info))
        out("sending POST /stop")
        await sess.action("stop", None)
        await asyncio.sleep(0.5)
    finally:
        sess.close()
    return rc


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-v", "--verbose", action="store_true", help="log every request/response (decoded, plaintext, redacted)")
    p.add_argument("--trace-file", help="write the decoded trace to this file")
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("discover", help="list AirPlay receivers via mDNS")
    d.add_argument("--timeout", type=int, default=5)

    def common(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("target", help="device name (substring) or host:port")
        sp.add_argument("--url", help="media URL (default: Apple bipbop HLS)")
        sp.add_argument("--mp4", action="store_true", help="use a progressive mp4 test URL")
        sp.add_argument("--position", type=float, default=0.0, help="Start-Position-Seconds")
        sp.add_argument("--duration", type=float, default=10.0, help="seconds to keep playing before /stop")
        sp.add_argument("--pairing", choices=["transient"], default="transient",
                        help="AirPlay 2 HAP transient pairing (pyatv cannot run the V2 event channel unpaired)")
        sp.add_argument("--play-protocol", choices=["http", "rtsp"], default="http",
                        help="framing of POST /play: HTTP/1.1 (pyatv default) or RTSP/1.0")
        sp.add_argument("--scan-timeout", type=int, default=5)

    pl = sub.add_parser("play", help="play a URL, poll playback-info, then stop")
    common(pl)
    pl.add_argument("--actions", default="", help="comma script: wait:N,scrub:S,rate:R,getscrub,stop,getprop:K,setprop:K=V")
    for name, argname in (("scrub", "position"), ("rate", "value")):
        sp = sub.add_parser(name, help="play, then %s" % name)
        common(sp)
        sp.add_argument(argname, type=float)
    pr = sub.add_parser("probe", help="pair, SETUP, then list which routes return 404")
    common(pr)
    st = sub.add_parser("stop", help="play briefly, then POST /stop explicitly")
    common(st)
    return p


def main() -> int:
    args = build_parser().parse_args()
    trace_mod.setup_logging(args.verbose, args.trace_file)
    if args.cmd == "discover":
        return asyncio.run(discover(args))
    if args.cmd == "probe":
        return asyncio.run(probe(args))
    if args.cmd == "play":
        script = parse_actions(args.actions)
    elif args.cmd == "scrub":
        script = [("wait", "3"), ("scrub", str(args.position)), ("wait", "3"), ("getscrub", None)]
    elif args.cmd == "rate":
        script = [("wait", "3"), ("rate", str(args.value)), ("wait", "3"), ("rate", "1")]
    else:
        script = [("wait", "3")]
    try:
        return asyncio.run(run(args, script))
    except exceptions.BaseError as ex:
        print("error: %s" % ex, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
