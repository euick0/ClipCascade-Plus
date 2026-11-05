package com.clipcascade.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Fired by the alarm from SyncService.scheduleRestart. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncLog.init(context)
        if (SyncService.isWanted(context)) {
            SyncLog.log("Restart alarm fired")
            SyncService.send(context)
        }
    }
}

/** Runs about every 15 min. Starts a dead service and nudges a disconnected one. */
class SyncWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncLog.init(applicationContext)
        if (SyncService.isWanted(applicationContext)) {
            if (!SyncService.isRunning) SyncLog.log("Watchdog found the service dead")
            SyncService.send(applicationContext, SyncService.ACTION_WATCHDOG)
        }
        return Result.success()
    }
}

private const val WATCHDOG_WORK = "clipcascade_plus_watchdog"

fun scheduleWatchdog(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WATCHDOG_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWatchdogWorker>(15, TimeUnit.MINUTES).build(),
    )
}

fun cancelWatchdog(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK)
}
