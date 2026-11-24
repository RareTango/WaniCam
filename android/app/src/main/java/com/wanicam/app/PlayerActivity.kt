package com.wanicam.app

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** One camera, full screen, with sound. Tap to show or hide the name. */
class PlayerActivity : AppCompatActivity() {
    private var live: LiveView? = null
    private var camera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraStore.init(this)
        setContentView(R.layout.activity_player)
        camera = CameraStore.get(intent.getStringExtra("id"))
        if (camera == null) { finish(); return }
        val title = findViewById<TextView>(R.id.title)
        title.text = camera!!.name
        findViewById<View>(R.id.root).setOnClickListener {
            title.visibility = if (title.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        val cam = camera ?: return
        val status = findViewById<TextView>(R.id.status)
        live = LiveView(this, cam, findViewById<FrameLayout>(R.id.video), muted = false, onStatus = { msg ->
            status.visibility = if (msg == null) View.GONE else View.VISIBLE
            status.text = msg ?: ""
        }).also { it.start() }
    }

    override fun onStop() {
        super.onStop()
        live?.stop()
        live = null
    }
}
