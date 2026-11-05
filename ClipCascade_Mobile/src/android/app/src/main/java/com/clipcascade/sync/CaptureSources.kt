package com.clipcascade.sync

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.clipcascade.BuildConfig
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a provider read from the clipboard, before any file contents are loaded. */
data class RawClip(
    val text: String?,
    val uris: List<String>,
    val isImage: Boolean,
    val sensitive: Boolean,
    val open: (String) -> InputStream?,
    val nameOf: (String) -> String?,
)

/** A way to learn about copies. CaptureManager runs the best one available. */
interface ClipboardSource {
    val name: String

    /** Can this run right now? Rechecked whenever availability may have changed. */
    fun isAvailable(): Boolean

    fun start(onCopy: (RawClip) -> Unit)

    fun stop()

    /** Reads the clipboard now, for "Send clipboard". Null if this provider can't. */
    fun readNow(): RawClip?
}

/** Primary: a privileged listener running as shell. No polling and no focus tricks. */
class ShizukuSource(private val context: Context, private val onAvailabilityChanged: () -> Unit) : ClipboardSource {
    override val name = "Shizuku"
    private val main = Handler(Looper.getMainLooper())
    private val userId = Process.myUid() / 100_000
    private var onCopy: ((RawClip) -> Unit)? = null
    private var watcher: IClipboardWatcher? = null
    private var failed = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ClipboardUserService::class.java.name))
        .daemon(false).processNameSuffix("clipboard").version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IClipboardWatcher.Stub.asInterface(binder) ?: return
            Thread({
                val error = runCatching { service.watch(callback, userId) }.getOrElse { it.javaClass.simpleName }
                main.post {
                    if (error.isEmpty()) {
                        watcher = service
                        failed = false
                        SyncLog.log("Shizuku clipboard listener attached")
                    } else {
                        // One UI may differ from AOSP here. Fall back rather than stay silent.
                        failed = true
                        SyncLog.log("Shizuku listener failed: $error")
                        onAvailabilityChanged()
                    }
                }
            }, "shizuku-attach").start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            watcher = null
            SyncLog.log("Shizuku service disconnected")
            onAvailabilityChanged()
        }
    }

    private val callback = object : IClipChangedCallback.Stub() {
        override fun onClipChanged() {
            val listener = onCopy ?: return
            readNow()?.let(listener)
        }
    }

    private val received = Shizuku.OnBinderReceivedListener {
        SyncLog.log("Shizuku is available")
        failed = false
        main.post(onAvailabilityChanged)
    }
    private val dead = Shizuku.OnBinderDeadListener {
        SyncLog.log("Shizuku stopped")
        main.post {
            watcher = null
            onAvailabilityChanged()
        }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post(onAvailabilityChanged) }

    init {
        // The sticky form also fires now if Shizuku is already running.
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    override fun isAvailable(): Boolean = !failed && isGranted()

    override fun start(onCopy: (RawClip) -> Unit) {
        this.onCopy = onCopy
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { SyncLog.log("Shizuku bind failed: ${it.javaClass.simpleName}") }
    }

    override fun stop() {
        onCopy = null
        watcher = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    fun release() {
        stop()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
    }

    override fun readNow(): RawClip? {
        val service = watcher ?: return null
        val bundle: Bundle = runCatching { service.readClip(userId) }.getOrElse {
            SyncLog.log("Shizuku read failed: ${it.javaClass.simpleName}")
            return null
        }
        val uris = bundle.getStringArrayList("uris").orEmpty()
        val text = bundle.getString("text")
        if (text == null && uris.isEmpty()) return null
        return RawClip(
            text = text,
            uris = uris,
            isImage = bundle.getBoolean("image"),
            sensitive = bundle.getBoolean("sensitive"),
            open = { uri -> service.openUri(uri)?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) } },
            nameOf = { uri -> runCatching { service.displayName(uri) }.getOrNull() },
        )
    }

    companion object {
        fun isGranted(): Boolean = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
}

/**
 * Watches logcat for Android's "Denying clipboard access" line about this app, which means someone
 * else just copied. Android only offers log access while the app is on top, so after a reboot this
 * stays silent until the app has been opened once. See NOTES.md section 6.2.
 */
private class LogcatTrigger(private val onTrigger: () -> Unit) {
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var stopped = false
    private var lastFire = 0L

