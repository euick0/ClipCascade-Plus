// android\app\src\main\java\com\clipcascade\BootReceiver.kt
package com.clipcascade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        com.clipcascade.sync.SyncLog.init(context)
        // A crash during a keyboard swap must not outlive a reboot or an update.
        com.clipcascade.sync.ImeSwitchSource.restoreIfInterrupted(context)
        if (com.clipcascade.sync.SyncService.isWanted(context)) {
            com.clipcascade.sync.SyncLog.log("Starting after $action")
            com.clipcascade.sync.SyncService.send(context)
        } else if (action == Intent.ACTION_BOOT_COMPLETED) {
            // P2P mode still runs on the JS service.
            startHeadlessTask(context)
        }
    }

    private fun startHeadlessTask(context: Context) {
        val headlessTaskIntent = Intent(context, HeadlessTaskService::class.java).apply {
            putExtra("event", "BOOT_COMPLETED")
        }
        context.startService(headlessTaskIntent)
    }
}
