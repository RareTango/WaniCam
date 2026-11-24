package com.wanicam.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanicam.core.Brands
import com.wanicam.core.Device
import com.wanicam.core.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverFragment : Fragment(R.layout.fragment_discover) {
    companion object {
        // Kept while the app runs so switching tabs doesn't lose the last scan.
        private var last: Discovery.Result? = null
    }

    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var scan: Button
    private val adapter = Adapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val subnets = view.findViewById<EditText>(R.id.subnets)
        val portScan = view.findViewById<CheckBox>(R.id.port_scan)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        scan = view.findViewById(R.id.scan)
        val list = view.findViewById<RecyclerView>(R.id.devices)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val mine = NetInfo.subnets(requireContext())
        subnets.setText(mine.joinToString(", "))
        if (mine.isEmpty()) status.text = "Connect to the same Wi-Fi network as your cameras, then scan."
        last?.let { show(it) }

        scan.setOnClickListener {
            val nets = subnets.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (n in nets) {
                val bits = n.substringAfter('/', "32").toIntOrNull()
                if (!Discovery.isPrivate(n)) { status.text = "$n isn't a private (home or office) network. WaniCam only scans local networks."; return@setOnClickListener }
                if (bits == null || bits < 20) { status.text = "$n is too large. Use /20 or smaller, such as 192.168.1.0/24."; return@setOnClickListener }
            }
            startScan(nets, portScan.isChecked)
        }
    }

    private fun startScan(nets: List<String>, doScan: Boolean) {
        scan.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Scanning… this takes 10 to 60 seconds."
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    NetInfo.withMulticast(ctx) { Discovery.discoverAll(nets, NetInfo.myIps(ctx), doScan) }
                }
            } catch (e: Exception) {
                status.text = "Scan failed: ${e.message}"
                null
            }
            scan.isEnabled = true
            progress.visibility = View.GONE
            if (result != null) {
                last = result
                show(result)
            }
        }
    }

    private fun show(r: Discovery.Result) {
        adapter.devices = r.devices
        adapter.notifyDataSetChanged()
        val errs = r.errors.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        status.text = "Found ${r.devices.size} possible camera(s)" +
            (if (r.subnets.isNotEmpty()) " on ${r.subnets.joinToString(", ")}" else "") + "." +
            (if (errs.isNotEmpty()) " Some methods failed: $errs" else "") +
            (if (r.devices.isEmpty()) "\n\nNo luck? Make sure the phone is on the cameras' Wi-Fi, or add the camera by address." else "")
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val ip: TextView = v.findViewById(R.id.ip)
        val brand: TextView = v.findViewById(R.id.brand)
        val detail: TextView = v.findViewById(R.id.detail)
        val setup: Button = v.findViewById(R.id.setup)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var devices: List<Device> = emptyList()
        override fun getItemCount() = devices.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val d = devices[position]
            val saved = CameraStore.all().any { it.host == d.ip }
            h.ip.text = d.ip + if (saved) "  ·  already added" else ""
            h.brand.text = (if (d.brand == "generic") "Unknown brand" else Brands.get(d.brand).label) +
                (if (d.onvif) "  ·  ONVIF" else "")
            h.detail.text = listOf(
                d.summary,
                "Found by " + d.sources.joinToString(", "),
                if (d.openPorts.isNotEmpty()) "Open ports " + d.openPorts.joinToString(", ") else "",
                d.note ?: "",
            ).filter { it.isNotEmpty() }.joinToString("\n")
            h.setup.setOnClickListener { (activity as? MainActivity)?.openAdd(d) }
        }
    }
}
