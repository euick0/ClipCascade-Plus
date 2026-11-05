package com.clipcascade.sync

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The in-app log, kept in a file so it shows what happened before a process death.
 * Callers must never pass clip content or credentials: users export and share this.
 */
object SyncLog {
    private const val MAX_BYTES = 256 * 1024L
    private const val TAG = "ClipCascadePlus"
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, "sync.log")
    }

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        runCatching {
            if (target.length() > MAX_BYTES) {
                val keep = target.readText().takeLast((MAX_BYTES / 2).toInt())
                target.writeText(keep.substringAfter('\n'))
            }
            target.appendText("${time.format(Date())} $message\n")
        }
    }

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
