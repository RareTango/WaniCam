package com.wanicam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wanicam.core.Device
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {
    private lateinit var nav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraStore.init(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        nav = findViewById(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cameras -> show(CamerasFragment())
                R.id.nav_discover -> show(DiscoverFragment())
                R.id.nav_add -> show(AddFragment())
            }
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.nav_cameras
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.content, f).commit()
    }

    /** Opens the Add tab, filled in from a discovered device or an existing camera. */
    fun openAdd(device: Device? = null, editId: String? = null) {
        val f = AddFragment().apply { arguments = AddFragment.args(device, editId) }
        nav.menu.findItem(R.id.nav_add).isChecked = true
        show(f)
    }

    fun openCameras() {
        nav.selectedItemId = R.id.nav_cameras
    }
}

object NetInfo {
    /** This phone's IPv4 networks as CIDRs, e.g. 192.168.1.0/24 (at least /24 so scans stay quick). */
    fun subnets(ctx: Context): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return lp.linkAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }.map {
            val prefix = maxOf(it.prefixLength, 24)
            val b = it.address.address
            val ip = (b[0].toLong() and 0xff shl 24) or (b[1].toLong() and 0xff shl 16) or
                (b[2].toLong() and 0xff shl 8) or (b[3].toLong() and 0xff)
            val net = ip and ((0xffffffffL shl (32 - prefix)) and 0xffffffffL)
            "${net shr 24 and 0xff}.${net shr 16 and 0xff}.${net shr 8 and 0xff}.${net and 0xff}/$prefix"
        }.distinct()
    }

    fun myIps(ctx: Context): Set<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptySet()
        return lp.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.toSet()
    }

    /** Android drops multicast packets unless an app holds this lock. */
    inline fun <T> withMulticast(ctx: Context, block: () -> T): T {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("wanicam-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}
