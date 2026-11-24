package com.wanicam.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

data class StreamCheck(
    val ok: Boolean,
    val codec: String? = null,
    val audio: String? = null,
    val error: String? = null,
    val authFailed: Boolean = false,
)

/**
 * Checks an RTSP URL by sending DESCRIBE (with the user's credentials if the camera asks) and
 * reading the SDP. This confirms the path exists and which codec it uses without opening the
 * video, and makes at most one authenticated attempt.
 */
object RtspProbe {
    private class Reply(val code: Int, val headers: Map<String, List<String>>, val body: String)

    fun check(url: String, user: String = "", password: String = "", timeoutMs: Int = 5000): StreamCheck {
        val stripped = Net.stripCreds(url)
        val u = user.ifEmpty { stripped.user }
        val p = password.ifEmpty { stripped.password }
        val clean = stripped.url
        if (clean.startsWith("rtsps://", true)) {
            return StreamCheck(false, error = "RTSPS (encrypted RTSP) isn't supported by the Android player. " +
                "Use the plain rtsp:// address (UniFi Protect: port 7447).")
        }
        val uri = try { URI(clean) } catch (e: Exception) { return StreamCheck(false, error = "Invalid URL") }
        val host = uri.host ?: return StreamCheck(false, error = "Invalid URL")
        val port = if (uri.port > 0) uri.port else 554
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun send(cseq: Int, auth: String?): Reply {
                    val req = StringBuilder("DESCRIBE $clean RTSP/1.0\r\nCSeq: $cseq\r\n")
                        .append("Accept: application/sdp\r\nUser-Agent: WaniCam\r\n")
                    if (auth != null) req.append("Authorization: $auth\r\n")
                    req.append("\r\n")
                    out.write(req.toString().toByteArray())
                    out.flush()
                    return read(input)
                }
                var r = send(2, null)
                if (r.code == 401) {
                    if (u.isEmpty()) return StreamCheck(false, error = "The camera needs a username and password",
                        authFailed = true)
                    val auth = Digest.authorization(r.headers["www-authenticate"] ?: emptyList(), "DESCRIBE",
                        clean, u, p) ?: return StreamCheck(false, error = "Unsupported login method")
                    r = send(3, auth)
                }
                when {
                    r.code == 200 -> parseSdp(r.body)
                    r.code == 401 || r.code == 403 -> StreamCheck(false, authFailed = true,
                        error = "Camera rejected the username or password (${r.code})")
                    r.code == 404 -> StreamCheck(false, error = "Stream path not found on camera (404)")
                    r.code == 453 -> StreamCheck(false, error = "Camera refused another session (too many viewers?)")
                    else -> StreamCheck(false, error = "Camera answered RTSP ${r.code}")
                }
            }
        } catch (e: SocketTimeoutException) {
            StreamCheck(false, error = "Timed out connecting to the camera")
        } catch (e: java.net.ConnectException) {
            StreamCheck(false, error = "Connection refused: wrong port or RTSP is turned off on the camera")
        } catch (e: Exception) {
            StreamCheck(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > 8192) break
        }
        return buf.toString("UTF-8")
    }

    private fun read(input: InputStream): Reply {
        var status = readLine(input)
        // Skip any interleaved binary or blank lines before the status line.
        var guard = 0
        while (!status.startsWith("RTSP/") && guard++ < 20) status = readLine(input)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("Not an RTSP server")
        val headers = mutableMapOf<String, MutableList<String>>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers.getOrPut(line.substring(0, i).trim().lowercase()) { mutableListOf() }
                .add(line.substring(i + 1).trim())
        }
        val len = headers["content-length"]?.firstOrNull()?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceAtMost(65536))
        var off = 0
        while (off < body.size) {
            val n = input.read(body, off, body.size - off)
            if (n < 0) break
            off += n
        }
        return Reply(code, headers, String(body, 0, off))
    }

    fun parseSdp(sdp: String): StreamCheck {
        var media = ""
        var video: String? = null
        var audio: String? = null
        for (line in sdp.lines()) {
            if (line.startsWith("m=")) media = line.substring(2).substringBefore(' ')
            val m = Regex("^a=rtpmap:\\d+ ([\\w.-]+)/").find(line) ?: continue
            if (media == "video" && video == null) video = m.groupValues[1].lowercase()
            if (media == "audio" && audio == null) audio = m.groupValues[1].lowercase()
        }
        if (video == null && sdp.lines().any { it.startsWith("m=video") }) {
            // Static payload type 26 is JPEG.
            video = if (sdp.contains(Regex("m=video \\d+ \\S+ 26\\b"))) "jpeg" else "unknown"
        }
        return if (video == null) StreamCheck(false, error = "No video in this stream")
        else StreamCheck(true, codec = video, audio = audio)
    }
}
