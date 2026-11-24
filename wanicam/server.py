"""WaniCam local web server."""
import argparse
import base64
import hmac
import json
import mimetypes
import os
import re
import sys
import threading
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

from . import __version__, brands, discovery, onvif, streams
from .store import Store

STATIC = Path(__file__).parent / "static"


class App:
    def __init__(self, data_dir, password=None, allowed_hosts=()):
        self.store = Store(Path(data_dir) / "cameras.json")
        self.relays = streams.RelayManager()
        self.password = password
        self.allowed_hosts = set(allowed_hosts)
        self.discover_lock = threading.Lock()
        self.last_discovery = None


# ---------------------------------------------------------------- probing

def probe_camera(host, brand="auto", user="", password="", rtsp_port=None, http_port=None,
                 channel=1, xaddrs=None, try_onvif=True):
    """Find working stream URLs for a camera using the credentials given."""
    results = {"host": host, "onvif": None, "rtsp": [], "http": [], "tried": 0}
    if try_onvif:
        o = onvif.lookup(host, xaddrs, user, password, extra_ports=[http_port] if http_port else [])
        results["onvif"] = o
        if o.get("ok"):
            info = " ".join(str(v) for v in (o.get("info") or {}).values())
            if brand in ("auto", None, "") and brands.guess_brand(info):
                brand = brands.guess_brand(info)
            for s in o["streams"]:
                if s.get("rtsp"):
                    uri, _, _ = streams.strip_creds(s["rtsp"])
                    # Cameras behind NAT sometimes report an internal address.
                    uri = re.sub(r"^(rtsps?://)[^/:]+", lambda m: m.group(1) + host, uri)
                    results["rtsp"].append({"url": uri, "label": f"ONVIF: {s.get('profile')}",
                                            "resolution": s.get("resolution"), "codec": s.get("encoding"),
                                            "source": "onvif"})
                if s.get("snapshot"):
                    snap = re.sub(r"^(https?://)[^/:]+", lambda m: m.group(1) + host, s["snapshot"])
                    results["http"].append({"url": snap, "type": "snapshot", "label": "ONVIF snapshot",
                                            "source": "onvif"})
    results["brand"] = brand
    # Verify ONVIF RTSP URLs and try brand patterns.
    order = []
    if brand not in ("auto", None, "", "generic"):
        order.append(brand)
    order.append("generic")
    rtsp_cands, http_cands = [], []
    seen = {r["url"] for r in results["rtsp"]} | {r["url"].split("?transportmode")[0] for r in results["rtsp"]}
    for b in order:
        kw = dict(user=user, password=password, channel=channel)
        if rtsp_port:
            kw["port"] = rtsp_port
        if http_port:
            kw["http_port"] = http_port
        for stream in (0, 1):
            for u in brands.candidates(b, "rtsp", host, stream=stream, **kw):
                u, _, _ = streams.strip_creds(u)
                if u not in seen and "{token}" not in u and not u.endswith("/"):
                    seen.add(u)
                    rtsp_cands.append((u, f"{brands.BRANDS[b]['label']} ({'main' if stream == 0 else 'sub'})"))
        for kind in ("mjpeg", "snapshot"):
            for u in brands.candidates(b, kind, host, **kw):
                if u not in seen:
                    seen.add(u)
                    http_cands.append((u, f"{brands.BRANDS[b]['label']} {kind}"))
    root = f"rtsp://{host}:{rtsp_port or 554}/"
    rtsp_cands.append((root, "Root path"))

    verified = []

    def check_rtsp(item):
        url, label, extra = item
        r = streams.ffprobe(url, user, password, timeout=6)
        return url, label, extra, r

    # ONVIF URLs first, then patterns. Small batches: cameras cap RTSP sessions, and
    # many lock the account after a few failed logins, so stop at the first 401.
    jobs = [(r["url"], r["label"], r) for r in results["rtsp"]] + [(u, l, None) for u, l in rtsp_cands]
    stop_after = 4
    with ThreadPoolExecutor(3) as ex:
        i = 0
        while i < len(jobs):
            batch = jobs[i:i + (1 if i == 0 else 3)]
            i += len(batch)
            for url, label, extra, r in ex.map(check_rtsp, batch):
                results["tried"] += 1
                if r.get("ok"):
                    item = dict(extra or {"url": url, "label": label, "source": "pattern"})
                    item.update({"ok": True, "codec": r["codec"], "resolution": f"{r['width']}x{r['height']}",
                                 "audio": r.get("audio")})
                    verified.append(item)
                elif extra:
                    verified.append({**extra, "ok": False, "error": r.get("error")})
                if (r.get("error") or "").startswith("Camera rejected"):
                    results["auth_error"] = r["error"]
            if results.get("auth_error") or sum(1 for v in verified if v.get("ok")) >= stop_after:
                break
    results["rtsp"] = verified

    hjobs, seen_http = [], set()
    for url, label in [(h["url"], h["label"]) for h in results["http"]] + http_cands:
        if url not in seen_http:
            seen_http.add(url)
            hjobs.append((url, label))
    results["http"] = []
    if not results.get("auth_error"):
        with ThreadPoolExecutor(3) as ex:
            for j in range(0, len(hjobs), 3):
                batch = hjobs[j:j + 3]
                for (url, label), r in zip(batch, ex.map(lambda b: streams.probe_http(b[0], user, password, 4), batch)):
                    results["tried"] += 1
                    if r.get("ok"):
                        results["http"].append({"url": url, "label": label, "type": r["type"], "ok": True})
                    elif "rejected" in (r.get("error") or ""):
                        results["auth_error"] = r["error"]
                if results.get("auth_error"):
                    break
    return results


