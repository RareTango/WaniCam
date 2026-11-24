"""Tests for WaniCam. Run: python -m unittest discover tests

The integration tests start tests/fake_camera.py (needs ffmpeg) and skip
themselves when ffmpeg is not installed.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from wanicam import brands, discovery, onvif, streams  # noqa: E402
from wanicam.store import Store  # noqa: E402

USER, PASSWORD = "admin", "s3cret!pw"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class UnitTests(unittest.TestCase):
    def test_brand_patterns_render(self):
        for key, b in brands.BRANDS.items():
            for kind in ("rtsp", "snapshot", "mjpeg"):
                for url in brands.candidates(key, kind, "10.0.0.5", user="u", password="p@ss", channel=2, stream=1):
                    self.assertNotIn("{", url, f"{key} {kind} left a placeholder")
                    self.assertIn("10.0.0.5", url)

    def test_brand_examples(self):
        self.assertEqual(brands.candidates("hikvision", "rtsp", "h")[0], "rtsp://h:554/Streaming/Channels/101")
        self.assertEqual(brands.candidates("dahua", "rtsp", "h", stream=1)[0],
                         "rtsp://h:554/cam/realmonitor?channel=1&subtype=1")
        self.assertEqual(brands.candidates("reolink", "rtsp", "h")[0], "rtsp://h:554/h264Preview_01_main")
        self.assertEqual(brands.candidates("tapo", "rtsp", "h", stream=1)[0], "rtsp://h:554/stream2")
        self.assertEqual(brands.candidates("axis", "rtsp", "h", user="a b", password="x:y")[0],
                         "rtsp://a%20b:x%3Ay@h:554/axis-media/media.amp?camera=1")

    def test_guess_brand(self):
        self.assertEqual(brands.guess_brand("Server: App-webs/ Hikvision Web"), "hikvision")
        self.assertEqual(brands.guess_brand("Reolink RLC-510A"), "reolink")
        self.assertIsNone(brands.guess_brand("nginx"))

    def test_credentials_helpers(self):
        u = streams.with_creds("rtsp://1.2.3.4:554/live", "ad min", "p@ss:word")
        self.assertEqual(u, "rtsp://ad%20min:p%40ss%3Aword@1.2.3.4:554/live")
        self.assertEqual(streams.strip_creds(u), ("rtsp://1.2.3.4:554/live", "ad min", "p@ss:word"))
        self.assertEqual(streams.with_creds("rtsp://x:y@h/", "a", "b"), "rtsp://x:y@h/")

    def test_mdns_parser(self):
        # A minimal response: PTR _rtsp._tcp.local -> cam._rtsp._tcp.local, plus SRV port 554.
        def name(n):
            return b"".join(bytes([len(p)]) + p.encode() for p in n.split(".")) + b"\0"
        import struct
        ptr_rd = name("cam._rtsp._tcp.local")
        srv_rd = struct.pack("!HHH", 0, 0, 554) + name("cam.local")
        pkt = struct.pack("!HHHHHH", 0, 0x8400, 0, 2, 0, 0)
        pkt += name("_rtsp._tcp.local") + struct.pack("!HHIH", 12, 1, 120, len(ptr_rd)) + ptr_rd
        pkt += name("cam._rtsp._tcp.local") + struct.pack("!HHIH", 33, 1, 120, len(srv_rd)) + srv_rd
        recs = discovery._parse_mdns(pkt)
        self.assertIn(("PTR", "_rtsp._tcp.local", "cam._rtsp._tcp.local"), recs)
        self.assertIn(("SRV", "cam._rtsp._tcp.local", (554, "cam.local")), recs)

    def test_store_keeps_password_on_edit(self):
        d = tempfile.mkdtemp()
        s = Store(os.path.join(d, "c.json"))
        c = s.upsert({"kind": "rtsp", "url": "rtsp://h/", "user": "a", "password": "pw"})
        s.upsert({"id": c["id"], "name": "renamed", "password": ""})
        self.assertEqual(s.get(c["id"])["password"], "pw")
        self.assertNotIn("password", s.list()[0])
        if os.name == "posix":
            self.assertEqual(os.stat(os.path.join(d, "c.json")).st_mode & 0o777, 0o600)

    def test_error_explanations(self):
        self.assertIn("password", streams.explain_ffmpeg_error("method DESCRIBE failed: 401 Unauthorized"))
        self.assertIn("not found", streams.explain_ffmpeg_error("404 Not Found"))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg not installed")
class IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rtsp_port, cls.http_port = free_port(), free_port()
        cls.cam = subprocess.Popen(
            [sys.executable, os.path.join(ROOT, "tests", "fake_camera.py"), "--rtsp-port", str(cls.rtsp_port),
             "--http-port", str(cls.http_port), "--user", USER, "--password", PASSWORD],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        cls.cam.stdout.readline()
        time.sleep(1.5)
        from wanicam import server
        server.Handler.app = server.App(tempfile.mkdtemp(), None, {"127.0.0.1", "localhost"})
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.cam.terminate()

    def post(self, path, body):
        req = urllib.request.Request(self.base + path, json.dumps(body).encode(),
                                     {"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_onvif_lookup(self):
        r = onvif.lookup("127.0.0.1", [f"http://127.0.0.1:{self.http_port}/onvif/device_service"], USER, PASSWORD)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["info"]["Manufacturer"], "HIKVISION")
        self.assertEqual(len(r["streams"]), 2)
        bad = onvif.lookup(None, [f"http://127.0.0.1:{self.http_port}/onvif/device_service"], USER, "nope")
        self.assertFalse(bad["ok"])
        self.assertIn("Not authorized", bad["errors"][0])

    def test_port_scan_finds_camera(self):
        found = discovery.port_scan(["127.0.0.1/32"], ports=[self.rtsp_port, self.http_port, 9])
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["ip"], "127.0.0.1")

    def test_probe_finds_streams(self):
        code, r = self.post("/api/probe", {"host": "127.0.0.1", "user": USER, "password": PASSWORD,
                                           "rtsp_port": self.rtsp_port, "http_port": self.http_port})
        self.assertEqual(code, 200)
        self.assertEqual(r["brand"], "hikvision")
        ok = [x for x in r["rtsp"] if x["ok"]]
        self.assertTrue(ok and ok[0]["source"] == "onvif", r)
        self.assertTrue(any(h["type"] == "snapshot" for h in r["http"]))

    def test_probe_stops_on_wrong_password(self):
        code, r = self.post("/api/probe", {"host": "127.0.0.1", "user": USER, "password": "wrong",
                                           "rtsp_port": self.rtsp_port, "http_port": self.http_port,
                                           "onvif": False})
        self.assertIn("rejected", r.get("auth_error", ""))
        self.assertEqual(r["tried"], 1, "should stop after the first rejected login")

    def test_stream_and_snapshot(self):
        code, cam = self.post("/api/cameras", {
            "kind": "rtsp", "name": "t",
            "url": f"rtsp://{USER}:s3cret%21pw@127.0.0.1:{self.rtsp_port}/Streaming/Channels/101"})
        self.assertEqual(code, 200)
        self.assertNotIn("password", cam)
        with urllib.request.urlopen(f"{self.base}/stream/{cam['id']}.mjpg?fps=5", timeout=30) as r:
            self.assertIn("multipart/x-mixed-replace", r.headers["Content-Type"])
            data = r.read(200_000)
        self.assertIn(b"\xff\xd8", data)
        with urllib.request.urlopen(f"{self.base}/snapshot/{cam['id']}.jpg", timeout=30) as r:
            self.assertTrue(r.read().startswith(b"\xff\xd8"))

    def test_mjpeg_camera(self):
        code, cam = self.post("/api/cameras", {"kind": "mjpeg", "user": USER, "password": PASSWORD,
                                               "url": f"http://127.0.0.1:{self.http_port}/video.mjpg"})
        with urllib.request.urlopen(f"{self.base}/stream/{cam['id']}.mjpg", timeout=30) as r:
            self.assertIn(b"\xff\xd8", r.read(100_000))

    def test_rejects_foreign_host_header(self):
        req = urllib.request.Request(self.base + "/api/cameras", headers={"Host": "evil.example"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(e.exception.code, 403)


if __name__ == "__main__":
    unittest.main()
