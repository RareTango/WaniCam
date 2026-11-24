package com.wanicam.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

class OnvifException(message: String, val authFailed: Boolean = false) : Exception(message)

data class OnvifStream(
    val profile: String,
    val token: String,
    val encoding: String? = null,
    val resolution: String? = null,
    val rtsp: String? = null,
    val snapshot: String? = null,
)

data class OnvifResult(
    val ok: Boolean,
    val xaddr: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val streams: List<OnvifStream> = emptyList(),
    val errors: List<String> = emptyList(),
    val authFailed: Boolean = false,
)

/** Minimal ONVIF Profile S client: device info, profiles, stream and snapshot URIs. */
class Onvif(private val deviceUrl: String, private val user: String, private val password: String) {
    private var offsetMs = 0L
    private var mediaUrl: String? = null
    private var media2Url: String? = null
    private val http = Net.client(user, password)

    companion object {
        const val NS_DEV = "http://www.onvif.org/ver10/device/wsdl"
        const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        const val NS_MEDIA2 = "http://www.onvif.org/ver20/media/wsdl"
        const val NS_SCHEMA = "http://www.onvif.org/ver10/schema"
        private val rnd = SecureRandom()
        private val SOAP = "application/soap+xml; charset=utf-8".toMediaType()

        fun defaultXaddrs(host: String, ports: List<Int>) =
            ports.map { "http://$host:$it/onvif/device_service" }

        /** Tries each device-service address until one answers. */
        fun lookup(host: String?, xaddrs: List<String>, user: String, password: String,
                   extraPorts: List<Int> = emptyList()): OnvifResult {
            val addrs = xaddrs.toMutableList()
            if (host != null) {
                val ports = (extraPorts + listOf(80, 8899, 2020, 8000, 8080)).distinct()
                defaultXaddrs(host, ports).forEach { if (it !in addrs) addrs += it }
            }
            val errors = mutableListOf<String>()
            for (xa in addrs) {
                try {
                    val cli = Onvif(xa, user, password)
                    val streams = cli.streams()
                    val info = try { cli.deviceInfo() } catch (e: OnvifException) { emptyMap() }
                    return OnvifResult(true, xa, info["Manufacturer"], info["Model"], info["FirmwareVersion"], streams)
                } catch (e: OnvifException) {
                    errors += "$xa: ${e.message}"
                    // Right endpoint, wrong credentials: don't keep trying other ports.
                    if (e.authFailed) return OnvifResult(false, errors = errors, authFailed = true)
                }
            }
            return OnvifResult(false, errors = errors)
        }

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun parse(xml: ByteArray): Element {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            // No external entities: camera responses are untrusted.
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            f.isExpandEntityReferences = false
            return f.newDocumentBuilder().parse(xml.inputStream()).documentElement
        }

        fun all(el: Node, local: String): List<Element> {
            val out = mutableListOf<Element>()
            fun walk(n: Node) {
                val kids = n.childNodes
                for (i in 0 until kids.length) {
                    val k = kids.item(i)
                    if (k is Element) {
                        if ((k.localName ?: k.nodeName.substringAfter(':')) == local) out += k
                        walk(k)
                    }
                }
            }
            walk(el)
            return out
        }

        fun first(el: Node?, local: String): Element? = el?.let { all(it, local).firstOrNull() }
        fun text(el: Node?, local: String): String? = first(el, local)?.textContent?.trim()?.ifEmpty { null }
    }

