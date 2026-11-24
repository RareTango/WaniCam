package com.wanicam.core

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrandsTest {
    @Test fun patternsRenderWithoutPlaceholders() {
        for (b in Brands.all) for (kind in listOf("rtsp", "snapshot", "mjpeg")) {
            for (u in Brands.candidates(b.id, kind, "10.0.0.5", "u", "p@ss", channel = 2, stream = 1)) {
                assertFalse(u.contains('{'), "${b.id} $kind: $u")
                assertTrue(u.contains("10.0.0.5"))
            }
        }
    }

    @Test fun knownFormats() {
        assertEquals("rtsp://h:554/Streaming/Channels/101", Brands.candidates("hikvision", "rtsp", "h")[0])
        assertEquals("rtsp://h:554/cam/realmonitor?channel=1&subtype=1", Brands.candidates("dahua", "rtsp", "h", stream = 1)[0])
        assertEquals("rtsp://h:554/h264Preview_01_main", Brands.candidates("reolink", "rtsp", "h")[0])
        assertEquals("rtsp://h:554/stream2", Brands.candidates("tapo", "rtsp", "h", stream = 1)[0])
        assertEquals("rtsp://a%20b:x%3Ay@h:554/axis-media/media.amp?camera=1",
            Brands.candidates("axis", "rtsp", "h", "a b", "x:y")[0])
    }

    @Test fun guessesBrand() {
        assertEquals("hikvision", Brands.guess("Server: App-webs/ Hikvision Web"))
        assertEquals("reolink", Brands.guess("Reolink RLC-510A"))
        assertNull(Brands.guess("nginx"))
    }
}

class NetTest {
    @Test fun credentials() {
        val u = Net.withCreds("rtsp://1.2.3.4:554/live", "ad min", "p@ss:word")
        assertEquals("rtsp://ad%20min:p%40ss%3Aword@1.2.3.4:554/live", u)
        assertEquals(Net.Stripped("rtsp://1.2.3.4:554/live", "ad min", "p@ss:word"), Net.stripCreds(u))
        assertEquals("rtsp://x:y@h/", Net.withCreds("rtsp://x:y@h/", "a", "b"))
        assertEquals("rtsp://192.168.1.9:554/a?b=1", Net.replaceHost("rtsp://10.0.0.1:554/a?b=1", "192.168.1.9"))
    }

    @Test fun digestMatchesRfc2617Example() {
        // The worked example from RFC 2617 section 3.5.
        val ch = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
        val h = Digest.authorization(listOf(ch), "GET", "/dir/index.html", "Mufasa", "Circle Of Life")!!
        val f = Digest.parseChallenge(h)
        assertEquals("auth", f["qop"])
        // Recompute with the cnonce we generated.
        fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val ha1 = md5("Mufasa:testrealm@host.com:Circle Of Life")
        val ha2 = md5("GET:/dir/index.html")
        assertEquals(md5("$ha1:dcd98b7102dd2f0e8b11d0f600bfb0c093:${f["nc"]}:${f["cnonce"]}:auth:$ha2"), f["response"])
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", f["opaque"])
    }

    @Test fun basicFallback() {
        assertTrue(Digest.authorization(listOf("Basic realm=\"cam\""), "GET", "/", "a", "b")!!.startsWith("Basic "))
    }
}

class ParsersTest {
    @Test fun sdp() {
        val r = RtspProbe.parseSdp("v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n")
        assertTrue(r.ok); assertEquals("h264", r.codec); assertEquals("mpeg4-generic", r.audio)
        assertEquals("jpeg", RtspProbe.parseSdp("m=video 0 RTP/AVP 26\r\n").codec)
        assertFalse(RtspProbe.parseSdp("m=audio 0 RTP/AVP 0\r\n").ok)
    }

    @Test fun mdns() {
        fun name(n: String) = n.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + listOf(0.toByte())
        val ptr = name("cam._rtsp._tcp.local")
        val srv = listOf<Byte>(0, 0, 0, 0, 0x02, 0x2a) + name("cam.local")
        val bb = ByteBuffer.allocate(512)
        bb.putShort(0); bb.putShort(0x8400.toShort()); bb.putShort(0); bb.putShort(2); bb.putShort(0); bb.putShort(0)
        bb.put(name("_rtsp._tcp.local").toByteArray()); bb.putShort(12); bb.putShort(1); bb.putInt(120); bb.putShort(ptr.size.toShort()); bb.put(ptr.toByteArray())
        bb.put(name("cam._rtsp._tcp.local").toByteArray()); bb.putShort(33); bb.putShort(1); bb.putInt(120); bb.putShort(srv.size.toShort()); bb.put(srv.toByteArray())
        val recs = Discovery.parseMdns(bb.array().copyOf(bb.position()))
        val p = recs.filterIsInstance<Discovery.Rec.Ptr>().single()
        assertEquals("cam._rtsp._tcp.local", p.target)
        assertEquals(554, recs.filterIsInstance<Discovery.Rec.Srv>().single().port)
    }

