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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var listView: ListView
    private lateinit var adapter: LibraryAdapter
    private lateinit var statusLine: TextView
    private lateinit var progressBar: ProgressBar
    private var items: MutableList<LibraryItem> = mutableListOf()
    private var installing = false
    private var focusedIndex = 0  // row the DPAD is currently on

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
        }

        val header = TextView(this).apply {
            text = "App Updater"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(header)

        val sub = TextView(this).apply {
            text = "Browse the library and tap an app to download & install it directly on this device."
            textSize = 18f
            setTextColor(0xFF9AA7B4.toInt())
        }
        root.addView(sub)

        statusLine = TextView(this).apply {
            text = "Loading library…"
            textSize = 18f
            setTextColor(0xFF00C9A7.toInt())
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

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 20 }
            divider = null
            // Let the per-row buttons (bg_row_selector) drive the highlight;
            // hide the framework's default selector overlay so nothing double-draws.
            selector = ColorDrawable(0x00000000)
            // TV remote: keep the list itself unfocusable and let row buttons own focus.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // DPAD must hop between row buttons, not scroll the list. ListView's own
            // arrow handling scrolls instead of moving focus, so intercept arrows here.
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                            val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                            val target = focusedIndex + dir
                            if (target in 0 until items.size) focusRow(target)
                            true  // consume so the list doesn't also scroll
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (focusedIndex < items.size) activateRow(focusedIndex)
                            true
                        }
                        else -> false
                    }
                } else false
            }
        }
        adapter = LibraryAdapter()
        listView.adapter = adapter
        root.addView(listView)

        val refreshBtn = Button(this).apply {
            text = "⟳ Refresh"
            textSize = 20f
            setOnClickListener { refresh() }
        }
        root.addView(refreshBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        setContentView(root)
    }

    // Shared install decision — called from each row's INSTALL/UPDATE button.
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

    // Move DPAD focus to a specific row's button (scrolling it into view if needed).
    private fun focusRow(target: Int) {
        focusedIndex = target.coerceIn(0, items.size - 1)
        // Ensure the target row is on screen before locating its button.
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        if (focusedIndex < first || focusedIndex > last) {
            listView.setSelectionFromTop(focusedIndex, 20)
        }
        listView.post {
            for (i in 0 until listView.childCount) {
                val row = listView.getChildAt(i) as? ViewGroup ?: continue
                val btn = row.getChildAt(row.childCount - 1) as? Button ?: continue
                if (btn.tag is Int && btn.tag == focusedIndex) {
                    btn.requestFocus()
                    return@post
                }
            }
        }
    }

    // Activate the currently-focused row (CENTER/OK/ENTER on the remote).
    private fun activateRow(pos: Int) {
        if (pos in items.indices) onAppChosen(items[pos])
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
                        // user may need to approve on-screen; re-evaluate states shortly
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
            kotlin.concurrent.thread {
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
                        // Give the first row's button visible focus so the DPAD
                        // highlight is obvious from launch (TV-friendly).
                        if (listView.count > 0) {
                            focusRow(0)
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
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 28, 24)
                // Passive container — the Button owns focus so DPAD has one clear target.
                setBackgroundColor(0xFF141A22.toInt())
                dividerPadding = 24
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
                isClickable = false
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
                isClickable = false
                isFocusable = false
            }
            val installedTV = TextView(this@MainActivity).apply {
                text = item.installedVersion ?: ""
                textSize = 15f
                setTextColor(0xFF6F7C89.toInt())
                isClickable = false
                isFocusable = false
            }
            left.addView(nameTV)
            left.addView(metaTV)
            left.addView(installedTV)
            row.addView(left)

            // Real focusable Button — the clear DPAD target. Triggered with OK/Select.
            val btn = Button(this@MainActivity).apply {
                text = statusBadgeText(item.state)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 16, 32, 16)
                setBackgroundResource(R.drawable.bg_row_selector)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                gravity = Gravity.CENTER
                minWidth = 220
                tag = pos
            }
            btn.setOnClickListener {
                focusedIndex = pos
                onAppChosen(item)
            }
            row.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            return row
        }
    }
}

// Holder for the install result listener — simple static bridge.
object InstallerListenerHolder {
    @Volatile var listener: Installer.Listener? = null
}
