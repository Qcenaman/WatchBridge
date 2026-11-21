package com.qcenaman.watchbridge

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var apps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)
        root.addView(button("Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Allow Bluetooth") { askPermissions() })
        root.addView(button("Restart watch link") { BridgeService.instance?.restartBle() })
        root.addView(TextView(this).apply {
            text = "\nApps sent to the watch (untick to mute):"
            textSize = 16f
        })
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(apps)
        setContentView(ScrollView(this).apply { addView(root) })
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        BridgeService.changed = { runOnUiThread { refresh() } }
        refresh()
        fillApps()
    }

    override fun onPause() {
        BridgeService.changed = null
        super.onPause()
    }

    private fun refresh() {
        val access = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, BridgeService::class.java).flattenToString()) == true
        val service = BridgeService.instance
        status.text = when {
            !access -> "Notification access is off. Turn it on for WatchBridge."
            !hasBluetooth() -> "Bluetooth permission needed."
            service == null -> "Starting…"
            service.connected -> "Watch connected ✓"
            else -> "Waiting for the watch. Open WatchBridge on the watch."
        }
    }

    private fun fillApps() {
        apps.removeAllViews()
        val muted = Prefs.muted(this)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        for ((pkg, label) in installed) {
            apps.addView(CheckBox(this).apply {
                text = label
                isChecked = pkg !in muted
                setOnCheckedChangeListener { _, on -> Prefs.setMuted(this@MainActivity, pkg, !on) }
            })
        }
    }

    private fun hasBluetooth(): Boolean = Build.VERSION.SDK_INT < 31 ||
        (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    private fun askPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        BridgeService.instance?.restartBle()
        refresh()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { action() }
    }
}