    @Test fun cidr() {
        assertEquals(254, Discovery.hosts("192.168.1.0/24").size)
        assertEquals("192.168.1.1", Discovery.hosts("192.168.1.77/24").first())
        assertEquals(listOf("10.0.0.5"), Discovery.hosts("10.0.0.5/32"))
        assertTrue(Discovery.isPrivate("192.168.0.0/24"))
        assertFalse(Discovery.isPrivate("8.8.8.0/24"))
    }
}

/** Runs against tests/fake_camera.py (needs python3 and ffmpeg; skipped otherwise). */
class FakeCameraTest {
    companion object {
        const val USER = "admin"
        const val PASSWORD = "s3cret!pw"
        var proc: Process? = null
        var rtspPort = 0
        var httpPort = 0

        private fun free() = ServerSocket(0).use { it.localPort }
        private fun has(cmd: String) = try {
            ProcessBuilder(cmd, "-version").redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) { false }

        @BeforeClass @JvmStatic fun start() {
            val root = System.getProperty("wanicam.repoRoot") ?: return
            val script = File(root, "tests/fake_camera.py")
            if (!script.exists() || !has("ffmpeg")) return
            rtspPort = free(); httpPort = free()
            proc = ProcessBuilder("python3", script.path, "--rtsp-port", "$rtspPort", "--http-port", "$httpPort",
                "--user", USER, "--password", PASSWORD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            proc!!.inputStream.bufferedReader().readLine()
            Thread.sleep(1500)
        }

        @AfterClass @JvmStatic fun stop() { proc?.destroy() }
    }

    private fun need() = assumeTrue("fake camera not available", proc?.isAlive == true)

    @Test fun rtspCheck() {
        need()
        val ok = RtspProbe.check("rtsp://127.0.0.1:$rtspPort/Streaming/Channels/101", USER, PASSWORD)
        assertTrue(ok.ok, ok.error); assertEquals("h264", ok.codec)
        val bad = RtspProbe.check("rtsp://127.0.0.1:$rtspPort/Streaming/Channels/101", USER, "nope")
        assertTrue(bad.authFailed)
        val missing = RtspProbe.check("rtsp://127.0.0.1:$rtspPort/nothing", USER, PASSWORD)
        assertFalse(missing.ok); assertTrue(missing.error!!.contains("404"))
    }

    @Test fun onvif() {
        need()
        val r = Onvif.lookup(null, listOf("http://127.0.0.1:$httpPort/onvif/device_service"), USER, PASSWORD)
        assertTrue(r.ok, r.errors.toString())
        assertEquals("HIKVISION", r.manufacturer)
        assertEquals(2, r.streams.size)
        assertNotNull(r.streams[0].rtsp)
        val bad = Onvif.lookup(null, listOf("http://127.0.0.1:$httpPort/onvif/device_service"), USER, "nope")
        assertFalse(bad.ok); assertTrue(bad.authFailed)
    }

    @Test fun httpChecks() {
        need()
        assertEquals("snapshot", Prober.checkHttp("http://127.0.0.1:$httpPort/ISAPI/Streaming/channels/101/picture", USER, PASSWORD).type)
        assertEquals("mjpeg", Prober.checkHttp("http://127.0.0.1:$httpPort/video.mjpg", USER, PASSWORD).type)
        assertTrue(Prober.checkHttp("http://127.0.0.1:$httpPort/video.mjpg", USER, "bad").authFailed)
    }

    @Test fun probeFindsStreams() = runBlocking {
        need()
        val r = Prober.probe("127.0.0.1", "auto", USER, PASSWORD, rtspPort, httpPort)
        assertEquals("hikvision", r.brand)
        val rtsp = r.streams.filter { it.kind == "rtsp" }
        assertTrue(rtsp.isNotEmpty() && rtsp[0].source == "onvif", r.toString())
        assertTrue(r.streams.any { it.kind == "snapshot" })
        assertNull(r.authError)
    }

    @Test fun probeStopsOnWrongPassword() = runBlocking {
        need()
        val r = Prober.probe("127.0.0.1", "auto", USER, "wrong", rtspPort, httpPort, useOnvif = false)
        assertNotNull(r.authError)
        assertEquals(1, r.tried)
    }

    @Test fun portScan() = runBlocking {
        need()
        val found = Discovery.portScan(listOf("127.0.0.1/32"), ports = listOf(rtspPort, httpPort, 9))
        assertEquals(1, found.size)
        assertEquals(rtspPort, found[0].rtspPort)
    }
}
