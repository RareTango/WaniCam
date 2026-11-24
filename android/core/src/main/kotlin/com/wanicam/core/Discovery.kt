package com.wanicam.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.UUID

data class Device(
    val ip: String,
    val sources: MutableList<String> = mutableListOf(),
    var name: String? = null,
    var model: String? = null,
    var manufacturer: String? = null,
    var onvifXaddrs: List<String> = emptyList(),
    var openPorts: MutableList<Int> = mutableListOf(),
    val banners: MutableMap<Int, String> = mutableMapOf(),
    var rtspPort: Int? = null,
    var httpPort: Int? = null,
    var brand: String = "generic",
    var onvif: Boolean = false,
    var note: String? = null,
    var ssdpServer: String? = null,
    var ssdpLocation: String? = null,
    var deviceType: String? = null,
    var mdnsServices: MutableList<String> = mutableListOf(),
    var mdnsTxt: String? = null,
) {
    val summary: String
        get() = listOfNotNull(manufacturer, model, name).joinToString(" · ").ifEmpty {
            banners.values.firstOrNull() ?: ""
        }
}

/**
 * Finds cameras with ONVIF WS-Discovery, SSDP/UPnP, mDNS and a camera port scan. Everything here
 * only listens for announcements or checks whether a port is open and reads public banners.
 * No logins are attempted. On Android, hold a WifiManager.MulticastLock while this runs.
 */
