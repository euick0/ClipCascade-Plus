package com.clipcascade.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.clipcascade.AsyncStorageBridge
import com.clipcascade.plus.core.Clip
import com.clipcascade.plus.core.ClipType
import org.json.JSONObject
import java.util.concurrent.Executors

/** Runs the best capture provider, and switches when availability changes. */
class CaptureManager(
    private val context: Context,
    private val config: SyncConfig,
    private val onClip: (Clip, Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clip-capture").apply { isDaemon = true } }
    private val shizuku = ShizukuSource(context) { main.post { choose() } }
    private val sources: List<ClipboardSource> = listOf(shizuku, ImeSwitchSource(context), ManualSource())
    private var active: ClipboardSource? = null
    private var ignoreUntil = 0L
    private val recheck = object : Runnable {
        override fun run() {
            choose()
            main.postDelayed(this, RECHECK_MILLIS)
        }
    }

    val activeProviderName: String? get() = active?.name

    fun start() {
        recheck.run()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        active?.stop()
        active = null
        shizuku.release()
        worker.shutdown()
    }

    /** We're about to write a clip from another device. Writing it fires the same change event. */
    fun ignoreNextChange() {
        ignoreUntil = System.currentTimeMillis() + IGNORE_MILLIS
    }

    /** The notification and tile use ClipGrabActivity instead when no provider can read directly. */
    fun sendCurrentClipboard(): Boolean {
        val raw = active?.readNow() ?: return false
        worker.execute { deliver(raw, manual = true) }
        return true
    }

    private fun choose() {
        val best = sources.first { it.isAvailable() }
        if (best === active) return
        active?.stop()
        active = best
        SyncLog.log("Capture provider: ${best.name}")
        best.start { raw -> worker.execute { deliver(raw, manual = false) } }
    }

    private fun deliver(raw: RawClip, manual: Boolean) {
        try {
            if (!manual && System.currentTimeMillis() < ignoreUntil) return
            if (!isOn("send_clipboard")) return
            val clip = load(raw) ?: return
            if (manual) {
                SyncService.instance?.sendManual(clip, raw.sensitive)
            } else {
                onClip(clip, raw.sensitive)
            }
        } catch (e: Throwable) {
            // OutOfMemoryError included: a big attachment must not take the service down.
            SyncLog.log("Could not read a clip: ${e.javaClass.simpleName}")
        }
    }

    private fun load(raw: RawClip): Clip? {
        val limit = config.maxLocalBytes ?: DEFAULT_LIMIT_BYTES
        raw.text?.let { text ->
            if (text.toByteArray(Charsets.UTF_8).size > limit) {
                SyncLog.log("Text clip over the size limit, not sent")
                return null
            }
            return Clip(ClipType.TEXT, text)
        }
        if (raw.isImage) {
            if (!isOn("enable_image_sharing")) return null
            val bytes = readLimited(raw, raw.uris.first(), limit) ?: return null
            return Clip(ClipType.IMAGE, Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        if (!isOn("enable_file_sharing")) return null
        val json = JSONObject()
        var total = 0L
        for (uri in raw.uris) {
            val bytes = readLimited(raw, uri, limit - total) ?: return null
            total += bytes.size
            val name = raw.nameOf(uri) ?: uri.substringAfterLast('/')
            json.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        return Clip(ClipType.FILES, json.toString())
    }

    private fun readLimited(raw: RawClip, uri: String, limit: Long): ByteArray? {
        val stream = raw.open(uri) ?: return null
        stream.use { input ->
            val bytes = input.readNBytesCompat(limit + 1)
            if (bytes.size > limit) {
                SyncLog.log("Attachment over the size limit, not sent")
                return null
            }
            return bytes
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), max - out.size()).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun isOn(key: String): Boolean =
        runCatching { AsyncStorageBridge(context).getValue(key) != "false" }.getOrDefault(true)

    private companion object {
        const val RECHECK_MILLIS = 60_000L
        const val IGNORE_MILLIS = 1_500L
        const val DEFAULT_LIMIT_BYTES = 1024L * 1024L
    }
}
