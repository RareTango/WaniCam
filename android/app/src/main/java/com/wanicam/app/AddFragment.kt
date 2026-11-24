package com.wanicam.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.wanicam.core.Brands
import com.wanicam.core.Device
import com.wanicam.core.FoundStream
import com.wanicam.core.Net
import com.wanicam.core.ProbeResult
import com.wanicam.core.Prober
import com.wanicam.core.RtspProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddFragment : Fragment(R.layout.fragment_add) {
    companion object {
        fun args(d: Device?, editId: String?) = bundleOf(
            "host" to d?.ip, "brand" to d?.brand, "rtsp_port" to d?.rtspPort, "http_port" to d?.httpPort,
            "xaddrs" to d?.onvifXaddrs?.toTypedArray(), "name" to (d?.name ?: d?.model), "edit" to editId,
        )

        private val KINDS = listOf("" to "Detect from URL", "rtsp" to "RTSP", "mjpeg" to "HTTP MJPEG stream",
            "snapshot" to "HTTP JPEG snapshot (refreshed)", "hls" to "HLS (.m3u8)", "rtmp" to "RTMP")
        private val TRANSPORTS = listOf("tcp" to "TCP (most reliable)", "udp" to "UDP")
    }

    private val brandIds = listOf("auto") + Brands.all.map { it.id }.filter { it != "generic" }
    private var editing: Camera? = null

    private fun View.text(id: Int) = findViewById<EditText>(id).text.toString().trim()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val mode = view.findViewById<MaterialButtonToggleGroup>(R.id.mode)
        val auto = view.findViewById<View>(R.id.auto_section)
        val manual = view.findViewById<View>(R.id.manual_section)
        mode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            auto.visibility = if (id == R.id.mode_auto) View.VISIBLE else View.GONE
            manual.visibility = if (id == R.id.mode_manual) View.VISIBLE else View.GONE
        }

        val brand = view.findViewById<Spinner>(R.id.brand)
        brand.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("Detect automatically") + brandIds.drop(1).map { Brands.get(it).label })
        val kind = view.findViewById<Spinner>(R.id.kind)
        kind.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, KINDS.map { it.second })
        val transport = view.findViewById<Spinner>(R.id.transport)
        transport.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, TRANSPORTS.map { it.second })

        // Prefill from a discovered device.
        val a = arguments ?: Bundle()
        a.getString("host")?.let { view.findViewById<EditText>(R.id.host).setText(it) }
        a.getString("brand")?.let { b -> brandIds.indexOf(b).takeIf { it > 0 }?.let { brand.setSelection(it) } }
        val rp = a.get("rtsp_port") as? Int
        val hp = a.get("http_port") as? Int
        if (rp != null && rp != 554) view.findViewById<EditText>(R.id.rtsp_port).setText(rp.toString())
        if (hp != null && hp != 80) view.findViewById<EditText>(R.id.http_port).setText(hp.toString())
        val xaddrs = a.getStringArray("xaddrs")?.toList() ?: emptyList()
        val suggestedName = a.getString("name")

        // Editing an existing camera opens the URL form.
        editing = CameraStore.get(a.getString("edit"))
        editing?.let { c ->
            mode.check(R.id.mode_manual)
            view.findViewById<EditText>(R.id.url).setText(c.url)
            view.findViewById<EditText>(R.id.cam_name).setText(c.name)
            view.findViewById<EditText>(R.id.m_user).setText(c.user)
            view.findViewById<TextInputLayout>(R.id.m_password_layout).helperText =
                if (c.password.isNotEmpty()) "Saved. Leave blank to keep it." else null
            kind.setSelection(KINDS.indexOfFirst { it.first == c.kind }.coerceAtLeast(0))
            transport.setSelection(TRANSPORTS.indexOfFirst { it.first == c.transport }.coerceAtLeast(0))
            view.findViewById<Button>(R.id.save).text = "Save changes"
        }

        // ---- Find automatically
        val find = view.findViewById<Button>(R.id.find)
        val autoStatus = view.findViewById<TextView>(R.id.auto_status)
        val results = view.findViewById<LinearLayout>(R.id.results)
        find.setOnClickListener {
            val host = view.text(R.id.host)
            if (host.isEmpty()) { autoStatus.text = "Enter the camera's address."; return@setOnClickListener }
            val user = view.text(R.id.user)
            val pass = view.findViewById<EditText>(R.id.password).text.toString()
            val brandId = brandIds[brand.selectedItemPosition]
            find.isEnabled = false
            results.removeAllViews()
            autoStatus.text = "Asking the camera for its streams…"
            viewLifecycleOwner.lifecycleScope.launch {
                val r = try {
                    Prober.probe(host, brandId, user, pass,
                        view.text(R.id.rtsp_port).toIntOrNull(), view.text(R.id.http_port).toIntOrNull(),
                        view.text(R.id.channel).toIntOrNull() ?: 1,
                        if (host == a.getString("host")) xaddrs else emptyList(),
                        view.findViewById<CheckBox>(R.id.use_onvif).isChecked,
                    ) { msg -> view.post { autoStatus.text = msg } }
                } catch (e: Exception) {
                    autoStatus.text = "Failed: ${e.message}"
                    null
                }
                find.isEnabled = true
                if (r != null) showProbe(r, user, pass, suggestedName, autoStatus, results)
            }
        }

        // ---- Enter URL
        val manualStatus = view.findViewById<TextView>(R.id.manual_status)
        fun manualCamera(): Camera? {
            val raw = view.text(R.id.url)
            if (raw.isEmpty()) { manualStatus.text = "Enter the stream URL."; return null }
            val s = Net.stripCreds(raw)
            val typed = KINDS[kind.selectedItemPosition].first
            var pass = view.findViewById<EditText>(R.id.m_password).text.toString().ifEmpty { s.password }
            if (pass.isEmpty()) pass = editing?.password ?: ""
            val host = Net.hostOf(s.url)
            return Camera(
                id = editing?.id ?: java.util.UUID.randomUUID().toString().take(10),
                name = view.text(R.id.cam_name).ifEmpty { host ?: "Camera" },
                kind = typed.ifEmpty { Camera.kindFor(s.url) },
                url = s.url,
                user = view.text(R.id.m_user).ifEmpty { s.user },
                password = pass,
                brand = editing?.brand, host = host,
                transport = TRANSPORTS[transport.selectedItemPosition].first,
            )
        }
        view.findViewById<Button>(R.id.test).setOnClickListener {
            val cam = manualCamera() ?: return@setOnClickListener
            manualStatus.text = "Testing…"
            viewLifecycleOwner.lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    when (cam.kind) {
                        "rtsp" -> RtspProbe.check(cam.url, cam.user, cam.password).let {
                            if (it.ok) "Works: ${it.codec?.uppercase()}" + (if (it.codec == "h265") " (needs an H.265-capable phone)" else "")
                            else it.error
                        }
                        "mjpeg", "snapshot" -> Prober.checkHttp(cam.url, cam.user, cam.password).let {
                            if (it.ok) "Works: ${it.type?.uppercase()}" else it.error
                        }
                        else -> "This type can't be tested before saving. Save it and open it from Cameras."
                    }
                }
                manualStatus.text = msg
            }
        }
        view.findViewById<Button>(R.id.save).setOnClickListener {
            val cam = manualCamera() ?: return@setOnClickListener
            CameraStore.upsert(cam)
            Toast.makeText(ctx, "Saved ${cam.name}", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openCameras()
        }
    }

    private fun showProbe(r: ProbeResult, user: String, pass: String, name: String?, status: TextView, out: LinearLayout) {
        val ctx = requireContext()
        val lines = mutableListOf<String>()
        val o = r.onvif
        if (o != null && o.ok) {
            lines += "ONVIF answered: " + listOfNotNull(o.manufacturer, o.model).joinToString(" ").ifEmpty { "camera" } +
                (o.firmware?.let { " (firmware $it)" } ?: "")
        } else if (o != null) {
            lines += if (o.authFailed) "ONVIF turned down this login. Some cameras (like Hikvision) keep a separate ONVIF user list. Tried the brand's URL formats instead."
            else "ONVIF didn't answer (it may be turned off on the camera). Tried the brand's URL formats instead."
        }
        if (r.streams.isEmpty()) {
            lines += r.authError?.let { "$it. Check the username and password set on the camera." }
                ?: "No working stream found after ${r.tried} checks. Make sure RTSP or ONVIF is turned on in the camera's settings " +
                "(Tapo, Eufy, Wyze and some others need it switched on in their app), or enter the URL from the camera's manual."
        } else {
            lines += "Tap Add on the stream you want. The sub stream uses less data for the grid."
        }
        status.text = lines.joinToString("\n\n")
        out.removeAllViews()
        val baseName = name ?: (Brands.get(r.brand).label.substringBefore(' ').takeIf { r.brand != "generic" } ?: "Camera") + " " + r.host
        for (s in r.streams) out.addView(row(ctx, s) { btn ->
            CameraStore.upsert(Camera(name = baseName + if (s.label.contains("sub", true)) " (sub)" else "",
                kind = s.kind, url = s.url, user = user, password = pass, brand = r.brand, host = r.host))
            btn.isEnabled = false
            btn.text = "Added"
        })
    }

    private fun row(ctx: android.content.Context, s: FoundStream, onAdd: (MaterialButton) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = TextView(ctx).apply {
            val desc = listOfNotNull(s.label, s.resolution, s.codec?.uppercase(), if (s.kind != "rtsp") s.kind.uppercase() else null)
                .joinToString(" · ")
            text = "$desc\n${s.url}"
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btn = MaterialButton(ctx).apply { text = getString(R.string.add) }
        btn.setOnClickListener { onAdd(btn) }
        row.addView(btn)
        return row
    }
}
