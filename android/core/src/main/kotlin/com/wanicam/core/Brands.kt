package com.wanicam.core

import java.net.URLEncoder

/**
 * Known stream URL formats for common camera brands.
 *
 * Placeholders: {auth} {user} {password} {host} {port} {http_port} {channel} {channel0} {channel2}
 * {stream} {stream1} {stream2} {stream_name} {reolink_stream}. {stream} is 0 for the main stream
 * and 1 for the sub stream. Only the credentials the user enters are used: nothing here guesses
 * passwords.
 */
data class Brand(
    val id: String,
    val label: String,
    val rtspPort: Int = 554,
    val httpPort: Int = 80,
    val rtsp: List<String> = emptyList(),
    val snapshot: List<String> = emptyList(),
    val mjpeg: List<String> = emptyList(),
    val match: List<String> = emptyList(),
)

object Brands {
    val all: List<Brand> = listOf(
        Brand("hikvision", "Hikvision / HiLook / Annke",
            rtsp = listOf(
                "rtsp://{auth}{host}:{port}/Streaming/Channels/{channel}0{stream1}",
                "rtsp://{auth}{host}:{port}/h264/ch{channel}/{stream_name}/av_stream",
                "rtsp://{auth}{host}:{port}/ISAPI/Streaming/channels/{channel}0{stream1}",
            ),
            snapshot = listOf("http://{host}:{http_port}/ISAPI/Streaming/channels/{channel}01/picture",
                "http://{host}:{http_port}/Streaming/channels/{channel}01/picture"),
            mjpeg = listOf("http://{host}:{http_port}/ISAPI/Streaming/channels/{channel}0{stream1}/httpPreview"),
            match = listOf("hikvision", "hikvision-webs", "dvrdvs", "app-webs", "hilook", "annke")),
        Brand("dahua", "Dahua / Lorex / Amcrest (Dahua OEM)",
            rtsp = listOf("rtsp://{auth}{host}:{port}/cam/realmonitor?channel={channel}&subtype={stream}",
                "rtsp://{auth}{host}:{port}/live"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/snapshot.cgi?channel={channel}"),
            mjpeg = listOf("http://{host}:{http_port}/cgi-bin/mjpg/video.cgi?channel={channel}&subtype=1"),
            match = listOf("dahua", "lorex", "dh-", "ipc-hfw", "ipc-hdw")),
        Brand("amcrest", "Amcrest",
            rtsp = listOf("rtsp://{auth}{host}:{port}/cam/realmonitor?channel={channel}&subtype={stream}"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/snapshot.cgi?channel={channel}"),
            mjpeg = listOf("http://{host}:{http_port}/cgi-bin/mjpg/video.cgi?channel={channel}&subtype=1"),
            match = listOf("amcrest")),
        Brand("reolink", "Reolink",
            rtsp = listOf("rtsp://{auth}{host}:{port}/h264Preview_{channel2}_{reolink_stream}",
                "rtsp://{auth}{host}:{port}/h265Preview_{channel2}_{reolink_stream}",
                "rtsp://{auth}{host}:{port}/Preview_{channel2}_{reolink_stream}"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/api.cgi?cmd=Snap&channel={channel0}&rs=wanicam&user={user}&password={password}"),
            match = listOf("reolink")),
        Brand("axis", "Axis",
            rtsp = listOf("rtsp://{auth}{host}:{port}/axis-media/media.amp?camera={channel}",
                "rtsp://{auth}{host}:{port}/axis-media/media.amp",
                "rtsp://{auth}{host}:{port}/mpeg4/media.amp"),
            snapshot = listOf("http://{host}:{http_port}/axis-cgi/jpg/image.cgi?camera={channel}"),
            mjpeg = listOf("http://{host}:{http_port}/axis-cgi/mjpg/video.cgi?camera={channel}"),
            match = listOf("axis")),
        Brand("foscam", "Foscam", httpPort = 88,
            rtsp = listOf("rtsp://{auth}{host}:{port}/videoMain", "rtsp://{auth}{host}:{port}/videoSub",
                "rtsp://{auth}{host}:{port}/11"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2&usr={user}&pwd={password}",
                "http://{host}:{http_port}/snapshot.cgi?user={user}&pwd={password}"),
            mjpeg = listOf("http://{host}:{http_port}/videostream.cgi?user={user}&pwd={password}",
                "http://{host}:{http_port}/cgi-bin/CGIStream.cgi?cmd=GetMJStream&usr={user}&pwd={password}"),
            match = listOf("foscam")),
        Brand("uniview", "Uniview (UNV)",
            rtsp = listOf("rtsp://{auth}{host}:{port}/unicast/c{channel}/s{stream}/live",
                "rtsp://{auth}{host}:{port}/media/video{stream1}"),
            snapshot = listOf("http://{host}:{http_port}/images/snapshot.jpg"),
            match = listOf("uniview", "unv")),
        Brand("tapo", "TP-Link Tapo / VIGI (turn on Camera Account in the Tapo app)",
            rtsp = listOf("rtsp://{auth}{host}:{port}/stream{stream1}"),
            match = listOf("tp-link", "tapo", "vigi")),
        Brand("ubiquiti", "Ubiquiti UniFi Protect (copy the RTSP link from Protect)", rtspPort = 7447,
            snapshot = listOf("http://{host}/snap.jpeg"),
            match = listOf("ubiquiti", "unifi", "ubnt")),
        Brand("wyze", "Wyze (RTSP firmware)", rtsp = listOf("rtsp://{auth}{host}:{port}/live"), match = listOf("wyze")),
        Brand("eufy", "Eufy (turn on RTSP in the app)",
            rtsp = listOf("rtsp://{auth}{host}:{port}/live0", "rtsp://{auth}{host}:{port}/live1"),
            match = listOf("eufy", "anker")),
        Brand("vivotek", "Vivotek",
            rtsp = listOf("rtsp://{auth}{host}:{port}/live.sdp", "rtsp://{auth}{host}:{port}/live{stream1}s1.sdp"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/viewer/video.jpg"),
            mjpeg = listOf("http://{host}:{http_port}/video.mjpg"), match = listOf("vivotek")),
        Brand("hanwha", "Hanwha / Samsung Wisenet",
            rtsp = listOf("rtsp://{auth}{host}:{port}/profile{stream2}/media.smp",
                "rtsp://{auth}{host}:{port}/onvif/profile{stream2}/media.smp"),
            snapshot = listOf("http://{host}:{http_port}/stw-cgi/video.cgi?msubmenu=snapshot&action=view"),
            match = listOf("hanwha", "samsung", "wisenet", "techwin")),
        Brand("bosch", "Bosch",
            rtsp = listOf("rtsp://{auth}{host}:{port}/?inst={stream1}", "rtsp://{auth}{host}:{port}/rtsp_tunnel"),
            snapshot = listOf("http://{host}:{http_port}/snap.jpg"), match = listOf("bosch")),
        Brand("panasonic", "Panasonic / i-PRO",
            rtsp = listOf("rtsp://{auth}{host}:{port}/MediaInput/h264/stream_{stream1}",
                "rtsp://{auth}{host}:{port}/MediaInput/h264"),
            snapshot = listOf("http://{host}:{http_port}/cgi-bin/camera"),
            mjpeg = listOf("http://{host}:{http_port}/cgi-bin/mjpeg?stream=1"), match = listOf("panasonic", "i-pro")),
        Brand("sony", "Sony",
            rtsp = listOf("rtsp://{auth}{host}:{port}/media/video{stream1}"),
            snapshot = listOf("http://{host}:{http_port}/oneshotimage.jpg"),
            mjpeg = listOf("http://{host}:{http_port}/image"), match = listOf("sony")),
        Brand("mobotix", "Mobotix",
            rtsp = listOf("rtsp://{auth}{host}:{port}/stream0/mobotix.mjpeg"),
            snapshot = listOf("http://{host}:{http_port}/record/current.jpg"),
            mjpeg = listOf("http://{host}:{http_port}/control/faststream.jpg?stream=full"), match = listOf("mobotix")),
        Brand("dlink", "D-Link",
            rtsp = listOf("rtsp://{auth}{host}:{port}/live1.sdp", "rtsp://{auth}{host}:{port}/play1.sdp"),
            snapshot = listOf("http://{host}:{http_port}/image/jpeg.cgi"),
            mjpeg = listOf("http://{host}:{http_port}/video/mjpg.cgi", "http://{host}:{http_port}/video.cgi"),
            match = listOf("d-link", "dlink", "dcs-")),
        Brand("trendnet", "TRENDnet",
            rtsp = listOf("rtsp://{auth}{host}:{port}/mpeg4", "rtsp://{auth}{host}:{port}/play1.sdp"),
            snapshot = listOf("http://{host}:{http_port}/image/jpeg.cgi"),
            mjpeg = listOf("http://{host}:{http_port}/video/mjpg.cgi"), match = listOf("trendnet")),
        Brand("ezviz", "EZVIZ (device verification code as password)",
            rtsp = listOf("rtsp://{auth}{host}:{port}/h264/ch1/{stream_name}/av_stream",
                "rtsp://{auth}{host}:{port}/Streaming/Channels/10{stream1}"),
            match = listOf("ezviz")),
        Brand("swann", "Swann",
            rtsp = listOf("rtsp://{auth}{host}:{port}/ch0{channel}/{stream}",
                "rtsp://{auth}{host}:{port}/Streaming/Channels/{channel}0{stream1}"),
            match = listOf("swann")),
        Brand("geovision", "GeoVision", rtspPort = 8554,
            rtsp = listOf("rtsp://{auth}{host}:{port}/CH00{channel}.sdp"), match = listOf("geovision")),
        Brand("milesight", "Milesight",
            rtsp = listOf("rtsp://{auth}{host}:{port}/main", "rtsp://{auth}{host}:{port}/sub"),
            snapshot = listOf("http://{host}:{http_port}/snapshot.cgi"), match = listOf("milesight")),
        Brand("android_ipwebcam", "Android \"IP Webcam\" app", rtspPort = 8080, httpPort = 8080,
            rtsp = listOf("rtsp://{auth}{host}:8080/h264_ulaw.sdp", "rtsp://{auth}{host}:8080/h264_pcm.sdp"),
            snapshot = listOf("http://{host}:8080/shot.jpg"),
            mjpeg = listOf("http://{host}:8080/video"), match = listOf("ip webcam")),
        Brand("esp32cam", "ESP32-CAM / ESPHome",
            snapshot = listOf("http://{host}/capture", "http://{host}:80/jpg"),
            mjpeg = listOf("http://{host}:81/stream", "http://{host}/mjpeg/1"), match = listOf("esp32", "esphome")),
        Brand("generic", "Generic / unknown (common paths)",
            rtsp = listOf(
                "rtsp://{auth}{host}:{port}/live",
                "rtsp://{auth}{host}:{port}/live.sdp",
                "rtsp://{auth}{host}:{port}/stream1",
                "rtsp://{auth}{host}:{port}/h264",
                "rtsp://{auth}{host}:{port}/11",
                "rtsp://{auth}{host}:{port}/ch0_0.h264",
                "rtsp://{auth}{host}:{port}/onvif1",
                "rtsp://{auth}{host}:{port}/user={user}&password={password}&channel=1&stream=0.sdp",
                "rtsp://{auth}{host}:{port}/Streaming/Channels/101",
                "rtsp://{auth}{host}:{port}/cam/realmonitor?channel=1&subtype=0",
                "rtsp://{auth}{host}:{port}/videoMain",
                "rtsp://{auth}{host}:{port}/h264Preview_01_main",
                "rtsp://{auth}{host}:{port}/unicast/c1/s0/live",
                "rtsp://{auth}{host}:{port}/axis-media/media.amp",
            ),
            snapshot = listOf("http://{host}:{http_port}/snapshot.jpg", "http://{host}:{http_port}/snapshot.cgi",
                "http://{host}:{http_port}/image.jpg", "http://{host}:{http_port}/cgi-bin/snapshot.cgi"),
            mjpeg = listOf("http://{host}:{http_port}/video.mjpg", "http://{host}:{http_port}/mjpg/video.mjpg",
                "http://{host}:{http_port}/videostream.cgi", "http://{host}:{http_port}/video.cgi")),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String?): Brand = byId[id] ?: byId.getValue("generic")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun render(pattern: String, host: String, user: String = "", password: String = "", port: Int = 554,
               httpPort: Int = 80, channel: Int = 1, stream: Int = 0): String {
        val values = mapOf(
            "auth" to if (user.isNotEmpty()) "${enc(user)}:${enc(password)}@" else "",
            "host" to host, "user" to enc(user), "password" to enc(password),
            "port" to port.toString(), "http_port" to httpPort.toString(),
            "channel" to channel.toString(), "channel0" to (channel - 1).toString(),
            "channel2" to "%02d".format(channel),
            "stream" to stream.toString(), "stream1" to (stream + 1).toString(), "stream2" to (stream + 1).toString(),
            "stream_name" to if (stream == 0) "main" else "sub",
            "reolink_stream" to if (stream == 0) "main" else "sub",
        )
        return Regex("\\{(\\w+)}").replace(pattern) { m -> values[m.groupValues[1]] ?: m.value }
    }

    /** Rendered candidate URLs of one kind: "rtsp", "snapshot" or "mjpeg". */
    fun candidates(brandId: String?, kind: String, host: String, user: String = "", password: String = "",
                   port: Int? = null, httpPort: Int? = null, channel: Int = 1, stream: Int = 0): List<String> {
        val b = get(brandId)
        val patterns = when (kind) {
            "rtsp" -> b.rtsp
            "snapshot" -> b.snapshot
            "mjpeg" -> b.mjpeg
            else -> emptyList()
        }
        return patterns.map {
            render(it, host, user, password, port ?: b.rtspPort, httpPort ?: b.httpPort, channel, stream)
        }.distinct()
    }

    fun guess(text: String?): String? {
        val t = text?.lowercase() ?: return null
        return all.firstOrNull { b -> b.match.any { t.contains(it) } }?.id
    }
}
