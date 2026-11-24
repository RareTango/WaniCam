"""A simulated IP camera for testing WaniCam without real hardware.

Serves, on 127.0.0.1:
  * RTSP (digest auth) at rtsp://HOST:RTSP_PORT/Streaming/Channels/101 and /102
  * ONVIF device + media service at http://HOST:HTTP_PORT/onvif/device_service
  * JPEG snapshot (digest auth) at /ISAPI/Streaming/channels/101/picture
  * MJPEG stream (basic auth) at /video.mjpg
  * a WS-Discovery responder on 239.255.255.250:3702

Video comes from ffmpeg's built-in test pattern. Run:
  python tests/fake_camera.py --user admin --password secret
"""
import argparse
import base64
import hashlib
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from socketserver import ThreadingTCPServer, StreamRequestHandler

PATHS = ("/Streaming/Channels/101", "/Streaming/Channels/102")
REALM = "IP Camera(FAKE)"


class Media:
    """Runs ffmpeg once: H.264 RTP to a local UDP port, and JPEG frames to memory."""

    def __init__(self):
        self.rtp_port = _free_udp_port()
        self.sdp_path = os.path.join(tempfile.mkdtemp(), "cam.sdp")
        self.jpeg = None
        self.subscribers = set()
        self.lock = threading.Lock()
        self.rtp = subprocess.Popen(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi",
             "-i", "testsrc2=size=1280x720:rate=15", "-c:v", "libx264", "-preset", "ultrafast",
             "-tune", "zerolatency", "-g", "15", "-bsf:v", "dump_extra", "-f", "rtp",
             "-sdp_file", self.sdp_path, f"rtp://127.0.0.1:{self.rtp_port}?pkt_size=1200"])
        self.mj = subprocess.Popen(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi",
             "-i", "testsrc=size=640x360:rate=5", "-q:v", "5", "-f", "mjpeg", "pipe:1"],
            stdout=subprocess.PIPE)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", self.rtp_port))
        threading.Thread(target=self._fanout, daemon=True).start()
        threading.Thread(target=self._jpegs, daemon=True).start()
        for _ in range(100):
            if os.path.exists(self.sdp_path) and os.path.getsize(self.sdp_path) > 0:
                break
            time.sleep(0.1)

    def sdp(self, host):
        s = open(self.sdp_path).read()
        s = s.replace("c=IN IP4 127.0.0.1", "c=IN IP4 0.0.0.0")
        s = re.sub(r"m=video \d+", "m=video 0", s)
        return s.replace("\n", "\r\n").replace("\r\r\n", "\r\n") + "a=control:track1\r\n"

    def _fanout(self):
        while True:
            pkt = self.sock.recv(65536)
            frame = b"$\x00" + struct.pack("!H", len(pkt)) + pkt
            with self.lock:
                subs = list(self.subscribers)
            for w in subs:
                try:
                    w.write(frame)
                    w.flush()
                except Exception:
                    with self.lock:
                        self.subscribers.discard(w)

    def _jpegs(self):
        buf = b""
        while True:
            c = self.mj.stdout.read(32768)
            if not c:
                return
            buf += c
            while True:
                s = buf.find(b"\xff\xd8")
                e = buf.find(b"\xff\xd9", s + 2)
                if s < 0 or e < 0:
                    break
                self.jpeg = buf[s:e + 2]
                buf = buf[e + 2:]

    def stop(self):
        for p in (self.rtp, self.mj):
            p.terminate()


def _free_udp_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def _md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def check_digest(header, method, user, password):
    if not header or not header.startswith("Digest "):
        return False
    f = dict(re.findall(r'(\w+)="?([^",]*)"?', header[7:]))
    if f.get("username") != user:
        return False
    ha1 = _md5(f"{user}:{f.get('realm')}:{password}")
    ha2 = _md5(f"{method}:{f.get('uri')}")
    if f.get("qop"):
        expect = _md5(f"{ha1}:{f['nonce']}:{f['nc']}:{f['cnonce']}:{f['qop']}:{ha2}")
    else:
        expect = _md5(f"{ha1}:{f.get('nonce')}:{ha2}")
    return expect == f.get("response")


# ------------------------------------------------------------------ RTSP

