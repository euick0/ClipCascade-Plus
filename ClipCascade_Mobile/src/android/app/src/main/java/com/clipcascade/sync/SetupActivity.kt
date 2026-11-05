package com.clipcascade.sync

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

/** Live checklist for what the service needs on this phone, plus status and the exportable log. */
class SetupActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(list)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        heading("Status")
        val state = SyncService.currentState
        note("Service: " + if (SyncService.isRunning) "running" else "not running")
        note("Connection: " + (state?.javaClass?.simpleName ?: "none"))
        note("Copy capture: " + (SyncService.activeCapture ?: "none"))
        action("Reconnect now") { SyncService.send(this, SyncService.ACTION_RECONNECT) }

        heading("Keep it running")
        if (Build.VERSION.SDK_INT >= 33) {
            item(
                "Notifications",
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "Shows the connection status.",
                "Allow" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
            )
        }
        item(
            "Battery: unrestricted",
            SyncService.isBatteryExempt(this),
            "Android refuses background restarts without this.",
            "Allow" to {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
        )
        note(
            "Samsung: go to Settings > Battery > Background usage limits. Add ClipCascade Plus to " +
                "\"Never auto sleeping apps\", and check it isn't under \"Sleeping apps\" or \"Deep sleeping apps\". " +
                "The app can't check this for you.",
        )
        action("Open Samsung battery settings") { openSamsungBattery() }

        heading("Automatic copy capture")
        val shizukuInstalled = runCatching { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        item("Shizuku installed", shizukuInstalled, "Best capture method.")
        item("Shizuku running", shizukuRunning, "Without root, restart it from the Shizuku app after every reboot.")
        item(
            "Shizuku access",
            ShizukuSource.isGranted(),
            "Lets the app listen for copies.",
            ("Allow" to {
                runCatching { Shizuku.requestPermission(2) }
                    .onFailure { toast("Start Shizuku first") }
                Unit
            }).takeIf { shizukuRunning },
        )
        val writeSecure = ImeSwitchSource.hasSecureSettings(this)
        val readLogs = ImeSwitchSource.hasReadLogs(this)
        item("WRITE_SECURE_SETTINGS", writeSecure, "Needed for the keyboard-switch fallback.")
        item("READ_LOGS", readLogs, "Tells the fallback when something was copied. Works only once the app has been opened since boot.")
        if (!writeSecure || !readLogs) {
            note("One-time grant from a computer, if Shizuku isn't available:")
            val commands = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n" +
                "adb shell pm grant $packageName android.permission.READ_LOGS"
            mono(commands)
            action("Copy ADB commands") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", commands))
                toast("Copied")
            }
            note("The main screen's Shizuku grant also works, and may restart the app.")
        }

        heading("Log")
        note("Shows connection and capture events. Never includes clip content or passwords.")
        action("Share log") {
            open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SyncLog.read()), "Share log"))
        }
        mono(SyncLog.read().takeLast(4_000).ifEmpty { "Empty" })
    }

    private fun openSamsungBattery() {
        val attempts = listOf(
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        toast("Couldn't open that screen")
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Couldn't open that screen") }
    }

    private fun item(title: String, ok: Boolean, detail: String, button: Pair<String, () -> Unit>? = null) {
        list.addView(
            TextView(this).apply {
                text = (if (ok) "✅ " else "⬜ ") + title
                textSize = 16f
                setPadding(0, dp(10), 0, 0)
            },
        )
        note(detail)
        if (!ok && button != null) action(button.first, button.second)
    }

    private fun heading(text: String) {
        list.addView(
            TextView(this).apply {
                this.text = text
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(4))
            },
        )
    }

    private fun note(text: String) {
        list.addView(TextView(this).apply { this.text = text })
    }

    private fun mono(text: String) {
        list.addView(
            TextView(this).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, dp(6))
            },
        )
    }

    private fun action(label: String, onClick: () -> Unit) {
        list.addView(
            Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener {
                    onClick()
                    postDelayed({ render() }, 500)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
