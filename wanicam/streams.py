"""Turn camera streams into something every browser can show.

RTSP / RTSPS / RTMP / HLS / local capture devices -> ffmpeg -> MJPEG frames,
shared between all viewers of the same camera. HTTP MJPEG and JPEG snapshot
cameras are proxied directly (with the camera's credentials added server side
so they never reach the browser).
"""
import glob
import json
import platform
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request

FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")


def with_creds(url, user, password):
    """Insert user:password into a URL (rtsp/http) unless it already has some."""
    if not user:
        return url
    p = urllib.parse.urlsplit(url)
    if p.username:
        return url
    host = p.hostname or ""
    if ":" in host:
        host = f"[{host}]"
    netloc = f"{urllib.parse.quote(user, safe='')}:{urllib.parse.quote(password or '', safe='')}@{host}"
    if p.port:
        netloc += f":{p.port}"
    return urllib.parse.urlunsplit((p.scheme, netloc, p.path, p.query, p.fragment))


def strip_creds(url):
    p = urllib.parse.urlsplit(url)
    if not p.username:
        return url, "", ""
    host = p.hostname or ""
    netloc = host + (f":{p.port}" if p.port else "")
    return (urllib.parse.urlunsplit((p.scheme, netloc, p.path, p.query, p.fragment)),
            urllib.parse.unquote(p.username or ""), urllib.parse.unquote(p.password or ""))


def http_open(url, user="", password="", timeout=8):
    url, u2, p2 = strip_creds(url)
    user, password = user or u2, password or p2
    handlers = []
    if user:
        pm = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        pm.add_password(None, url, user, password)
        handlers = [urllib.request.HTTPDigestAuthHandler(pm), urllib.request.HTTPBasicAuthHandler(pm)]
    opener = urllib.request.build_opener(*handlers)
    req = urllib.request.Request(url, headers={"User-Agent": "WaniCam"})
    return opener.open(req, timeout=timeout)


# ------------------------------------------------------------------ local devices