    private fun security(): String {
        if (user.isEmpty()) return ""
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val created = fmt.format(Date(System.currentTimeMillis() + offsetMs))
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray()); sha.update(password.toByteArray())
        val digest = Base64.getEncoder().encodeToString(sha.digest())
        val b64Nonce = Base64.getEncoder().encodeToString(nonce)
        return "<s:Header><Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/" +
            "oasis-200401-wss-wssecurity-secext-1.0.xsd\"><UsernameToken><Username>${xmlEscape(user)}</Username>" +
            "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0" +
            "#PasswordDigest\">$digest</Password><Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/" +
            "oasis-200401-wss-soap-message-security-1.0#Base64Binary\">$b64Nonce</Nonce>" +
            "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
            "$created</Created></UsernameToken></Security></s:Header>"
    }

    private fun call(url: String, body: String, auth: Boolean = true): Element {
        val env = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tt=\"$NS_SCHEMA\">${if (auth) security() else ""}<s:Body>$body</s:Body></s:Envelope>"
        val req = Request.Builder().url(url).post(env.toRequestBody(SOAP)).build()
        val client = if (auth) http else Net.base
        val (code, data) = try {
            client.newCall(req).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
        } catch (e: Exception) {
            throw OnvifException("Could not reach $url: ${e.message}")
        }
        val faulty = String(data, Charsets.UTF_8).contains("Fault")
        if ((code == 401 || code == 403) && !faulty) throw OnvifException("Camera rejected the username or password ($code)", true)
        if (code >= 300 && !faulty) throw OnvifException("HTTP $code from $url")
        val root = try { parse(data) } catch (e: Exception) { throw OnvifException("Not an ONVIF response") }
        val fault = first(root, "Fault")
        if (fault != null) {
            val reason = fault.textContent.replace(Regex("\\s+"), " ").trim()
            if (Regex("NotAuthorized|authority|Sender", RegexOption.IGNORE_CASE).containsMatchIn(reason)) {
                throw OnvifException("Not authorized: check the camera's username and password. ${reason.take(200)}", true)
            }
            throw OnvifException(reason.take(300))
        }
        return root
    }

    private fun syncTime() {
        try {
            val root = call(deviceUrl, "<GetSystemDateAndTime xmlns=\"$NS_DEV\"/>", auth = false)
            val utc = first(root, "UTCDateTime") ?: return
            val d = first(utc, "Date"); val t = first(utc, "Time")
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(text(d, "Year")!!.toInt(), text(d, "Month")!!.toInt() - 1, text(d, "Day")!!.toInt(),
                text(t, "Hour")!!.toInt(), text(t, "Minute")!!.toInt(), text(t, "Second")!!.toInt())
            offsetMs = cal.timeInMillis - System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    fun deviceInfo(): Map<String, String?> {
        val root = call(deviceUrl, "<GetDeviceInformation xmlns=\"$NS_DEV\"/>")
        return listOf("Manufacturer", "Model", "FirmwareVersion", "SerialNumber").associateWith { text(root, it) }
    }

    private fun findServices() {
        try {
            val root = call(deviceUrl, "<GetServices xmlns=\"$NS_DEV\"><IncludeCapability>false</IncludeCapability></GetServices>")
            for (svc in all(root, "Service")) {
                when (text(svc, "Namespace")) {
                    NS_MEDIA -> mediaUrl = text(svc, "XAddr")
                    NS_MEDIA2 -> media2Url = text(svc, "XAddr")
                }
            }
        } catch (e: OnvifException) {
            if (e.authFailed) throw e
        }
        if (mediaUrl == null) {
            try {
                val root = call(deviceUrl, "<GetCapabilities xmlns=\"$NS_DEV\"><Category>All</Category></GetCapabilities>")
                mediaUrl = text(first(root, "Media"), "XAddr")
            } catch (e: OnvifException) {
                if (e.authFailed) throw e
            }
        }
        if (mediaUrl == null && media2Url == null) mediaUrl = deviceUrl
        // Some cameras report an internal address: talk to the host we actually reached.
        val reached = Regex("^\\w+://[^/]+").find(deviceUrl)!!.value
        mediaUrl = mediaUrl?.replace(Regex("^\\w+://[^/]+"), reached)
        media2Url = media2Url?.replace(Regex("^\\w+://[^/]+"), reached)
    }

    fun streams(): List<OnvifStream> {
        syncTime()
        findServices()
        val out = mutableListOf<OnvifStream>()
        mediaUrl?.let { media ->
            try {
                val root = call(media, "<GetProfiles xmlns=\"$NS_MEDIA\"/>")
                for (p in all(root, "Profiles")) {
                    val token = p.getAttribute("token")
                    val enc = first(p, "VideoEncoderConfiguration")
                    val res = first(enc, "Resolution")
                    val rtsp = try {
                        text(call(media, "<GetStreamUri xmlns=\"$NS_MEDIA\"><StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></StreamSetup>" +
                            "<ProfileToken>${xmlEscape(token)}</ProfileToken></GetStreamUri>"), "Uri")
                    } catch (e: OnvifException) { null }
                    val snap = try {
                        text(call(media, "<GetSnapshotUri xmlns=\"$NS_MEDIA\"><ProfileToken>${xmlEscape(token)}" +
                            "</ProfileToken></GetSnapshotUri>"), "Uri")
                    } catch (e: OnvifException) { null }
                    out += OnvifStream(text(p, "Name") ?: token, token, text(enc, "Encoding"),
                        res?.let { "${text(it, "Width")}x${text(it, "Height")}" }, rtsp, snap)
                }
            } catch (e: OnvifException) {
                if (media2Url == null || e.authFailed) throw e
            }
        }
        if (out.isEmpty()) media2Url?.let { media2 ->
            val root = call(media2, "<GetProfiles xmlns=\"$NS_MEDIA2\"><Type>All</Type></GetProfiles>")
            for (p in all(root, "Profiles")) {
                val token = p.getAttribute("token")
                val rtsp = try {
                    text(call(media2, "<GetStreamUri xmlns=\"$NS_MEDIA2\"><Protocol>RTSP</Protocol>" +
                        "<ProfileToken>${xmlEscape(token)}</ProfileToken></GetStreamUri>"), "Uri")
                } catch (e: OnvifException) { null }
                out += OnvifStream(text(p, "Name") ?: token, token, rtsp = rtsp)
            }
        }
        return out
    }
}