# ---------------------------------------------------------------- HTTP handler

class Handler(BaseHTTPRequestHandler):
    server_version = f"WaniCam/{__version__}"
    protocol_version = "HTTP/1.1"
    app: App = None

    def log_message(self, fmt, *args):
        if os.environ.get("WANICAM_DEBUG"):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # -- helpers
    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _err(self, msg, code=400):
        self._json({"error": msg}, code)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("Body too large")
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def _guard(self):
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
        if self.app.allowed_hosts and host not in self.app.allowed_hosts:
            # Blocks DNS-rebinding attacks from web pages you visit.
            self._err("Host not allowed. Start WaniCam with --host to allow it.", 403)
            return False
        if self.app.password:
            auth = self.headers.get("Authorization") or ""
            ok = False
            if auth.startswith("Basic "):
                try:
                    _, pw = base64.b64decode(auth[6:]).decode().split(":", 1)
                    ok = hmac.compare_digest(pw, self.app.password)
                except Exception:
                    ok = False
            if not ok:
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="WaniCam"')
                self.send_header("Content-Length", "0")
                self.end_headers()
                return False
        if self.command in ("POST", "PUT", "DELETE"):
            # Same-origin check for state-changing requests.
            origin = self.headers.get("Origin")
            if origin and urlsplit(origin).netloc != self.headers.get("Host"):
                self._err("Cross-origin request refused", 403)
                return False
        return True

    # -- routes
    def do_GET(self):
        if not self._guard():
            return
        u = urlsplit(self.path)
        p, q = u.path, parse_qs(u.query)
        try:
            if p == "/api/info":
                return self._json({"version": __version__, "ffmpeg": bool(streams.FFMPEG),
                                   "subnets": discovery.default_subnets(), "local_ips": discovery.local_ipv4s()})
            if p == "/api/brands":
                return self._json(brands.brand_list())
            if p == "/api/cameras":
                return self._json({"cameras": self.app.store.list(), "status": self.app.relays.status()})
            if p == "/api/discover/last":
                return self._json(self.app.last_discovery or {"devices": []})
            if p == "/api/local-devices":
                return self._json([{"id": d["id"], "name": d["name"]} for d in streams.local_devices()])
            m = re.fullmatch(r"/stream/([\w-]+)\.mjpg", p)
            if m:
                return self._stream(m.group(1), q)
            m = re.fullmatch(r"/snapshot/([\w-]+)\.jpg", p)
            if m:
                return self._snapshot(m.group(1))
            return self._static(p)
        except BrokenPipeError:
            pass
        except Exception as e:
            try:
                self._err(str(e), 500)
            except Exception:
                pass

    def do_POST(self):
        if not self._guard():
            return
        p = urlsplit(self.path).path
        try:
            data = self._body()
            if p == "/api/discover":
                return self._discover(data)
            if p == "/api/probe":
                if not data.get("host"):
                    return self._err("host is required")
                return self._json(probe_camera(
                    data["host"].strip(), data.get("brand") or "auto", data.get("user") or "",
                    data.get("password") or "", _int(data.get("rtsp_port")), _int(data.get("http_port")),
                    _int(data.get("channel")) or 1, data.get("xaddrs"), data.get("onvif", True)))
            if p == "/api/onvif":
                return self._json(onvif.lookup(data.get("host"), data.get("xaddrs"),
                                               data.get("user") or "", data.get("password") or ""))
            if p == "/api/test":
                url = (data.get("url") or "").strip()
                pw = data.get("password") or ""
                if data.get("id") and not pw:
                    pw = (self.app.store.get(data["id"]) or {}).get("password", "")
                if url.startswith("http"):
                    r = streams.probe_http(url, data.get("user") or "", pw)
                    if not r.get("ok") and streams.FFPROBE:
                        r2 = streams.ffprobe(url, data.get("user") or "", pw)
                        r = r2 if r2.get("ok") else r
                    return self._json(r)
                return self._json(streams.ffprobe(url, data.get("user") or "", pw,
                                                  data.get("transport") or "tcp"))
            if p == "/api/cameras":
                kind = data.get("kind")
                if kind not in ("rtsp", "mjpeg", "snapshot", "local", "ffmpeg"):
                    return self._err("kind must be rtsp, mjpeg, snapshot, local or ffmpeg")
                if kind == "local" and not data.get("device"):
                    return self._err("device is required")
                if kind != "local":
                    if not data.get("url"):
                        return self._err("url is required")
                    url, u2, p2 = streams.strip_creds(data["url"].strip())
                    data["url"] = url
                    data["user"] = data.get("user") or u2
                    data["password"] = data.get("password") or p2
                    data.setdefault("host", urlsplit(url).hostname)
                cam = self.app.store.upsert(data)
                self.app.relays.drop(cam["id"])
                return self._json(self.app.store.public(cam))
            return self._err("Not found", 404)
        except BrokenPipeError:
            pass
        except Exception as e:
            self._err(str(e), 500)

    def do_DELETE(self):
        if not self._guard():
            return
        m = re.fullmatch(r"/api/cameras/([\w-]+)", urlsplit(self.path).path)
        if not m:
            return self._err("Not found", 404)
        self.app.relays.drop(m.group(1))
        self._json({"deleted": self.app.store.delete(m.group(1))})

    # -- features
    def _discover(self, data):
        if not self.app.discover_lock.acquire(blocking=False):
            return self._err("A scan is already running", 409)
        try:
            subnets = [s.strip() for s in (data.get("subnets") or []) if s.strip()] or None
            if subnets:
                import ipaddress
                for s in subnets:
                    net = ipaddress.ip_network(s, strict=False)
                    if not net.is_private:
                        return self._err(f"{s} is not a private (local) network. "
                                         "WaniCam only scans local networks.")
                    if net.num_addresses > 4096:
                        return self._err(f"{s} is too large; use /20 or smaller.")
            res = discovery.discover_all(subnets, scan=data.get("scan", True))
            saved_hosts = {c.get("host") for c in self.app.store.list()}
            for d in res["devices"]:
                d["saved"] = d["ip"] in saved_hosts
            self.app.last_discovery = res
            return self._json(res)
        finally:
            self.app.discover_lock.release()

    def _cam(self, cid):
        cam = self.app.store.get(cid)
        if not cam:
            self._err("Unknown camera", 404)
        return cam

    def _stream(self, cid, q):
        cam = self._cam(cid)
        if not cam:
            return
        if cam["kind"] == "snapshot":
            return self._snapshot_stream(cam)
        if not streams.FFMPEG:
            return self._err("ffmpeg is not installed. See README for install steps.", 503)
        fps = min(max(_int(q.get("fps", [None])[0]) or _int(cam.get("fps")) or 10, 1), 30)
        width = _int(q.get("w", [None])[0]) or _int(cam.get("width"))
        relay = self.app.relays.get(cam, fps=fps, width=width)
        if relay.wait_first(20) is None:
            return self._err(relay.error or "No video received from the camera", 502)
        self._send_mjpeg(relay.frames())

    def _send_mjpeg(self, frames):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            for f in frames:
                self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                 + str(len(f)).encode() + b"\r\n\r\n" + f + b"\r\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def _snapshot_stream(self, cam):
        import time

        def gen():
            while True:
                try:
                    yield streams.grab_snapshot(cam)
                except Exception:
                    return
                time.sleep(max(0.2, 1.0 / max(_int(cam.get("fps")) or 1, 1)))
        self._send_mjpeg(gen())

    def _snapshot(self, cid):
        cam = self._cam(cid)
        if not cam:
            return
        try:
            relay = None
            for (k, _), r in list(self.app.relays.relays.items()):
                if k == cid and not r.stopped and r.frame:
                    relay = r
            img = relay.frame if relay else streams.grab_snapshot(cam)
        except Exception as e:
            return self._err(str(e), 502)
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(img)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(img)

    def _static(self, p):
        if p in ("", "/"):
            p = "/index.html"
        f = (STATIC / p.lstrip("/")).resolve()
        if STATIC.resolve() not in f.parents or not f.is_file():
            return self._err("Not found", 404)
        body = f.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(str(f))[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; "
                         "style-src 'self' 'unsafe-inline'")
        self.end_headers()
        self.wfile.write(body)