    fun start() {
        Thread({
            try {
                val since = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                // Android 16 is reported to want the second form. To confirm on the A54.
                val filter = if (Build.VERSION.SDK_INT > 35) arrayOf("-s", "ClipboardService") else arrayOf("ClipboardService:E", "*:S")
                val started = Runtime.getRuntime().exec(arrayOf("logcat", "-T", since) + filter)
                process = started
                BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                    while (!stopped) {
                        val line = reader.readLine() ?: break
                        if (!line.contains(BuildConfig.APPLICATION_ID)) continue
                        val now = System.currentTimeMillis()
                        if (now - lastFire > 500) {
                            lastFire = now
                            onTrigger()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopped) SyncLog.log("Logcat trigger ended: ${e.javaClass.simpleName}")
            }
        }, "logcat-trigger").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        process?.destroy()
    }
}

/**
 * Fallback: Android lets the default input method read the clipboard, and only checks the setting.
 * So: make our keyboard the default for a moment, read, and put the previous keyboard back.
 * The previous keyboard is saved first, so a crash mid-swap is repaired by [restoreIfInterrupted].
 */
class ImeSwitchSource(private val context: Context) : ClipboardSource {
    override val name = "Keyboard switch"
    private val prefs = context.getSharedPreferences("ime_switch", Context.MODE_PRIVATE)
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val main = Handler(Looper.getMainLooper())
    private var trigger: LogcatTrigger? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val ourIme = ComponentName(context, MinimalImeService::class.java).flattenToShortString()

    override fun isAvailable(): Boolean = hasSecureSettings(context)

    override fun start(onCopy: (RawClip) -> Unit) {
        restoreIfInterrupted(context)
        // Android logs its denial line only for apps with a registered listener, so this is what
        // makes the log trigger work. It also fires directly whenever this app has focus.
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            runCatching { readLocal(context) }.getOrNull()?.let(onCopy)
        }.also(clipboard::addPrimaryClipChangedListener)
        if (!hasReadLogs(context)) {
            SyncLog.log("Keyboard switch has no trigger: READ_LOGS is not granted")
            return
        }
        trigger = LogcatTrigger {
            main.post { readNow()?.let(onCopy) }
        }.also { it.start() }
    }

    override fun stop() {
        listener?.let(clipboard::removePrimaryClipChangedListener)
        listener = null
        trigger?.stop()
        trigger = null
    }

    override fun readNow(): RawClip? {
        val resolver = context.contentResolver
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (previous == null) return null
        if (previous == ourIme) return readLocal(context)
        prefs.edit().putString(KEY_PREVIOUS, previous).commit()
        return try {
            enable(resolver)
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, ourIme)
            readLocal(context)
        } catch (e: Exception) {
            SyncLog.log("Keyboard switch failed: ${e.javaClass.simpleName}")
            null
        } finally {
            restoreIfInterrupted(context)
        }
    }

    private fun enable(resolver: android.content.ContentResolver) {
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        if (ourIme !in enabled.split(':')) {
            // Android resets a default keyboard that isn't also enabled.
            val updated = if (enabled.isEmpty()) ourIme else "$enabled:$ourIme"
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, updated)
        }
    }

    companion object {
        private const val KEY_PREVIOUS = "previous"

        fun hasSecureSettings(context: Context) = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

        fun hasReadLogs(context: Context) = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_LOGS,
        ) == PackageManager.PERMISSION_GRANTED

        /** Puts the saved keyboard back. Safe to call at any time, and does nothing if none is saved. */
        fun restoreIfInterrupted(context: Context) {
            val prefs = context.getSharedPreferences("ime_switch", Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_PREVIOUS, null) ?: return
            val restored = runCatching {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, previous)
            }.getOrDefault(false)
            if (restored) {
                prefs.edit().remove(KEY_PREVIOUS).commit()
            } else {
                SyncLog.log("Could not restore the previous keyboard")
            }
        }
    }
}

/** No automatic capture. The clipboard is read only from a focused screen, like ClipGrabActivity. */
class ManualSource : ClipboardSource {
    override val name = "Manual only"
    override fun isAvailable() = true
    override fun start(onCopy: (RawClip) -> Unit) {}
    override fun stop() {}
    override fun readNow(): RawClip? = null
}

/** Reads via this app's own access, which works when focused or while we're the default keyboard. */
fun readLocal(context: Context): RawClip? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
    val uris = items.mapNotNull { it.uri }.filter { it.scheme == "content" || it.scheme == "file" }.map { it.toString() }
    val text = items.mapNotNull { it.text?.toString() ?: it.uri?.toString() }.joinToString("\n").takeIf { it.isNotEmpty() }
    if (uris.isEmpty() && text == null) return null
    val resolver = context.contentResolver
    return RawClip(
        text = if (uris.isEmpty()) text else null,
        uris = uris,
        isImage = uris.size == 1 && (
            runCatching { resolver.getType(android.net.Uri.parse(uris[0])) }.getOrNull()?.startsWith("image/")
                ?: clip.description.hasMimeType("image/*")
            ),
        sensitive = clip.isSensitive(),
        open = { uri -> resolver.openInputStream(android.net.Uri.parse(uri)) },
        nameOf = { uri ->
            runCatching {
                resolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
        },
    )
}
