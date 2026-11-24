# WaniCam

Find the IP cameras on your own network and watch them in your browser.

WaniCam runs on your computer (Windows, macOS or Linux) and opens a dashboard at
`http://localhost:8765`. It finds cameras automatically, asks each one for its
stream addresses using the login you give it, and shows every camera in a grid.

It is meant for cameras you own or are allowed to access. It uses only the
username and password you type in for each camera. It never guesses passwords
or tries default logins, and it stops after the first rejected login so the
camera doesn't lock your account.

## What it works with

**Finding cameras**

- **ONVIF WS-Discovery**: most IP cameras and NVRs made since about 2012.
- **mDNS / Bonjour**: Axis, cameras that advertise RTSP, HomeKit devices.
- **UPnP / SSDP**: many consumer cameras and NVRs.
- **Port scan** of your local network for camera ports (RTSP 554/8554, ONVIF
  8899/2020, Hikvision 8000, Dahua 37777, XMEye 34567, Reolink 9000, UniFi 7447
  and the usual web ports). It reads the public banner to guess the brand.
  Only private (home/office) network ranges can be scanned.

**Watching**

- **RTSP and RTSPS** (H.264, H.265, MJPEG), converted by ffmpeg so any browser
  can show it.
- **ONVIF**: the camera reports its own stream and snapshot addresses (Profile S
  Media1, with a Media2 fallback for newer cameras).
- **HTTP MJPEG** streams and **JPEG snapshot** URLs (refreshed continuously).
- **RTMP, HLS (.m3u8)** and anything else ffmpeg can open.
- **USB and built-in webcams**, either on the computer running WaniCam (via
  ffmpeg) or on the computer you're browsing from (via the browser).
- **NVR/DVR channels**: set the channel number under "Advanced".

**Built-in URL formats** (tried automatically when ONVIF is off or
unavailable): Hikvision and its OEMs (HiLook, Annke), Dahua and its OEMs
(Lorex, Amcrest), Reolink, Axis, Foscam, Uniview, TP-Link Tapo and VIGI,
Ubiquiti UniFi Protect, Wyze, Eufy, Vivotek, Hanwha/Samsung Wisenet, Bosch,
Panasonic/i-PRO, Sony, Mobotix, D-Link, TRENDnet, EZVIZ, Swann, GeoVision,
Milesight, the Android "IP Webcam" app, ESP32-CAM/ESPHome, plus a list of
common generic paths. They live in [`wanicam/brands.py`](wanicam/brands.py);
adding a brand is one dictionary entry.

## Install and run

1. **Install Python 3.8 or newer** from <https://www.python.org/downloads/>
   (on Windows, tick "Add python.exe to PATH"). WaniCam has no other Python
   dependencies.
2. **Install ffmpeg** (needed for RTSP, RTMP, HLS and USB cameras; MJPEG and
   snapshot cameras work without it):
   - Windows: `winget install Gyan.FFmpeg`
   - macOS: `brew install ffmpeg`
   - Debian/Ubuntu: `sudo apt install ffmpeg`
3. **Start WaniCam**:
   - Windows: double-click `run.bat`
   - macOS/Linux: `./run.sh`
   - or from any terminal in this folder: `python3 -m wanicam`

Your browser opens the dashboard. Stop WaniCam with Ctrl+C in its window.

You can also install it as a command with `pip install .` and then run
`wanicam`.

### Options

| Option | What it does |
| --- | --- |
| `--port 8765` | Port for the dashboard. |
| `--host 0.0.0.0` | Let phones and other computers on your network open the dashboard. Use it with `--password`. |
| `--password SECRET` | Ask for this password before showing the dashboard (any username). Also settable with the `WANICAM_PASSWORD` environment variable. |
| `--data-dir PATH` | Where saved cameras are kept (default `~/.wanicam`). |
| `--no-browser` | Don't open a browser window on start. |

## Using it

1. **Discover**: click **Scan**. The list shows each likely camera, its guessed
   brand and how it was found. Click **Set up** on one.
2. **Find streams**: enter the camera's username and password and click
   **Find streams**. WaniCam asks the camera over ONVIF, then tries the known
   URL formats for its brand, and lists every stream that actually plays, with
   its resolution and codec. Click **Add** on the one you want (the sub stream
   is lighter for a grid of many cameras).
3. **Cameras**: all saved cameras play in a grid. Change the layout, frame rate
   and size at the top. **Expand** opens one full screen, **Snapshot** saves a
   JPEG.

If you already know the stream address, use **Add camera → Enter a stream URL**
and click **Test** before saving.

### Camera-specific tips

- **TP-Link Tapo, Eufy, Wyze, Reolink battery cams**: RTSP/ONVIF is off by
  default or needs a separate "camera account". Turn it on in the brand's app
  first, and use that account here.
- **Hikvision**: ONVIF users are a separate list in the camera's web page
  (Configuration → Network → Integration Protocol). Your normal login still
  works for RTSP, so "Find streams" falls back to it.
- **UniFi Protect**: enable RTSP for the camera in Protect and paste the RTSPS
  link into "Enter a stream URL".
- **Cloud-only cameras** (Ring, Nest, Blink, Arlo, most Wyze without RTSP
  firmware) don't offer a local stream, so no local viewer can show them.

## Privacy and security

- The dashboard only listens on `127.0.0.1` (this computer) unless you pass
  `--host`.
- Camera passwords are stored in `~/.wanicam/cameras.json`, readable only by
  your user account. They're never sent to the browser. The server adds them
  when it connects to the camera.
- Requests from other websites are refused (Host and Origin checks), so a web
  page you visit can't read your cameras through WaniCam.
- Nothing is sent anywhere except to your cameras.

## How it works

```
camera ──RTSP/HTTP──▶ WaniCam (Python + ffmpeg) ──MJPEG over HTTP──▶ browser <img>
```

One ffmpeg process per camera is shared by every open viewer and stops 15
seconds after the last viewer leaves. MJPEG plays in every browser with no
plugins and adds very little delay.

| File | Purpose |
| --- | --- |
| `wanicam/discovery.py` | WS-Discovery, SSDP, mDNS and the port scan |
| `wanicam/onvif.py` | ONVIF client (WS-Security digest login, profiles, stream and snapshot URIs) |
| `wanicam/brands.py` | Stream URL formats per brand, and brand detection |
| `wanicam/streams.py` | ffmpeg relay, MJPEG/snapshot proxy, stream testing |
| `wanicam/server.py` | Web server and API |
| `wanicam/static/` | Dashboard |
| `tests/fake_camera.py` | A simulated camera (RTSP, ONVIF, MJPEG, snapshot, WS-Discovery) for testing without hardware |

## Tests

```
python3 -m unittest discover tests -v
```

The integration tests start `tests/fake_camera.py` and need ffmpeg; they skip
themselves without it. You can also run the simulated camera on its own and
point WaniCam at it:

```
python3 tests/fake_camera.py --user admin --password secret
# then in WaniCam: host 127.0.0.1, Advanced → RTSP port 8554, HTTP port 8080
```