object Discovery {
    val CAMERA_PORTS = listOf(554, 8554, 10554, 7447, 5554, 80, 8080, 8000, 81, 88, 443, 8443, 8899, 2020,
        37777, 34567, 9000, 1935, 8081, 5000)
    val RTSP_PORTS = setOf(554, 8554, 10554, 7447, 5554)
    val HTTP_PORTS = setOf(80, 8080, 8000, 81, 88, 8081, 5000, 8899, 2020)
    private val CAMERA_ONLY_PORTS = setOf(8899, 2020, 37777, 34567, 8000, 9000, 7447)
    private val CAMERA_WORDS = Regex("camera|webcam|ipcam|nvr|dvr|mjpeg|onvif|rtsp", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ WS-Discovery

    private fun probe(types: String) = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
 xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
 xmlns:dn="http://www.onvif.org/ver10/network/wsdl"
 xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>
<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
</e:Header><e:Body><d:Probe><d:Types>$types</d:Types></d:Probe></e:Body></e:Envelope>"""

    private inline fun receiveLoop(sock: DatagramSocket, timeoutMs: Long, handle: (String, ByteArray, Int) -> Unit) {
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(65535)
        sock.soTimeout = 400
        while (System.currentTimeMillis() < end) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val addr = pkt.address
            if (addr !is Inet4Address) continue
            handle(addr.hostAddress, pkt.data.copyOf(pkt.length), pkt.length)
        }
    }

    private fun multicastSocket(): MulticastSocket = MulticastSocket(0).apply { timeToLive = 2 }

    fun wsDiscovery(timeoutMs: Long = 3000): List<Device> {
        val found = linkedMapOf<String, Device>()
        multicastSocket().use { sock ->
            val group = InetAddress.getByName("239.255.255.250")
            for (t in listOf("dn:NetworkVideoTransmitter", "", "tds:Device")) {
                val b = probe(t).toByteArray()
                try { sock.send(DatagramPacket(b, b.size, group, 3702)) } catch (_: Exception) {}
            }
            receiveLoop(sock, timeoutMs) { ip, data, _ ->
                val text = String(data, Charsets.UTF_8)
                val xaddrs = Regex("<[^>]*XAddrs>([^<]+)<").find(text)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))
                    ?: emptyList()
                val scopes = Regex("<[^>]*Scopes[^>]*>([^<]+)<").findAll(text).joinToString(" ") { it.groupValues[1] }
                val meta = mutableMapOf<String, String>()
                for (sc in scopes.split(Regex("\\s+"))) {
                    val m = Regex("onvif://www\\.onvif\\.org/(name|hardware|manufacturer|mac)/(.+)").find(sc) ?: continue
                    meta[m.groupValues[1]] = try { URLDecoder.decode(m.groupValues[2], "UTF-8") } catch (_: Exception) { m.groupValues[2] }
                }
                found.getOrPut(ip) {
                    Device(ip, mutableListOf("onvif"), meta["name"], meta["hardware"], meta["manufacturer"], xaddrs)
                }
            }
        }
        return found.values.toList()
    }

    // ------------------------------------------------------------ SSDP

    fun ssdp(timeoutMs: Long = 3000): List<Device> {
        val found = linkedMapOf<String, Device>()
        multicastSocket().use { sock ->
            val group = InetAddress.getByName("239.255.255.250")
            for (st in listOf("ssdp:all", "urn:schemas-upnp-org:device:Basic:1", "urn:schemas-upnp-org:device:NetworkCamera:1")) {
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                val b = msg.toByteArray()
                try { sock.send(DatagramPacket(b, b.size, group, 1900)) } catch (_: Exception) {}
            }
            receiveLoop(sock, timeoutMs) { ip, data, _ ->
                val hdr = mutableMapOf<String, String>()
                String(data, Charsets.UTF_8).split("\r\n").drop(1).forEach { line ->
                    val i = line.indexOf(':')
                    if (i > 0) hdr[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val d = found.getOrPut(ip) { Device(ip, mutableListOf("ssdp")) }
                if (d.ssdpServer == null) d.ssdpServer = hdr["server"]
                if (d.ssdpLocation == null) d.ssdpLocation = hdr["location"]
            }
        }
        for (d in found.values) {
            val loc = d.ssdpLocation ?: continue
            try {
                Net.base.newCall(Request.Builder().url(loc).build()).execute().use { r ->
                    val x = r.body?.string()?.take(65536) ?: return@use
                    fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(x)?.groupValues?.get(1)?.trim()
                    d.manufacturer = tag("manufacturer"); d.model = tag("modelName")
                    d.name = tag("friendlyName"); d.deviceType = tag("deviceType")
                }
            } catch (_: Exception) {
            }
        }
        return found.values.toList()
    }

    // ------------------------------------------------------------ mDNS

    val MDNS_SERVICES = listOf("_rtsp._tcp.local", "_axis-video._tcp.local", "_onvif._tcp.local",
        "_camera._tcp.local", "_hap._tcp.local", "_http._tcp.local", "_nvr._tcp.local")

    sealed class Rec(val name: String) {
        class Ptr(name: String, val target: String) : Rec(name)
        class A(name: String, val ip: String) : Rec(name)
        class Srv(name: String, val port: Int, val target: String) : Rec(name)
        class Txt(name: String, val items: List<String>) : Rec(name)
    }

    private fun dnsName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var off = start
        var ret = -1
        repeat(64) {
            if (off >= data.size) return labels.joinToString(".") to (if (ret >= 0) ret else off)
            val len = data[off].toInt() and 0xff
            when {
                len == 0 -> { off += 1; return labels.joinToString(".") to (if (ret >= 0) ret else off) }
                len and 0xC0 == 0xC0 -> {
                    val ptr = ((len and 0x3f) shl 8) or (data[off + 1].toInt() and 0xff)
                    if (ret < 0) ret = off + 2
                    off = ptr
                }
                else -> {
                    labels += String(data, off + 1, len, Charsets.UTF_8)
                    off += 1 + len
                }
            }
        }
        return labels.joinToString(".") to (if (ret >= 0) ret else off)
    }

    fun parseMdns(data: ByteArray): List<Rec> {
        val recs = mutableListOf<Rec>()
        try {
            val bb = ByteBuffer.wrap(data)
            val qd = bb.getShort(4).toInt() and 0xffff
            val total = (bb.getShort(6).toInt() and 0xffff) + (bb.getShort(8).toInt() and 0xffff) +
                (bb.getShort(10).toInt() and 0xffff)
            var off = 12
            repeat(qd) { off = dnsName(data, off).second + 4 }
            repeat(total) {
                val (name, o) = dnsName(data, off)
                off = o
                val type = bb.getShort(off).toInt() and 0xffff
                val rdlen = bb.getShort(off + 8).toInt() and 0xffff
                off += 10
                when (type) {
                    12 -> recs += Rec.Ptr(name, dnsName(data, off).first)
                    1 -> if (rdlen == 4) recs += Rec.A(name, InetAddress.getByAddress(data.copyOfRange(off, off + 4)).hostAddress)
                    33 -> recs += Rec.Srv(name, bb.getShort(off + 4).toInt() and 0xffff, dnsName(data, off + 6).first)
                    16 -> {
                        val items = mutableListOf<String>()
                        var i = off
                        while (i < off + rdlen) {
                            val l = data[i].toInt() and 0xff
                            items += String(data, i + 1, l, Charsets.UTF_8)
                            i += 1 + l
                        }
                        recs += Rec.Txt(name, items)
                    }
                }
                off += rdlen
            }
        } catch (_: Exception) {
        }
        return recs
    }

    fun mdns(timeoutMs: Long = 3000): List<Device> {
        val q = ByteBuffer.allocate(512)
        q.putShort(0); q.putShort(0); q.putShort(MDNS_SERVICES.size.toShort()); q.putShort(0); q.putShort(0); q.putShort(0)
        for (s in MDNS_SERVICES) {
            for (p in s.split('.')) { q.put(p.length.toByte()); q.put(p.toByteArray()) }
            q.put(0)
            q.putShort(12); q.putShort(0x8001.toShort()) // PTR, ask for unicast reply
        }
        val bytes = q.array().copyOf(q.position())
        val found = linkedMapOf<String, Device>()
        multicastSocket().use { sock ->
            sock.timeToLive = 255
            try { sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("224.0.0.251"), 5353)) } catch (_: Exception) {}
            receiveLoop(sock, timeoutMs) { ip, data, _ ->
                val recs = parseMdns(data)
                val services = recs.filterIsInstance<Rec.Ptr>().filter { it.name in MDNS_SERVICES }.map { it.target }
                if (services.isEmpty()) return@receiveLoop
                val txt = recs.filterIsInstance<Rec.Txt>().joinToString(" ") { it.items.joinToString(" ") }
                val all = txt + " " + services.joinToString(" ")
                val cameraLike = services.any { !it.endsWith("_http._tcp.local") }
                if (!cameraLike && Brands.guess(all) == null && !all.contains("cam", true)) return@receiveLoop
                val d = found.getOrPut(ip) { Device(ip, mutableListOf("mdns")) }
                services.forEach { if (it !in d.mdnsServices) d.mdnsServices += it }
                if (d.name == null) d.name = services.first().substringBefore("._")
                recs.filterIsInstance<Rec.Srv>().firstOrNull { it.name.contains("_rtsp._tcp") }?.let { d.rtspPort = it.port }
                if (services.any { it.contains("_hap._tcp") }) {
                    d.note = "HomeKit device: HomeKit cameras don't offer a standard stream"
                }
                d.mdnsTxt = txt.take(300)
            }
        }
        return found.values.toList()
    }

    // ------------------------------------------------------------ port scan

    private fun readSome(input: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var off = 0
        try {
            while (off < max) {
                val n = input.read(buf, off, max - off)
                if (n < 0) break
                off += n
                if (String(buf, 0, off).contains("\r\n\r\n")) break
            }
        } catch (_: Exception) {
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): Pair<Int, String?>? {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, port), timeoutMs)
        } catch (e: Exception) {
            s.close()
            return null
        }
        return s.use {
            it.soTimeout = 1500
            var banner: String? = null
            try {
                val req = if (port in HTTP_PORTS) "GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: WaniCam\r\n\r\n"
                else "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: WaniCam\r\n\r\n"
                if (port != 443 && port != 8443) {
                    it.getOutputStream().write(req.toByteArray())
                    banner = readSome(it.getInputStream(), 4096)
                }
            } catch (_: Exception) {
            }
            port to banner
        }
    }

    fun summarizeBanner(b: String): String {
        val parts = mutableListOf<String>()
        Regex("^Server:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(b)?.let { parts += it.groupValues[1].trim() }
        Regex("<title>([^<]{1,80})</title>", RegexOption.IGNORE_CASE).find(b)?.let { parts += it.groupValues[1].trim() }
        Regex("realm=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(b)?.let { parts += "realm=" + it.groupValues[1] }
        if (parts.isEmpty()) parts += b.substringBefore("\r\n").take(80)
        return parts.joinToString(" | ")
    }

    /** Hosts of an IPv4 CIDR such as 192.168.1.0/24 (at most 4096). */
    fun hosts(cidr: String): List<String> {
        val (base, bitsStr) = if ('/' in cidr) cidr.split('/', limit = 2).let { it[0] to it[1] } else cidr to "32"
        val bits = bitsStr.toInt().coerceIn(20, 32)
        val addr = InetAddress.getByName(base).address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val mask = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL
        val net = addr and mask
        val size = 1L shl (32 - bits)
        val range = if (size <= 2) (0 until size) else (1 until size - 1)
        return range.map { i ->
            val v = net + i
            "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"
        }
    }

    fun isPrivate(cidr: String): Boolean = try {
        val a = InetAddress.getByName(cidr.substringBefore('/'))
        a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            // 100.64.0.0/10 carrier-grade NAT, used by some mesh/VPN home setups
            (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xc0) == 64
    } catch (_: Exception) {
        false
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun portScan(subnets: List<String>, exclude: Set<String> = emptySet(), ports: List<Int> = CAMERA_PORTS,
                         timeoutMs: Int = 600, concurrency: Int = 128): List<Device> = withContext(Dispatchers.IO) {
        val hosts = subnets.flatMap { hosts(it) }.filter { it !in exclude }.distinct().take(4096)
        // Blocking connects: give them their own pool so we really run `concurrency` at once.
        val pool = Dispatchers.IO.limitedParallelism(concurrency)
        val results = coroutineScope {
            hosts.flatMap { h -> ports.map { p -> h to p } }.map { (h, p) ->
                async(pool) { probePort(h, p, timeoutMs)?.let { h to it } }
            }.awaitAll().filterNotNull()
        }
        val byIp = linkedMapOf<String, Device>()
        val rtspSeen = mutableMapOf<String, MutableSet<Int>>()
        for ((ip, pb) in results) {
            val (port, banner) = pb
            val d = byIp.getOrPut(ip) { Device(ip, mutableListOf("scan")) }
            d.openPorts += port
            if (!banner.isNullOrEmpty()) {
                d.banners[port] = summarizeBanner(banner)
                if (banner.startsWith("RTSP/")) rtspSeen.getOrPut(ip) { mutableSetOf() } += port
            }
        }
        byIp.values.filter { d ->
            val open = d.openPorts.toSet()
            val rtsp = (rtspSeen[d.ip] ?: emptySet()) + open.intersect(RTSP_PORTS)
            val text = d.banners.values.joinToString(" ")
            val isCam = rtsp.isNotEmpty() || open.intersect(CAMERA_ONLY_PORTS).isNotEmpty() ||
                Brands.guess(text) != null || CAMERA_WORDS.containsMatchIn(text)
            if (isCam) {
                d.openPorts.sort()
                rtsp.minOrNull()?.let { d.rtspPort = it }
                val http = open.intersect(HTTP_PORTS).sorted()
                if (http.isNotEmpty()) d.httpPort = if (80 in http) 80 else http.first()
            }
            isCam
        }
    }

    // ------------------------------------------------------------ combined

    data class Result(val devices: List<Device>, val errors: Map<String, String>, val subnets: List<String>)

    suspend fun discoverAll(subnets: List<String>, exclude: Set<String> = emptySet(), scan: Boolean = true,
                            timeoutMs: Long = 3000): Result = coroutineScope {
        val errors = mutableMapOf<String, String>()
        suspend fun <T> safe(name: String, f: suspend () -> List<T>): List<T> =
            try { f() } catch (e: Exception) { synchronized(errors) { errors[name] = e.message ?: e.toString() }; emptyList() }
        val onvif = async(Dispatchers.IO) { safe("onvif") { wsDiscovery(timeoutMs) } }
        val mdns = async(Dispatchers.IO) { safe("mdns") { mdns(timeoutMs) } }
        val ssdp = async(Dispatchers.IO) { safe("ssdp") { ssdp(timeoutMs) } }
        val ports = async(Dispatchers.IO) { if (scan) safe("scan") { portScan(subnets, exclude) } else emptyList() }
        val merged = linkedMapOf<String, Device>()
        for ((method, list) in listOf("onvif" to onvif.await(), "mdns" to mdns.await(), "ssdp" to ssdp.await(),
            "scan" to ports.await())) {
            for (d in list) {
                val e = merged[d.ip]
                if (e == null) { merged[d.ip] = d; continue }
                if (method !in e.sources) e.sources += method
                e.name = e.name ?: d.name; e.model = e.model ?: d.model; e.manufacturer = e.manufacturer ?: d.manufacturer
                if (e.onvifXaddrs.isEmpty()) e.onvifXaddrs = d.onvifXaddrs
                d.openPorts.forEach { if (it !in e.openPorts) e.openPorts += it }
                d.banners.forEach { (k, v) -> e.banners.putIfAbsent(k, v) }
                e.rtspPort = e.rtspPort ?: d.rtspPort; e.httpPort = e.httpPort ?: d.httpPort
                e.note = e.note ?: d.note; e.ssdpServer = e.ssdpServer ?: d.ssdpServer
                e.deviceType = e.deviceType ?: d.deviceType
                d.mdnsServices.forEach { if (it !in e.mdnsServices) e.mdnsServices += it }
            }
        }
        val devices = merged.values.filter { d ->
            // SSDP alone also finds TVs and routers: keep only what looks like a camera.
            if (d.sources == listOf("ssdp")) {
                val t = listOfNotNull(d.manufacturer, d.model, d.name, d.deviceType, d.ssdpServer).joinToString(" ")
                Brands.guess(t) != null || Regex("camera|ipcam|nvr|dvr|video", RegexOption.IGNORE_CASE).containsMatchIn(t)
            } else true
        }.onEach { d ->
            val t = listOfNotNull(d.name, d.model, d.manufacturer, d.ssdpServer, d.deviceType, d.mdnsTxt).joinToString(" ") +
                " " + d.banners.values.joinToString(" ") + " " + d.mdnsServices.joinToString(" ")
            d.brand = Brands.guess(t) ?: "generic"
            val b = Brands.get(d.brand)
            if (d.rtspPort == null) d.rtspPort = b.rtspPort
            if (d.httpPort == null) d.httpPort = b.httpPort
            d.onvif = d.onvifXaddrs.isNotEmpty() || 8899 in d.openPorts || 2020 in d.openPorts
        }.sortedBy { d ->
            d.ip.split('.').mapNotNull { it.toIntOrNull() }.fold(0L) { acc, v -> acc * 256 + v }
        }
        Result(devices, errors, subnets)
    }
}