def local_devices():
    """List capture devices ffmpeg can open on this computer."""
    out = []
    if not FFMPEG:
        return out
    sysname = platform.system()
    if sysname == "Linux":
        for dev in sorted(glob.glob("/dev/video*")):
            name = dev
            try:
                with open(f"/sys/class/video4linux/{dev.rsplit('/', 1)[-1]}/name") as f:
                    name = f.read().strip() + f" ({dev})"
            except OSError:
                pass
            out.append({"id": dev, "name": name, "input": ["-f", "v4l2", "-i", dev]})
    elif sysname == "Darwin":
        r = subprocess.run([FFMPEG, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
                           capture_output=True, text=True, timeout=10)
        video = True
        for line in r.stderr.splitlines():
            if "audio devices" in line:
                video = False
            m = re.search(r"\[(\d+)\] (.+)$", line)
            if m and video and "AVFoundation" in line:
                out.append({"id": m.group(1), "name": m.group(2),
                            "input": ["-f", "avfoundation", "-framerate", "30", "-i", f"{m.group(1)}:none"]})
    elif sysname == "Windows":
        r = subprocess.run([FFMPEG, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
                           capture_output=True, text=True, timeout=10)
        for line in r.stderr.splitlines():
            m = re.search(r'"([^"]+)"\s*\(video\)', line)
            if m:
                out.append({"id": m.group(1), "name": m.group(1),
                            "input": ["-f", "dshow", "-i", f"video={m.group(1)}"]})
    return out


def input_args(cam):
    kind = cam.get("kind")
    if kind == "local":
        for d in local_devices():
            if d["id"] == cam.get("device"):
                return d["input"]
        raise ValueError(f"Local device {cam.get('device')} not found")
    url = with_creds(cam["url"], cam.get("user"), cam.get("password"))
    args = ["-fflags", "nobuffer", "-flags", "low_delay"]
    if url.startswith(("rtsp://", "rtsps://")):
        args += ["-rtsp_transport", cam.get("transport") or "tcp", "-timeout", "10000000"]
    elif url.startswith(("http://", "https://")):
        args += ["-reconnect", "1", "-reconnect_streamed", "1"]
    return args + ["-i", url]


# ------------------------------------------------------------------ relay

class Relay:
    """One ffmpeg process per camera, fanned out to any number of viewers."""

    IDLE_STOP = 15

    def __init__(self, cam, fps=10, width=None, quality=5):
        self.cam = cam
        self.fps, self.width, self.quality = fps, width, quality
        self.frame = None
        self.frame_no = 0
        self.cond = threading.Condition()
        self.viewers = 0
        self.last_viewer = time.time()
        self.error = None
        self.proc = None
        self.stopped = False
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _cmd(self):
        vf = [f"fps={self.fps}"]
        if self.width:
            vf.append(f"scale={self.width}:-2")
        return [FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin", *input_args(self.cam),
                "-an", "-vf", ",".join(vf), "-q:v", str(self.quality), "-f", "mjpeg", "pipe:1"]

    def _run(self):
        backoff = 1
        while not self.stopped:
            try:
                self.proc = subprocess.Popen(self._cmd(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            except Exception as e:
                self.error = str(e)
                return
            err_lines = []
            threading.Thread(target=self._read_err, args=(self.proc, err_lines), daemon=True).start()
            buf = b""
            got = False
            while not self.stopped:
                chunk = self.proc.stdout.read(65536)
                if not chunk:
                    break
                buf += chunk
                while True:
                    s = buf.find(b"\xff\xd8")
                    e = buf.find(b"\xff\xd9", s + 2) if s >= 0 else -1
                    if s < 0 or e < 0:
                        if s > 0:
                            buf = buf[s:]
                        break
                    with self.cond:
                        self.frame = buf[s:e + 2]
                        self.frame_no += 1
                        self.error = None
                        self.cond.notify_all()
                    got = True
                    backoff = 1
                    buf = buf[e + 2:]
                if len(buf) > 8_000_000:
                    buf = b""
                if self.viewers == 0 and time.time() - self.last_viewer > self.IDLE_STOP:
                    self.stopped = True
            self._kill()
            if self.stopped:
                break
            self.error = explain_ffmpeg_error("\n".join(err_lines)) or "Stream ended"
            self.failed_at = time.time()
            with self.cond:
                self.cond.notify_all()
            if self.error.startswith("Camera rejected"):
                break  # Retrying a wrong password can lock the camera account.
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)
            if not got and self.viewers == 0:
                break
        self.stopped = True
        with self.cond:
            self.cond.notify_all()

    @staticmethod
    def _read_err(proc, lines):
        for line in proc.stderr:
            lines.append(line.decode("utf-8", "replace").strip())
            del lines[:-20]

    def _kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(3)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def stop(self):
        self.stopped = True
        self._kill()

    def frames(self, timeout=20):
        """Generator of JPEG frames for one viewer."""
        self.viewers += 1
        try:
            last = 0
            while True:
                with self.cond:
                    ok = self.cond.wait_for(lambda: self.frame_no != last or self.stopped, timeout)
                    if self.stopped and self.frame_no == last:
                        return
                    if not ok:
                        return
                    last, frame = self.frame_no, self.frame
                yield frame
        finally:
            self.viewers -= 1
            self.last_viewer = time.time()

    def wait_first(self, timeout=15):
        with self.cond:
            self.cond.wait_for(lambda: self.frame is not None or self.stopped or
                               (self.error and self.error != "Stream ended"), timeout)
        return self.frame


class RelayManager:
    def __init__(self):
        self.lock = threading.Lock()
        self.relays = {}

    def get(self, cam, **opts):
        key = (cam["id"], json.dumps(opts, sort_keys=True))
        with self.lock:
            r = self.relays.get(key)
            sig = (cam.get("url"), cam.get("user"), cam.get("password"), cam.get("device"), cam.get("transport"))
            recent_auth_fail = r and r.stopped and r.sig == sig and (r.error or "").startswith("Camera rejected") \
                and time.time() - getattr(r, "failed_at", 0) < 120
            if recent_auth_fail:
                return r
            if r and (r.stopped or r.sig != sig):
                r.stop()
                r = None
            if not r:
                r = Relay(cam, **opts)
                r.sig = sig
                self.relays[key] = r
            return r

    def drop(self, cam_id):
        with self.lock:
            for k in [k for k in self.relays if k[0] == cam_id]:
                self.relays.pop(k).stop()

    def status(self):
        with self.lock:
            return {k[0]: {"viewers": r.viewers, "error": r.error, "running": not r.stopped}
                    for k, r in self.relays.items()}


# ------------------------------------------------------------------ probing

def explain_ffmpeg_error(text):
    t = text or ""
    if re.search(r"401|Unauthorized", t):
        return "Camera rejected the username or password (401 Unauthorized)"
    if re.search(r"404|Not Found", t):
        return "Stream path not found on camera (404). Try another URL pattern."
    if re.search(r"Connection refused", t):
        return "Connection refused: wrong port or the service is disabled on the camera"
    if re.search(r"timed out|Timeout|Operation not permitted", t, re.I):
        return "Timed out connecting to the camera"
    if re.search(r"No route to host|Network is unreachable", t):
        return "Camera is unreachable from this computer"
    if re.search(r"453|Not Enough Bandwidth|session", t, re.I):
        return "Camera refused another session (too many viewers?)"
    lines = [l for l in t.splitlines() if l.strip()]
    return lines[-1][:300] if lines else None


def ffprobe(url, user="", password="", transport="tcp", timeout=10):
    if not FFPROBE:
        return {"ok": False, "error": "ffprobe not installed (install ffmpeg)"}
    full = with_creds(url, user, password)
    cmd = [FFPROBE, "-hide_banner", "-v", "error", "-print_format", "json", "-show_streams"]
    if full.startswith(("rtsp://", "rtsps://")):
        cmd += ["-rtsp_transport", transport, "-timeout", str(int(timeout * 1e6))]
    else:
        cmd += ["-rw_timeout", str(int(timeout * 1e6))]
    cmd.append(full)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "Timed out"}
    if r.returncode != 0:
        return {"ok": False, "error": explain_ffmpeg_error(r.stderr) or "ffprobe failed"}
    try:
        info = json.loads(r.stdout)
    except ValueError:
        info = {}
    vids = [s for s in info.get("streams", []) if s.get("codec_type") == "video"]
    if not vids:
        return {"ok": False, "error": "No video stream found"}
    v = vids[0]
    auds = [s for s in info.get("streams", []) if s.get("codec_type") == "audio"]
    return {"ok": True, "codec": v.get("codec_name"), "width": v.get("width"), "height": v.get("height"),
            "audio": auds[0].get("codec_name") if auds else None}


def probe_http(url, user="", password="", timeout=5):
    """Check whether an HTTP URL serves a JPEG or MJPEG stream."""
    try:
        with http_open(url, user, password, timeout) as r:
            ctype = r.headers.get("Content-Type", "")
            head = r.read(4096)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return {"ok": False, "error": "Camera rejected the username or password (401)"}
        return {"ok": False, "error": f"HTTP {e.code}"}
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}
    if "multipart" in ctype.lower() or (b"--" in head[:200] and b"\xff\xd8" in head):
        return {"ok": True, "type": "mjpeg", "content_type": ctype}
    if ctype.startswith("image/") or head.startswith(b"\xff\xd8"):
        return {"ok": True, "type": "snapshot", "content_type": ctype}
    return {"ok": False, "error": f"Not an image or MJPEG stream (Content-Type: {ctype or 'unknown'})"}


def grab_snapshot(cam, timeout=12):
    """Return JPEG bytes for any camera kind."""
    kind = cam.get("kind")
    if kind in ("snapshot", "mjpeg") and cam.get("url", "").startswith("http"):
        with http_open(cam["url"], cam.get("user"), cam.get("password"), timeout) as r:
            if kind == "snapshot":
                return r.read(20_000_000)
            buf = b""
            while len(buf) < 10_000_000:
                c = r.read(32768)
                if not c:
                    break
                buf += c
                s = buf.find(b"\xff\xd8")
                e = buf.find(b"\xff\xd9", s + 2) if s >= 0 else -1
                if s >= 0 and e > 0:
                    return buf[s:e + 2]
            raise RuntimeError("No frame received")
    if not FFMPEG:
        raise RuntimeError("ffmpeg is not installed")
    cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin", *input_args(cam),
           "-frames:v", "1", "-q:v", "3", "-f", "image2", "-c:v", "mjpeg", "pipe:1"]
    r = subprocess.run(cmd, capture_output=True, timeout=timeout + 10)
    if r.returncode != 0 or not r.stdout:
        raise RuntimeError(explain_ffmpeg_error(r.stderr.decode("utf-8", "replace")) or "Snapshot failed")
    return r.stdout
