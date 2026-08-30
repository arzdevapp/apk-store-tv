package com.arzdev.appupdater

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var listView: ListView
    private lateinit var adapter: LibraryAdapter
    private lateinit var statusLine: TextView
    private lateinit var progressBar: ProgressBar
    private var items: MutableList<LibraryItem> = mutableListOf()
    private var installing = false

    private data class LibraryItem(
        val info: ApkInfo,
        var state: ItemState,
        var installedVersion: String? = null
    )

    private enum class ItemState { INSTALL, UPDATE, CURRENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // If user went to Settings to allow unknown sources, refresh on return
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            setBackgroundColor(0xFF0B0F14.toInt())
            isFocusable = false
        }

        val header = TextView(this).apply {
            text = "App Updater"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            isFocusable = false
        }
        root.addView(header)

        val sub = TextView(this).apply {
            text = "Browse the library and select an app to download & install it directly on this device."
            textSize = 18f
            setTextColor(0xFF9AA7B4.toInt())
            isFocusable = false
        }
        root.addView(sub)

        statusLine = TextView(this).apply {
            text = "Loading library…"
            textSize = 18f
            setTextColor(0xFF00C9A7.toInt())
            isFocusable = false
        }
        root.addView(statusLine)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            max = 1000
            visibility = View.GONE
        }
        root.addView(progressBar)

        // Canonical TV list: ListView OWNS focus so DPAD natively moves the row
        // selection and OK/Enter fires the item click. Rows are single focus targets;
        // the INSTALL badge is a non-focusable TextView so it never steals focus.
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 20 }
            divider = null
            // Hide the framework's default highlight; rows draw their own via the
            // bg_row_selector (state_focused / state_selected) when selected.
            selector = ColorDrawable(0x00000000)
            isFocusable = true
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Whole-row selection model: DPAD moves selection, OK triggers install.
            setOnItemClickListener { _, _, position, _ ->
                if (position in items.indices) onAppChosen(items[position])
            }
        }
        adapter = LibraryAdapter()
        listView.adapter = adapter
        root.addView(listView)

        val refreshBtn = TextView(this).apply {
            text = "⟳ Refresh"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(32, 16, 32, 16)
            setBackgroundResource(R.drawable.bg_row_selector)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { refresh() }
        }
        root.addView(refreshBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        setContentView(root)
    }

    // Shared install decision — called when a row is selected (OK/Enter on remote,
    // or touch tap). Prefers a fresh fetch of the install-source setting each time.
    private fun onAppChosen(item: LibraryItem) {
        if (installing) return
        when {
            !Installer.canRequestUnknownSources(this) -> {
                statusLine.text = "Please allow App Updater to install apps from this source."
                Installer.requestUnknownSources(this)
            }
            item.state == ItemState.CURRENT && item.installedVersion != null -> {
                statusLine.text = "${item.info.label} is already the latest version."
            }
            else -> startInstall(item.info)
        }
    }

    private fun startInstall(info: ApkInfo) {
        installing = true
        progressBar.visibility = View.VISIBLE
        statusLine.text = "Preparing ${info.label}…"

        InstallerListenerHolder.listener = object : Installer.Listener {
            override fun onProgress(fraction: Float, label: String) {
                mainHandler.post {
                    progressBar.progress = (fraction * 1000).toInt()
                    statusLine.text = label
                }
            }

            override fun onStatus(message: String) {
                mainHandler.post { statusLine.text = message }
            }

            override fun onDone(success: Boolean, message: String) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    installing = false
                    statusLine.text = if (success) "✓ $message" else "$message"
                    if (success) {
                        // Re-evaluate states shortly so the badge flips to OK/UPDATE.
                        mainHandler.postDelayed({ refresh() }, 3000)
                    }
                }
            }
        }

        Installer.downloadAndInstall(this, info, InstallerListenerHolder.listener!!)
    }

    private fun refresh() {
        if (installing) return
        statusLine.text = "Loading library…"
        InstallerListenerHolder.listener = null
        mainHandler.post {
            thread(name = "appupdater-lib") {
                try {
                    val lib = Api.fetchLibrary()
                    val pm = packageManager
                    val newItems = lib.map { info ->
                        var installedVersion: Long? = null
                        var installedVName: String? = null
                        if (info.packageName != null) {
                            try {
                                val pi = pm.getPackageInfo(info.packageName, 0)
                                installedVersion = pi.longVersionCode
                                installedVName = pi.versionName
                            } catch (_: PackageManager.NameNotFoundException) {
                                installedVersion = null
                            }
                        }
                        val state = when {
                            installedVersion == null -> ItemState.INSTALL
                            info.versionCode != null && installedVersion < info.versionCode -> ItemState.UPDATE
                            else -> ItemState.CURRENT
                        }
                        val vstr = when (state) {
                            ItemState.CURRENT -> "installed ${installedVName ?: ""}"
                            ItemState.UPDATE -> "installed ${installedVName ?: ""} → library ${info.versionName ?: ""}"
                            ItemState.INSTALL -> "not installed"
                        }
                        LibraryItem(info, state, vstr)
                    }
                    mainHandler.post {
                        items.clear()
                        items.addAll(newItems)
                        adapter.notifyDataSetChanged()
                        statusLine.text = "${items.size} apps in library"
                        // Focus the first row so the teal highlight is visible from launch.
                        if (listView.count > 0) {
                            listView.requestFocus()
                            listView.setSelection(0)
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        statusLine.text = "Failed to load library: ${e.message}"
                    }
                }
            }
        }
    }

    private fun statusBadgeText(state: ItemState): String = when (state) {
        ItemState.INSTALL -> "INSTALL"
        ItemState.UPDATE -> "UPDATE"
        ItemState.CURRENT -> "OK"
    }

    private inner class LibraryAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[pos]
            // Single focusable/clickable row = the INSTALL target. TextView children are
            // non-focusable so the whole row is one clear DPAD target.
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 28, 24)
                setBackgroundResource(R.drawable.bg_row_selector)
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // CRITICAL (v1.4.0): a clickable row NEEDS its own OnClickListener.
                // On ListView, if the item view is isClickable=true but has no listener,
                // the click (DPAD-CENTER performClick OR touch) is swallowed at the row
                // and NEVER reaches setOnItemClickListener → install never triggers.
                setOnClickListener { onAppChosen(item) }
            }

            val left = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTV = TextView(this@MainActivity).apply {
                text = item.info.label
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                isFocusable = false
            }
            val metaTV = TextView(this@MainActivity).apply {
                text = buildString {
                    if (item.info.versionName != null) append("v${item.info.versionName}")
                    if (item.info.size > 0) append("  ·  ${Api.formatSize(item.info.size)}")
                    if (item.info.packageName != null) append("  ·  ${item.info.packageName}")
                }
                textSize = 16f
                setTextColor(0xFF9AA7B4.toInt())
                isFocusable = false
            }
            val installedTV = TextView(this@MainActivity).apply {
                text = item.installedVersion ?: ""
                textSize = 15f
                setTextColor(0xFF6F7C89.toInt())
                isFocusable = false
            }
            left.addView(nameTV)
            left.addView(metaTV)
            left.addView(installedTV)
            row.addView(left)

            // Non-focusable INSTALL/UPDATE/OK badge — a label, NOT a separate click target.
            val badge = TextView(this@MainActivity).apply {
                text = statusBadgeText(item.state)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 16, 32, 16)
                setBackgroundColor(0xFF00C9A7.toInt())
                gravity = Gravity.CENTER
                isFocusable = false
                isClickable = false
            }
            row.addView(badge, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            return row
        }
    }
}

// Holder for the install result listener — simple static bridge.
object InstallerListenerHolder {
    @Volatile var listener: Installer.Listener? = null
    // Set true once an install result has been delivered (broadcast OR poll), so the
    // two completion paths never double-fire or contradict each other.
    private val completion = java.util.concurrent.atomic.AtomicBoolean(false)
    fun newCompletion(): java.util.concurrent.atomic.AtomicBoolean { completion.set(false); return completion }
    fun markCompleted() { completion.set(true) }
}