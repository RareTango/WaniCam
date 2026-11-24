"""Known stream URL patterns for common camera brands.

Placeholders: {user} {password} {host} {port} {channel} {stream}
  {stream} is 0 for main stream, 1 for sub stream (some brands translate it).
Patterns are tried in order. Only the credentials the user enters are used;
nothing here guesses passwords.
"""

BRANDS = {
    "hikvision": {
        "label": "Hikvision / HiLook / Annke (Hik OEM)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/Streaming/Channels/{channel}0{stream1}",
            "rtsp://{auth}{host}:{port}/h264/ch{channel}/{stream_name}/av_stream",
            "rtsp://{auth}{host}:{port}/ISAPI/Streaming/channels/{channel}0{stream1}",
        ],
        "snapshot": ["http://{host}:{http_port}/ISAPI/Streaming/channels/{channel}01/picture",
                     "http://{host}:{http_port}/Streaming/channels/{channel}01/picture"],
        "mjpeg": ["http://{host}:{http_port}/ISAPI/Streaming/channels/{channel}0{stream1}/httpPreview"],
        "match": ["hikvision", "hikvision-webs", "dvrdvs", "app-webs", "hilook", "annke"],
    },
    "dahua": {
        "label": "Dahua / Lorex / Amcrest (Dahua OEM)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/cam/realmonitor?channel={channel}&subtype={stream}",
            "rtsp://{auth}{host}:{port}/live",
        ],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/snapshot.cgi?channel={channel}"],
        "mjpeg": ["http://{host}:{http_port}/cgi-bin/mjpg/video.cgi?channel={channel}&subtype=1"],
        "match": ["dahua", "lorex", "dh-", "ipc-hfw", "ipc-hdw"],
    },
    "amcrest": {
        "label": "Amcrest",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/cam/realmonitor?channel={channel}&subtype={stream}"],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/snapshot.cgi?channel={channel}"],
        "mjpeg": ["http://{host}:{http_port}/cgi-bin/mjpg/video.cgi?channel={channel}&subtype=1"],
        "match": ["amcrest"],
    },
    "reolink": {
        "label": "Reolink",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/h264Preview_{channel2}_{reolink_stream}",
            "rtsp://{auth}{host}:{port}/h265Preview_{channel2}_{reolink_stream}",
            "rtsp://{auth}{host}:{port}/Preview_{channel2}_{reolink_stream}",
        ],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/api.cgi?cmd=Snap&channel={channel0}&rs=wanicam&user={user}&password={password}"],
        "mjpeg": [],
        "match": ["reolink"],
    },
    "axis": {
        "label": "Axis",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/axis-media/media.amp?camera={channel}",
            "rtsp://{auth}{host}:{port}/axis-media/media.amp",
            "rtsp://{auth}{host}:{port}/mpeg4/media.amp",
        ],
        "snapshot": ["http://{host}:{http_port}/axis-cgi/jpg/image.cgi?camera={channel}"],
        "mjpeg": ["http://{host}:{http_port}/axis-cgi/mjpg/video.cgi?camera={channel}"],
        "match": ["axis"],
    },
    "foscam": {
        "label": "Foscam",
        "rtsp_port": 554, "http_port": 88,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/videoMain",
            "rtsp://{auth}{host}:{port}/videoSub",
            "rtsp://{auth}{host}:{port}/11",
        ],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2&usr={user}&pwd={password}",
                     "http://{host}:{http_port}/snapshot.cgi?user={user}&pwd={password}"],
        "mjpeg": ["http://{host}:{http_port}/videostream.cgi?user={user}&pwd={password}",
                  "http://{host}:{http_port}/cgi-bin/CGIStream.cgi?cmd=GetMJStream&usr={user}&pwd={password}"],
        "match": ["foscam"],
    },
    "uniview": {
        "label": "Uniview (UNV)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/unicast/c{channel}/s{stream}/live",
            "rtsp://{auth}{host}:{port}/media/video{stream1}",
        ],
        "snapshot": ["http://{host}:{http_port}/images/snapshot.jpg"],
        "mjpeg": [],
        "match": ["uniview", "unv"],
    },
    "tapo": {
        "label": "TP-Link Tapo / VIGI (enable 'Camera Account' in the Tapo app)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/stream{stream1}",
        ],
        "snapshot": [], "mjpeg": [],
        "match": ["tp-link", "tapo", "vigi"],
    },
    "ubiquiti": {
        "label": "Ubiquiti UniFi Protect (copy the RTSP(S) link from Protect)",
        "rtsp_port": 7447, "http_port": 80,
        "rtsp": [
            "rtsp://{host}:7447/{token}",
            "rtsps://{host}:7441/{token}?enableSrtp",
        ],
        "snapshot": ["http://{host}/snap.jpeg"], "mjpeg": [],
        "match": ["ubiquiti", "unifi", "ubnt"],
    },
    "wyze": {
        "label": "Wyze (RTSP firmware / wyze-bridge)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/live"],
        "snapshot": [], "mjpeg": [], "match": ["wyze"],
    },
    "eufy": {
        "label": "Eufy (enable RTSP in app)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/live0", "rtsp://{auth}{host}:{port}/live1"],
        "snapshot": [], "mjpeg": [], "match": ["eufy", "anker"],
    },
    "vivotek": {
        "label": "Vivotek",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/live.sdp", "rtsp://{auth}{host}:{port}/live{stream1}s1.sdp"],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/viewer/video.jpg"],
        "mjpeg": ["http://{host}:{http_port}/video.mjpg"], "match": ["vivotek"],
    },
    "hanwha": {
        "label": "Hanwha / Samsung Wisenet",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/profile{stream2}/media.smp",
                 "rtsp://{auth}{host}:{port}/onvif/profile{stream2}/media.smp"],
        "snapshot": ["http://{host}:{http_port}/stw-cgi/video.cgi?msubmenu=snapshot&action=view"],
        "mjpeg": [], "match": ["hanwha", "samsung", "wisenet", "techwin"],
    },
    "bosch": {
        "label": "Bosch",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/?inst={stream1}", "rtsp://{auth}{host}:{port}/rtsp_tunnel"],
        "snapshot": ["http://{host}:{http_port}/snap.jpg"], "mjpeg": [], "match": ["bosch"],
    },
    "panasonic": {
        "label": "Panasonic / i-PRO",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/MediaInput/h264/stream_{stream1}",
                 "rtsp://{auth}{host}:{port}/MediaInput/h264"],
        "snapshot": ["http://{host}:{http_port}/cgi-bin/camera"],
        "mjpeg": ["http://{host}:{http_port}/cgi-bin/mjpeg?stream=1"], "match": ["panasonic", "i-pro"],
    },
    "sony": {
        "label": "Sony",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/media/video{stream1}"],
        "snapshot": ["http://{host}:{http_port}/oneshotimage.jpg"],
        "mjpeg": ["http://{host}:{http_port}/image"], "match": ["sony"],
    },
    "mobotix": {
        "label": "Mobotix",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/stream0/mobotix.mjpeg"],
        "snapshot": ["http://{host}:{http_port}/record/current.jpg"],
        "mjpeg": ["http://{host}:{http_port}/control/faststream.jpg?stream=full"], "match": ["mobotix"],
    },
    "dlink": {
        "label": "D-Link",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/live1.sdp", "rtsp://{auth}{host}:{port}/play1.sdp"],
        "snapshot": ["http://{host}:{http_port}/image/jpeg.cgi"],
        "mjpeg": ["http://{host}:{http_port}/video/mjpg.cgi", "http://{host}:{http_port}/video.cgi"],
        "match": ["d-link", "dlink", "dcs-"],
    },
    "trendnet": {
        "label": "TRENDnet",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/mpeg4", "rtsp://{auth}{host}:{port}/play1.sdp"],
        "snapshot": ["http://{host}:{http_port}/image/jpeg.cgi"],
        "mjpeg": ["http://{host}:{http_port}/video/mjpg.cgi"], "match": ["trendnet"],
    },
    "ezviz": {
        "label": "EZVIZ (use device verification code as password)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/h264/ch1/{stream_name}/av_stream",
                 "rtsp://{auth}{host}:{port}/Streaming/Channels/10{stream1}"],
        "snapshot": [], "mjpeg": [], "match": ["ezviz"],
    },
    "swann": {
        "label": "Swann",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/ch0{channel}/{stream}",
                 "rtsp://{auth}{host}:{port}/Streaming/Channels/{channel}0{stream1}"],
        "snapshot": [], "mjpeg": [], "match": ["swann"],
    },
    "geovision": {
        "label": "GeoVision",
        "rtsp_port": 8554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/CH00{channel}.sdp"],
        "snapshot": [], "mjpeg": [], "match": ["geovision"],
    },
    "milesight": {
        "label": "Milesight",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": ["rtsp://{auth}{host}:{port}/main", "rtsp://{auth}{host}:{port}/sub"],
        "snapshot": ["http://{host}:{http_port}/snapshot.cgi"], "mjpeg": [], "match": ["milesight"],
    },
    "android_ipwebcam": {
        "label": "Android 'IP Webcam' app",
        "rtsp_port": 8080, "http_port": 8080,
        "rtsp": ["rtsp://{auth}{host}:8080/h264_ulaw.sdp", "rtsp://{auth}{host}:8080/h264_pcm.sdp"],
        "snapshot": ["http://{host}:8080/shot.jpg"],
        "mjpeg": ["http://{host}:8080/video"], "match": ["ip webcam"],
    },
    "esp32cam": {
        "label": "ESP32-CAM / ESPHome",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [],
        "snapshot": ["http://{host}/capture", "http://{host}:80/jpg"],
        "mjpeg": ["http://{host}:81/stream", "http://{host}/mjpeg/1"], "match": ["esp32", "esphome"],
    },
    "generic": {
        "label": "Generic / unknown (common paths)",
        "rtsp_port": 554, "http_port": 80,
        "rtsp": [
            "rtsp://{auth}{host}:{port}/",
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
        ],
        "snapshot": ["http://{host}:{http_port}/snapshot.jpg", "http://{host}:{http_port}/snapshot.cgi",
                     "http://{host}:{http_port}/image.jpg", "http://{host}:{http_port}/cgi-bin/snapshot.cgi"],
        "mjpeg": ["http://{host}:{http_port}/video.mjpg", "http://{host}:{http_port}/mjpg/video.mjpg",
                  "http://{host}:{http_port}/videostream.cgi", "http://{host}:{http_port}/video.cgi"],
        "match": [],
    },
}