def _int(v):
    try:
        return int(v) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None


def main(argv=None):
    ap = argparse.ArgumentParser(prog="wanicam", description="Discover and view cameras on your network.")
    ap.add_argument("--host", default="127.0.0.1",
                    help="Address to listen on (default 127.0.0.1, this computer only). "
                         "Use 0.0.0.0 to view from other devices, together with --password.")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--data-dir", default=os.environ.get("WANICAM_DATA", str(Path.home() / ".wanicam")))
    ap.add_argument("--password", default=os.environ.get("WANICAM_PASSWORD"),
                    help="Require this password to open the dashboard (any username).")
    ap.add_argument("--no-browser", action="store_true", help="Don't open a browser window.")
    args = ap.parse_args(argv)

    if args.host not in ("127.0.0.1", "localhost", "::1") and not args.password:
        print("WARNING: listening on the network without --password. Anyone on your network "
              "could view your cameras.", file=sys.stderr)
    allowed = {"127.0.0.1", "localhost", "::1"}
    if args.host not in ("127.0.0.1", "localhost", "::1"):
        allowed = set()  # Any host name allowed when you deliberately expose it.
    Handler.app = App(args.data_dir, args.password, allowed)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    url = f"http://{'localhost' if args.host in ('127.0.0.1', '0.0.0.0', '::') else args.host}:{args.port}/"
    print(f"WaniCam {__version__} running at {url}")
    if not streams.FFMPEG:
        print("NOTE: ffmpeg not found. RTSP cameras and USB cameras need ffmpeg; "
              "MJPEG/snapshot cameras still work.", file=sys.stderr)
    if not args.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        for r in list(Handler.app.relays.relays.values()):
            r.stop()


if __name__ == "__main__":
    main()
