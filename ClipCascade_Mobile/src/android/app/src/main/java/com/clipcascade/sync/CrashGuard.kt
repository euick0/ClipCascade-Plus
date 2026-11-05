package com.clipcascade.sync

import android.content.Context
import android.os.Looper

/**
 * Android can't carry on after an uncaught exception on the main thread, so that case is logged, a
 * restart is scheduled, and the process dies. Other threads just lose that one task and keep going.
 */
object CrashGuard {
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Class names and code locations only: exception messages might quote clip content.
            val where = error.stackTrace.firstOrNull { it.className.startsWith("com.clipcascade") }
            SyncLog.log("Uncaught ${error.javaClass.simpleName} on ${thread.name} at $where")
            if (thread === Looper.getMainLooper().thread) {
                if (SyncService.isWanted(app)) SyncService.scheduleRestart(app, 3_000)
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
