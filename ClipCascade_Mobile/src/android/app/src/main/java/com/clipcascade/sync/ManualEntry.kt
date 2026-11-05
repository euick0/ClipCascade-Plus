package com.clipcascade.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.inputmethodservice.InputMethodService
import android.service.quicksettings.TileService
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.clipcascade.ClipboardContent
import com.clipcascade.MainActivity
import com.clipcascade.plus.core.Clip
import com.clipcascade.plus.core.ClipType
import org.json.JSONObject

/**
 * An invisible screen. Android allows clipboard reads while an app has focus, so a tap on the tile
 * or notification action can always send the clipboard, with no special permissions.
 */
class ClipGrabActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val raw = runCatching { readLocal(this) }.getOrNull()
        when {
            raw == null -> toast("Clipboard is empty")
            SyncService.instance == null -> {
                SyncService.send(this)
                toast("Sync wasn't running. Try again in a moment")
            }
            else -> {
                val clip = runCatching { toClip(raw) }.getOrNull()
                if (clip == null) toast("Couldn't read the clipboard") else {
                    SyncService.instance?.sendManual(clip, raw.sensitive)
                    toast("Clipboard sent")
                }
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun toast(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    private fun toClip(raw: RawClip): Clip? = rawToClip(raw)
}

/** One conversion for the manual paths. Automatic capture adds size limits in CaptureManager. */
internal fun rawToClip(raw: RawClip): Clip? {
    raw.text?.let { return Clip(ClipType.TEXT, it) }
    if (raw.uris.isEmpty()) return null
    if (raw.isImage) {
        val bytes = raw.open(raw.uris.first())?.use { it.readBytes() } ?: return null
        return Clip(ClipType.IMAGE, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    val json = JSONObject()
    raw.uris.forEach { uri ->
        val bytes = raw.open(uri)?.use { it.readBytes() } ?: return null
        json.put(raw.nameOf(uri) ?: uri.substringAfterLast('/'), Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    return Clip(ClipType.FILES, json.toString())
}

fun clipGrabIntent(context: Context): Intent =
    Intent(context, ClipGrabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

/** Quick Settings tile: "Send clipboard". */
class SendClipTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = clipGrabIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/** Share-sheet target. Sends through native sync, or hands over to the old flow in P2P mode. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = SyncService.instance
        if (service == null) {
            startActivity(
                Intent(intent).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            finish()
            return
        }
        val content = runCatching { ClipboardContent.fromIntent(intent, contentResolver) }.getOrNull()
        val app = applicationContext
        val resolver = contentResolver
        if (content == null) {
            finish()
            return
        }
        Thread({
            // The read grant lasts while this activity exists, so finish only after reading.
            val clip = runCatching {
                when (content.type) {
                    "text" -> Clip(ClipType.TEXT, content.values.first())
                    else -> rawToClip(
                        RawClip(
                            null, content.values, content.type == "image", false,
                            { resolver.openInputStream(Uri.parse(it)) },
                            { uri ->
                                resolver.query(Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            },
                        ),
                    )
                }
            }.onFailure { SyncLog.log("Could not read a share: ${it.javaClass.simpleName}") }.getOrNull()
            runOnUiThread {
                if (clip != null) service.sendManual(clip)
                Toast.makeText(app, if (clip != null) "Sent" else "Couldn't send that", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, "share-read").start()
    }
}

/**
 * Never meant to be typed with. It exists so this app can briefly be the default keyboard. If
 * Android ever actually shows it, it hands straight back to the previous keyboard.
 */
class MinimalImeService : InputMethodService() {
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ImeSwitchSource.restoreIfInterrupted(this)
        // Nothing was saved to restore, yet this is showing: never leave the phone without a keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { if (!switchToPreviousInputMethod()) switchToNextInputMethod(false) }
        }
    }
}
