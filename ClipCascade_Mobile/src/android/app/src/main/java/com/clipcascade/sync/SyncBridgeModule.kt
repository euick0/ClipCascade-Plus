package com.clipcascade.sync

import android.content.Intent
import com.clipcascade.plus.core.SyncState
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/** The small surface the React Native UI uses to drive native sync. */
class SyncBridgeModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName() = "SyncBridge"

    @ReactMethod
    fun saveLogin(login: ReadableMap, promise: Promise) {
        try {
            val store = SecureStore(context)
            store.saveConfig(
                SyncConfig(
                    serverUrl = login.getString("serverUrl")!!,
                    username = login.getString("username")!!,
                    loginHash = login.getString("loginHash")!!,
                    encryptionKey = login.getString("encryptionKey")?.takeIf { it.isNotEmpty() },
                    maxLocalBytes = login.getString("maxBytes")?.toLongOrNull(),
                ),
            )
            // The session cookie belongs to the old login, if any.
            store.saveSessionCookies(null)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SAVE_LOGIN_FAILED", "Could not store the login securely (${e.javaClass.simpleName})", e)
        }
    }

    @ReactMethod
    fun start(promise: Promise) {
        SyncLog.init(context)
        SyncService.send(context)
        promise.resolve(null)
    }

    @ReactMethod
    fun stop(promise: Promise) {
        if (SyncService.isRunning) SyncService.send(context, SyncService.ACTION_STOP)
        promise.resolve(null)
    }

    /** At logout: stop, and forget what the service stored. */
    @ReactMethod
    fun forget(promise: Promise) {
        if (SyncService.isRunning) SyncService.send(context, SyncService.ACTION_STOP)
        SecureStore(context).clear()
        SensitiveExpiry(context).disarm()
        promise.resolve(null)
    }

    @ReactMethod
    fun openSetup(promise: Promise) {
        context.startActivity(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        promise.resolve(null)
    }
}
