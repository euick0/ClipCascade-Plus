package com.clipcascade.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import com.clipcascade.plus.core.Clip
import com.clipcascade.plus.core.ClipType
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Applies clips from other devices to this phone. */
class ClipApplier(private val context: Context) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Returns the number of files saved to Downloads, for clips of type files. */
    fun apply(clip: Clip): Int = when (clip.type) {
        ClipType.TEXT -> {
            if (clip.content.isEmpty()) clear() else clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, clip.content))
            0
        }
        ClipType.IMAGE -> {
            applyImage(clip.content)
            0
        }
        ClipType.FILES -> saveFiles(clip.content)
    }

    /** Clears this phone's clipboard. Used by sensitive-clip expiry and for empty clips from others. */
    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun applyImage(base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Not a valid image")
        context.cacheDir.listFiles { f -> f.name.startsWith(IMAGE_PREFIX) }?.forEach { it.delete() }
        val file = File(context.cacheDir, "$IMAGE_PREFIX${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
    }

    private fun saveFiles(json: String): Int {
        // MediaStore.Downloads needs Android 10. Older phones aren't a target for this fork.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw UnsupportedOperationException("Needs Android 10")
        val files = JSONObject(json)
        var saved = 0
        files.keys().forEach { name ->
            val values = ContentValues().apply {
                // Names come from another device, so keep only the last path component.
                put(MediaStore.Downloads.DISPLAY_NAME, name.substringAfterLast('/').substringAfterLast('\\'))
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create a download")
            context.contentResolver.openOutputStream(uri)!!.use {
                it.write(Base64.decode(files.getString(name), Base64.DEFAULT))
            }
            saved++
        }
        return saved
    }

    private companion object {
        const val LABEL = "ClipCascade"
        const val IMAGE_PREFIX = "clipboard_image_"
    }
}

/** Android's marker for passwords and similar, set by the app that made the copy. */
fun ClipData.isSensitive(): Boolean {
    val extras: PersistableBundle = description.extras ?: return false
    return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
}