def _q(s):
    from urllib.parse import quote
    return quote(s or "", safe="")


def render(pattern, host, user="", password="", port=None, http_port=None,
           channel=1, stream=0, token=""):
    auth = f"{_q(user)}:{_q(password)}@" if user else ""
    channel = int(channel or 1)
    stream = int(stream or 0)
    vals = dict(
        auth=auth, host=host, user=_q(user), password=_q(password),
        port=port or 554, http_port=http_port or 80, channel=channel,
        channel0=channel - 1, channel2=f"{channel:02d}", stream=stream,
        stream1=stream + 1, stream2=stream + 1,
        stream_name="main" if stream == 0 else "sub",
        reolink_stream="main" if stream == 0 else "sub", token=token,
    )
    return pattern.format(**vals)


def candidates(brand, kind, host, **kw):
    """Return rendered candidate URLs of one kind (rtsp|snapshot|mjpeg)."""
    b = BRANDS.get(brand) or BRANDS["generic"]
    kw.setdefault("port", b["rtsp_port"])
    kw.setdefault("http_port", b["http_port"])
    out = []
    for p in b.get(kind, []):
        try:
            u = render(p, host, **kw)
        except (KeyError, ValueError):
            continue
        if u not in out:
            out.append(u)
    return out


def guess_brand(text):
    t = (text or "").lower()
    for key, b in BRANDS.items():
        for m in b.get("match", []):
            if m in t:
                return key
    return None


def brand_list():
    return [{"id": k, "label": v["label"], "rtsp_port": v["rtsp_port"], "http_port": v["http_port"]}
            for k, v in BRANDS.items()]
