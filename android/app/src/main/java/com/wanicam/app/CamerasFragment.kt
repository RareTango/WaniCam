package com.wanicam.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CamerasFragment : Fragment(R.layout.fragment_cameras) {
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Adapter()
    private var started = false

    private val prefs get() = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun columns(): Int {
        val saved = prefs.getInt("columns", 0)
        if (saved > 0) return saved
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 1
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        grid = view.findViewById(R.id.grid)
        empty = view.findViewById(R.id.empty)
        grid.layoutManager = GridLayoutManager(requireContext(), columns())
        grid.adapter = adapter
        grid.setItemViewCacheSize(0)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.cameras, menu)
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId != R.id.action_columns) return false
                val next = columns() % 3 + 1
                prefs.edit().putInt("columns", next).apply()
                (grid.layoutManager as GridLayoutManager).spanCount = next
                adapter.notifyDataSetChanged()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onStart() {
        super.onStart()
        started = true
        refresh()
    }

    private fun refresh() {
        adapter.stopAll()
        adapter.cameras = CameraStore.all()
        empty.visibility = if (adapter.cameras.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        // Release every player so the phone isn't pulling video in the background.
        started = false
        adapter.stopAll()
    }

    private fun showMenu(cam: Camera) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(cam.name)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.remove))) { _, which ->
                when (which) {
                    0 -> (activity as? MainActivity)?.openAdd(editId = cam.id)
                    1 -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Remove \"${cam.name}\" from WaniCam? This doesn't change the camera.")
                        .setPositiveButton(R.string.remove) { _, _ ->
                            CameraStore.delete(cam.id)
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val frame: AspectRatioFrameLayout = v.findViewById(R.id.frame)
        val video: FrameLayout = v.findViewById(R.id.video)
        val status: TextView = v.findViewById(R.id.status)
        val name: TextView = v.findViewById(R.id.name)
        val kind: TextView = v.findViewById(R.id.kind)
        var live: LiveView? = null
    }

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        var cameras: List<Camera> = emptyList()
        private val bound = mutableSetOf<Holder>()

        override fun getItemCount() = cameras.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_camera, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val cam = cameras[position]
            h.live?.stop()
            h.name.text = cam.name
            h.kind.text = cam.kindLabel
            h.frame.setAspectRatio(16f / 9f)
            h.status.visibility = View.VISIBLE
            h.status.text = ""
            h.itemView.setOnClickListener {
                startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra("id", cam.id))
            }
            h.itemView.setOnLongClickListener { showMenu(cam); true }
            if (!started) return
            h.live = LiveView(requireContext(), cam, h.video, muted = true,
                onStatus = { msg ->
                    h.status.visibility = if (msg == null) View.GONE else View.VISIBLE
                    h.status.text = msg ?: ""
                },
                onAspect = { a -> if (a > 0.3f && a < 5f) h.frame.setAspectRatio(a) }).also { it.start() }
            bound += h
        }

        override fun onViewRecycled(h: Holder) {
            h.live?.stop()
            h.live = null
            bound -= h
        }

        fun stopAll() {
            bound.forEach { it.live?.stop(); it.live = null }
            bound.clear()
        }
    }
}
