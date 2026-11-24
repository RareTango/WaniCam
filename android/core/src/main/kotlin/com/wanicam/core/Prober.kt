package com.wanicam.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/** One stream address that was checked against the camera. */
data class FoundStream(
    val url: String,
    val label: String,
    val kind: String, // rtsp | mjpeg | snapshot
    val source: String, // onvif | pattern
    val codec: String? = null,
    val resolution: String? = null,
)

data class ProbeResult(
    val host: String,
    val brand: String,
    val onvif: OnvifResult?,
    val streams: List<FoundStream>,
    val tried: Int,
    val authError: String?,
)

data class HttpCheck(val ok: Boolean, val type: String? = null, val error: String? = null, val authFailed: Boolean = false)

object Prober {
    /** Checks whether an HTTP URL serves a JPEG snapshot or an MJPEG stream. */
    fun checkHttp(url: String, user: String, password: String, timeoutSec: Long = 5): HttpCheck {
        val s = Net.stripCreds(url)
        val client = Net.client(user.ifEmpty { s.user }, password.ifEmpty { s.password }, timeoutSec)
        return try {
            client.newCall(Request.Builder().url(s.url).header("User-Agent", "WaniCam").build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) {
                    return HttpCheck(false, error = "Camera rejected the username or password (${r.code})", authFailed = true)
                }
                if (!r.isSuccessful) return HttpCheck(false, error = "HTTP ${r.code}")
                val ctype = r.header("Content-Type") ?: ""
                val head = r.body?.source()?.let { src ->
                    src.request(4096)
                    src.buffer.snapshot().toByteArray().take(4096).toByteArray()
                } ?: ByteArray(0)
                val isJpeg = head.size > 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
                when {
                    ctype.contains("multipart", true) -> HttpCheck(true, "mjpeg")
                    ctype.startsWith("image/") || isJpeg -> HttpCheck(true, "snapshot")
                    else -> HttpCheck(false, error = "Not an image or MJPEG stream (${ctype.ifEmpty { "unknown type" }})")
                }
            }
        } catch (e: Exception) {
            HttpCheck(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Finds working stream URLs for a camera using the credentials given. Asks ONVIF first, then tries
     * the brand's URL formats. Stops at the first rejected login so the camera doesn't lock the account.
     */
    suspend fun probe(host: String, brandIn: String = "auto", user: String = "", password: String = "",
                      rtspPort: Int? = null, httpPort: Int? = null, channel: Int = 1,
                      xaddrs: List<String> = emptyList(), useOnvif: Boolean = true,
                      progress: (String) -> Unit = {}): ProbeResult = withContext(Dispatchers.IO) {
        var brand = brandIn
        var tried = 0
        var authError: String? = null
        val found = mutableListOf<FoundStream>()
        val rtspJobs = mutableListOf<Pair<String, FoundStream>>()
        val httpJobs = mutableListOf<FoundStream>()
        val seen = mutableSetOf<String>()

        var onvif: OnvifResult? = null
        if (useOnvif) {
            progress("Asking the camera over ONVIF…")
            onvif = Onvif.lookup(host, xaddrs, user, password, listOfNotNull(httpPort))
            if (onvif.ok) {
                val info = listOfNotNull(onvif.manufacturer, onvif.model).joinToString(" ")
                if (brand == "auto" || brand.isEmpty()) Brands.guess(info)?.let { brand = it }
                for (s in onvif.streams) {
                    s.rtsp?.let {
                        val uri = Net.replaceHost(Net.stripCreds(it).url, host)
                        if (seen.add(uri)) {
                            seen += uri.substringBefore("?transportmode")
                            rtspJobs += uri to FoundStream(uri, "ONVIF: ${s.profile}", "rtsp", "onvif", s.encoding?.lowercase(), s.resolution)
                        }
                    }
                    s.snapshot?.let {
                        val u = Net.replaceHost(it, host)
                        if (seen.add(u)) httpJobs += FoundStream(u, "ONVIF snapshot", "snapshot", "onvif")
                    }
                }
            }
        }
        if (brand == "auto" || brand.isEmpty()) brand = "generic"
        val order = (if (brand != "generic") listOf(brand) else emptyList()) + "generic"
        for (b in order) {
            val label = Brands.get(b).label
            for (stream in 0..1) {
                for (u in Brands.candidates(b, "rtsp", host, port = rtspPort, httpPort = httpPort, channel = channel, stream = stream)) {
                    if (seen.add(u)) rtspJobs += u to FoundStream(u, "$label (${if (stream == 0) "main" else "sub"})", "rtsp", "pattern")
                }
            }
            for (kind in listOf("mjpeg", "snapshot")) {
                for (u in Brands.candidates(b, kind, host, user, password, rtspPort, httpPort, channel)) {
                    if (seen.add(u)) httpJobs += FoundStream(u, "$label $kind", kind, "pattern")
                }
            }
        }
        val root = "rtsp://$host:${rtspPort ?: 554}/"
        if (seen.add(root)) rtspJobs += root to FoundStream(root, "Root path", "rtsp", "pattern")

        // Small batches: cameras cap RTSP sessions. The first check runs alone so a wrong password
        // costs one attempt.
        var i = 0
        while (i < rtspJobs.size && authError == null && found.count { it.kind == "rtsp" } < 4) {
            val batch = rtspJobs.subList(i, minOf(rtspJobs.size, i + if (i == 0) 1 else 3)).toList()
            i += batch.size
            progress("Checking stream addresses ($i of ${rtspJobs.size})…")
            val results = coroutineScope { batch.map { (u, _) -> async { RtspProbe.check(u, user, password) } }.awaitAll() }
            for ((job, r) in batch.zip(results)) {
                tried++
                if (r.ok) found += job.second.copy(codec = r.codec ?: job.second.codec)
                if (r.authFailed) authError = r.error
            }
        }
        var j = 0
        while (j < httpJobs.size && authError == null) {
            val batch = httpJobs.subList(j, minOf(httpJobs.size, j + 3)).toList()
            j += batch.size
            progress("Checking web snapshot and MJPEG addresses…")
            val results = coroutineScope { batch.map { f -> async { checkHttp(f.url, user, password, 4) } }.awaitAll() }
            for ((f, r) in batch.zip(results)) {
                tried++
                if (r.ok) found += f.copy(kind = r.type ?: f.kind)
                if (r.authFailed) authError = r.error
            }
        }
        ProbeResult(host, brand, onvif, found, tried, authError)
    }
}