def make_rtsp_handler(media, user, password):
    class RTSP(StreamRequestHandler):
        def handle(self):
            session = uuid.uuid4().hex[:8]
            while True:
                line = self.rfile.readline()
                if not line:
                    break
                if not line.strip():
                    continue
                req = line.decode().strip()
                hdr = {}
                while True:
                    l = self.rfile.readline().decode().strip()
                    if not l:
                        break
                    k, _, v = l.partition(":")
                    hdr[k.strip().lower()] = v.strip()
                method, url, _ = req.split(" ", 2)
                cseq = hdr.get("cseq", "0")
                path = re.sub(r"^rtsps?://[^/]+", "", url).split("?")[0]
                base = re.sub(r"/track1$", "", path).rstrip("/") or "/"

                def reply(code, text, extra="", body=""):
                    msg = f"RTSP/1.0 {code} {text}\r\nCSeq: {cseq}\r\nServer: Hikvision-Webs-Fake\r\n{extra}"
                    if body:
                        msg += f"Content-Type: application/sdp\r\nContent-Length: {len(body)}\r\n"
                    self.wfile.write((msg + "\r\n" + body).encode())
                    self.wfile.flush()

                if method == "OPTIONS":
                    reply(200, "OK", "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
                    continue
                if not check_digest(hdr.get("authorization"), method, user, password):
                    reply(401, "Unauthorized", f'WWW-Authenticate: Digest realm="{REALM}", nonce="{uuid.uuid4().hex}"\r\n')
                    continue
                if base not in PATHS:
                    reply(404, "Not Found")
                    continue
                if method == "DESCRIBE":
                    reply(200, "OK", f"Content-Base: {url.rstrip('/')}/\r\n", media.sdp("127.0.0.1"))
                elif method == "SETUP":
                    if "TCP" not in hdr.get("transport", ""):
                        reply(461, "Unsupported Transport")
                        continue
                    reply(200, "OK", f"Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: {session};timeout=60\r\n")
                elif method == "PLAY":
                    reply(200, "OK", f"Session: {session}\r\nRange: npt=0.000-\r\n")
                    with media.lock:
                        media.subscribers.add(self.wfile)
                elif method == "TEARDOWN":
                    reply(200, "OK", f"Session: {session}\r\n")
                    break
                else:
                    reply(405, "Method Not Allowed")
            with media.lock:
                media.subscribers.discard(self.wfile)
    return RTSP


# ------------------------------------------------------------------ ONVIF / HTTP

def soap(body):
    return ('<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
            'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" '
            'xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>' + body + '</s:Body></s:Envelope>')


def ws_security_ok(xml, user, password):
    u = re.search(r"<(?:\w+:)?Username>([^<]*)<", xml)
    pw = re.search(r"<(?:\w+:)?Password[^>]*>([^<]*)<", xml)
    nonce = re.search(r"<(?:\w+:)?Nonce[^>]*>([^<]*)<", xml)
    created = re.search(r"<(?:\w+:)?Created[^>]*>([^<]*)<", xml)
    if not (u and pw and nonce and created) or u.group(1) != user:
        return False
    d = base64.b64encode(hashlib.sha1(base64.b64decode(nonce.group(1)) + created.group(1).encode()
                                      + password.encode()).digest()).decode()
    return d == pw.group(1)


def make_http_handler(media, user, password, rtsp_port):
    class HTTP(BaseHTTPRequestHandler):
        server_version = "App-webs/"
        sys_version = ""

        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype="application/soap+xml", extra=None):
            b = body.encode() if isinstance(body, str) else body
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(b)))
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(b)

        def _need_digest(self):
            if check_digest(self.headers.get("Authorization"), self.command, user, password):
                return False
            self._send(401, "Unauthorized", "text/plain",
                       {"WWW-Authenticate": f'Digest realm="{REALM}", nonce="{uuid.uuid4().hex}", qop="auth"'})
            return True

        def do_GET(self):
            host = self.headers.get("Host", "127.0.0.1").split(":")[0]
            if self.path == "/":
                return self._send(401, "<html><title>Hikvision Web</title></html>", "text/html",
                                  {"WWW-Authenticate": f'Digest realm="{REALM}", nonce="x"'})
            if self.path.startswith("/ISAPI/Streaming/channels/101/picture"):
                if self._need_digest():
                    return
                return self._send(200, media.jpeg or b"", "image/jpeg")
            if self.path.startswith("/video.mjpg"):
                auth = self.headers.get("Authorization", "")
                if auth != "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode():
                    return self._send(401, "no", "text/plain", {"WWW-Authenticate": 'Basic realm="cam"'})
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=myboundary")
                self.end_headers()
                try:
                    while True:
                        j = media.jpeg or b""
                        self.wfile.write(b"--myboundary\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                         + str(len(j)).encode() + b"\r\n\r\n" + j + b"\r\n")
                        time.sleep(0.2)
                except Exception:
                    return
            self._send(404, "not found", "text/plain")

        def do_POST(self):
            host = self.headers.get("Host", "127.0.0.1").split(":")[0]
            xml = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
            if not self.path.startswith("/onvif/"):
                return self._send(404, "")
            if "GetSystemDateAndTime" in xml:
                t = time.gmtime()
                return self._send(200, soap(
                    "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime>"
                    f"<tt:Time><tt:Hour>{t.tm_hour}</tt:Hour><tt:Minute>{t.tm_min}</tt:Minute><tt:Second>{t.tm_sec}</tt:Second></tt:Time>"
                    f"<tt:Date><tt:Year>{t.tm_year}</tt:Year><tt:Month>{t.tm_mon}</tt:Month><tt:Day>{t.tm_mday}</tt:Day></tt:Date>"
                    "</tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"))
            if not ws_security_ok(xml, user, password):
                return self._send(400, soap(
                    "<s:Fault><s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:NotAuthorized</s:Value>"
                    "</s:Subcode></s:Code><s:Reason><s:Text>Sender not Authorized</s:Text></s:Reason></s:Fault>"))
            me = f"http://{host}:{self.server.server_address[1]}/onvif/device_service"
            if "GetServices" in xml:
                return self._send(200, soap(
                    "<tds:GetServicesResponse><tds:Service><tds:Namespace>http://www.onvif.org/ver10/device/wsdl"
                    f"</tds:Namespace><tds:XAddr>{me}</tds:XAddr></tds:Service><tds:Service><tds:Namespace>"
                    "http://www.onvif.org/ver10/media/wsdl</tds:Namespace>"
                    f"<tds:XAddr>http://10.99.99.99/onvif/Media</tds:XAddr></tds:Service></tds:GetServicesResponse>"))
            if "GetDeviceInformation" in xml:
                return self._send(200, soap(
                    "<tds:GetDeviceInformationResponse><tds:Manufacturer>HIKVISION</tds:Manufacturer>"
                    "<tds:Model>DS-2CD2043G2-I</tds:Model><tds:FirmwareVersion>V5.7.3</tds:FirmwareVersion>"
                    "<tds:SerialNumber>FAKE0001</tds:SerialNumber></tds:GetDeviceInformationResponse>"))
            if "GetProfiles" in xml:
                prof = ""
                for tok, name, w, h in (("Profile_1", "mainStream", 1280, 720), ("Profile_2", "subStream", 640, 360)):
                    prof += (f'<trt:Profiles token="{tok}" fixed="true"><tt:Name>{name}</tt:Name>'
                             f'<tt:VideoEncoderConfiguration token="V{tok}"><tt:Name>v</tt:Name><tt:Encoding>H264</tt:Encoding>'
                             f'<tt:Resolution><tt:Width>{w}</tt:Width><tt:Height>{h}</tt:Height></tt:Resolution>'
                             '</tt:VideoEncoderConfiguration></trt:Profiles>')
                return self._send(200, soap(f"<trt:GetProfilesResponse>{prof}</trt:GetProfilesResponse>"))
            if "GetStreamUri" in xml:
                ch = "102" if "Profile_2" in xml else "101"
                # Report an internal address, as some cameras do behind NAT.
                return self._send(200, soap(
                    f"<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://10.99.99.99:{rtsp_port}/Streaming/Channels/{ch}"
                    "?transportmode=unicast</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"))
            if "GetSnapshotUri" in xml:
                return self._send(200, soap(
                    f"<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>http://10.99.99.99:{self.server.server_address[1]}"
                    "/ISAPI/Streaming/channels/101/picture</tt:Uri></trt:MediaUri></trt:GetSnapshotUriResponse>"))
            return self._send(400, soap("<s:Fault><s:Reason><s:Text>Unsupported</s:Text></s:Reason></s:Fault>"))
    return HTTP


def wsd_responder(http_port):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", 3702))
        mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton("0.0.0.0"))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    except OSError as e:
        print("WS-Discovery responder disabled:", e, file=sys.stderr)
        return
    while True:
        data, addr = s.recvfrom(65535)
        if b"Probe" not in data:
            continue
        resp = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
                'xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><s:Body><d:ProbeMatches><d:ProbeMatch>'
                '<d:Types>dn:NetworkVideoTransmitter</d:Types>'
                '<d:Scopes>onvif://www.onvif.org/name/FakeCam onvif://www.onvif.org/hardware/DS-2CD2043G2-I '
                'onvif://www.onvif.org/manufacturer/HIKVISION</d:Scopes>'
                f'<d:XAddrs>http://127.0.0.1:{http_port}/onvif/device_service</d:XAddrs>'
                '</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>')
        s.sendto(resp.encode(), addr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--rtsp-port", type=int, default=8554)
    ap.add_argument("--http-port", type=int, default=8080)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="secret")
    a = ap.parse_args()
    media = Media()
    ThreadingTCPServer.allow_reuse_address = True
    ThreadingTCPServer.daemon_threads = True
    rtsp = ThreadingTCPServer((a.host, a.rtsp_port), make_rtsp_handler(media, a.user, a.password))
    http = ThreadingHTTPServer((a.host, a.http_port), make_http_handler(media, a.user, a.password, a.rtsp_port))
    http.daemon_threads = True
    for target in (rtsp.serve_forever, http.serve_forever, lambda: wsd_responder(a.http_port)):
        threading.Thread(target=target, daemon=True).start()
    print(f"Fake camera: rtsp://{a.host}:{a.rtsp_port}{PATHS[0]}  onvif http://{a.host}:{a.http_port}/onvif/device_service",
          flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        media.stop()


if __name__ == "__main__":
    main()
