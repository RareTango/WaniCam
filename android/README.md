# WaniCam for Android

The same camera finder and viewer as the desktop app, as an Android app
(Android 8.0 or newer).

## Install

1. On your phone, open the repository's
   [**android-latest** release](https://github.com/RareTango/WaniCam/releases/tag/android-latest)
   and tap **WaniCam.apk**.
2. When Android asks, allow your browser to install apps, then tap **Install**.
3. Connect the phone to the same Wi-Fi network as your cameras.

Each new build installs over the previous one, and your saved cameras are kept.

## What it does

- **Discover** finds cameras on your Wi-Fi with ONVIF WS-Discovery, mDNS/Bonjour,
  UPnP/SSDP and a scan of common camera ports. It guesses the brand from what
  the camera announces. No logins are attempted during discovery.
- **Add camera → Find automatically** asks the camera over ONVIF for its
  streams using the login you enter, then tries the known URL formats for its
  brand (the same 26-brand table as the desktop app). Each address is checked
  before it is offered. It stops after the first rejected login, so the camera
  doesn't lock your account. It never guesses passwords.
- **Add camera → Enter URL** takes any RTSP, HLS (.m3u8), RTMP, HTTP MJPEG or
  JPEG snapshot address, with a **Test** button.
- **Cameras** shows every saved camera live in a grid (1, 2 or 3 columns, from
  the grid icon). Tap a camera for full screen with sound; long-press to edit
  or remove it.

Playback uses Android's Media3 player (RTSP over TCP or UDP, HLS, RTMP).
H.265 cameras play on phones with an H.265 decoder, which most phones from
the last several years have. If one doesn't, use the camera's H.264 sub stream.
RTSPS (encrypted RTSP) isn't supported by the player; for UniFi Protect use the
plain `rtsp://…:7447/…` link.

Camera passwords are encrypted with a key kept in the Android Keystore and
are excluded from backups.

## Build it yourself

GitHub Actions builds the APK on every push (`.github/workflows/android.yml`),
runs the tests, and publishes the result to the `android-latest` release.

Locally, with Android Studio or the Android SDK installed:

```
cd android
./gradlew :core:test :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`core/` is plain Kotlin (discovery, ONVIF, RTSP checks, brand URL formats) and
its tests run on any JVM. Some tests start the Python simulated camera in
`tests/fake_camera.py`, which needs python3 and ffmpeg; they're skipped
otherwise. `app/` is the Android UI.

`app/debug.keystore` is a throwaway debug signing key kept in the repository
so that every build can update the last one. Don't use it to publish to an
app store.
