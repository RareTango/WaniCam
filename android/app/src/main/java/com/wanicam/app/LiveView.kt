package com.wanicam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.wanicam.core.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * Shows one camera inside [container]. Video streams (RTSP, HLS, RTMP) play through Media3
 * ExoPlayer; MJPEG and snapshot cameras are decoded frame by frame. [onStatus] gets a message to
 * show over the video, or null once frames are showing. [onAspect] reports the video's shape.
 */
class LiveView(
    private val context: Context,
    private val camera: Camera,
    private val container: ViewGroup,
    private val muted: Boolean,
    private val onStatus: (String?) -> Unit,
    private val onAspect: (Float) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var imageView: ImageView? = null
    private var scope: CoroutineScope? = null
    private var call: Call? = null
    private var retries = 0
    private var stopped = true

    val isVideo get() = camera.kind in setOf("rtsp", "hls", "rtmp", "other")

    fun start() {
        if (!stopped) return
        stopped = false
        onStatus("Connecting…")
        if (isVideo) startPlayer() else startImages()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        call?.cancel()
        scope?.cancel()
        scope = null
        container.removeAllViews()
        playerView = null
        imageView = null
    }

    // ------------------------------------------------------------ video

    @OptIn(UnstableApi::class)
    private fun mediaSource(): MediaSource {
        val item = MediaItem.fromUri(if (camera.kind == "rtsp") Net.withCreds(camera.url, camera.user, camera.password) else camera.url)
        return when (camera.kind) {
            "rtsp" -> RtspMediaSource.Factory()
                .setForceUseRtpTcp(camera.transport != "udp")
                .setTimeoutMs(10_000)
                .createMediaSource(item)
            "rtmp" -> ProgressiveMediaSource.Factory(RtmpDataSource.Factory()).createMediaSource(item)
            else -> DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.client(camera.user, camera.password, 20)))
                .createMediaSource(item)
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlayer() {
        // Small buffers: this is live video, so low delay matters more than smoothness.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 4000, 500, 500).build()
        val p = ExoPlayer.Builder(context).setLoadControl(load).build()
        player = p
        val pv = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF000000.toInt())
            this.player = p
        }
        playerView = pv
        container.removeAllViews()
        container.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.volume = if (muted) 0f else 1f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { retries = 0; onStatus(null) }
                    Player.STATE_ENDED -> retry("Stream ended")
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) onAspect(size.width * size.pixelWidthHeightRatio / size.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = explain(error)
                if (msg.startsWith("Camera rejected")) {
                    // Retrying a wrong password can lock the camera account.
                    onStatus("$msg. Long-press the camera to edit its login.")
                } else retry(msg)
            }
        })
        p.setMediaSource(mediaSource())
        p.playWhenReady = true
        p.prepare()
    }

    private fun retry(msg: String) {
        if (stopped) return
        retries++
        val wait = (2000L shl minOf(retries, 4)).coerceAtMost(30_000)
        onStatus("$msg\nRetrying in ${wait / 1000}s…")
        main.postDelayed({
            if (stopped) return@postDelayed
            player?.let { it.setMediaSource(mediaSource()); it.prepare() }
            onStatus("Connecting…")
        }, wait)
    }

    @OptIn(UnstableApi::class)
    private fun explain(e: PlaybackException): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) {
                if (t.responseCode == 401 || t.responseCode == 403) return "Camera rejected the username or password"
                if (t.responseCode == 404) return "Stream not found on the camera (404)"
            }
            val m = t.message ?: ""
            if (m.contains("401") || m.contains("Unauthorized", true)) return "Camera rejected the username or password"
            if (m.contains("404")) return "Stream not found on the camera (404)"
            t = t.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Can't reach the camera"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Timed out connecting to the camera"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This phone can't decode the camera's video format (try the camera's sub stream or H.264)"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Unsupported stream format"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }
        }
    }

    // ------------------------------------------------------------ MJPEG and snapshots

    private fun startImages() {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        imageView = iv
        container.removeAllViews()
        container.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { if (camera.kind == "mjpeg") mjpegLoop() else snapshotLoop() }
    }

    private fun client() = Net.client(camera.user, camera.password, 20)

    private suspend fun show(jpeg: ByteArray) {
        val bmp: Bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        withContext(Dispatchers.Main) {
            if (stopped) return@withContext
            imageView?.setImageBitmap(bmp)
            onAspect(bmp.width.toFloat() / bmp.height)
            onStatus(null)
        }
    }

    private suspend fun status(msg: String?) = withContext(Dispatchers.Main) { if (!stopped) onStatus(msg) }

    private suspend fun CoroutineScope.mjpegLoop() {
        var backoff = 2000L
        while (isActive) {
            try {
                val stripped = Net.stripCreds(camera.url)
                val c = client().newCall(Request.Builder().url(stripped.url).build())
                call = c
                c.execute().use { r ->
                    if (r.code == 401 || r.code == 403) {
                        status("Camera rejected the username or password. Long-press the camera to edit its login.")
                        return
                    }
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val input = r.body!!.byteStream()
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(32 * 1024)
                    var scanFrom = 0
                    var start = -1
                    while (isActive) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        val data = buf.toByteArray()
                        var i = maxOf(scanFrom, 0)
                        var lastEnd = -1
                        while (i < data.size - 1) {
                            if (data[i] == 0xFF.toByte()) {
                                val next = data[i + 1]
                                if (start < 0 && next == 0xD8.toByte()) start = i
                                else if (start >= 0 && next == 0xD9.toByte()) {
                                    show(data.copyOfRange(start, i + 2))
                                    backoff = 2000L
                                    lastEnd = i + 2
                                    start = -1
                                }
                            }
                            i++
                        }
                        // Keep only the unfinished frame.
                        val keepFrom = if (start >= 0) start else if (lastEnd >= 0) lastEnd else maxOf(0, data.size - 1)
                        buf.reset()
                        buf.write(data, keepFrom, data.size - keepFrom)
                        if (start >= 0) start -= keepFrom
                        scanFrom = maxOf(0, buf.size() - 1)
                        if (buf.size() > 8_000_000) { buf.reset(); start = -1; scanFrom = 0 }
                    }
                }
                status("Stream ended. Reconnecting…")
            } catch (e: Exception) {
                if (!isActive) return
                status("${e.message ?: "Can't reach the camera"}\nRetrying in ${backoff / 1000}s…")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun CoroutineScope.snapshotLoop() {
        var backoff = 2000L
        val http = client()
        while (isActive) {
            try {
                val stripped = Net.stripCreds(camera.url)
                val c = http.newCall(Request.Builder().url(stripped.url).build())
                call = c
                c.execute().use { r ->
                    if (r.code == 401 || r.code == 403) {
                        status("Camera rejected the username or password. Long-press the camera to edit its login.")
                        return
                    }
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    show(r.body!!.bytes())
                }
                backoff = 2000L
                delay(1000)
            } catch (e: Exception) {
                if (!isActive) return
                status("${e.message ?: "Can't reach the camera"}\nRetrying in ${backoff / 1000}s…")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }
}
