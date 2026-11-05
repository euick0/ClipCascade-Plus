package com.clipcascade.sync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipcascade.AsyncStorageBridge
import com.clipcascade.MainActivity
import com.clipcascade.R
import com.clipcascade.ServerTls
import com.clipcascade.plus.core.Clip
import com.clipcascade.plus.core.ClipCascadeClient
import com.clipcascade.plus.core.ClipCredentials
import com.clipcascade.plus.core.ClipType
import com.clipcascade.plus.core.LoopGuard
import com.clipcascade.plus.core.Session
import com.clipcascade.plus.core.SyncEngine
import com.clipcascade.plus.core.SyncEngineListener
import com.clipcascade.plus.core.SyncState
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The always-on connection. Android type remoteMessaging has no documented runtime limit and can
 * start at boot, unlike dataSync, whose limit since Android 15 is about 6 h a day. See NOTES.md.
 */
class SyncService : Service(), SyncEngineListener {
    private val main = Handler(Looper.getMainLooper())
    private var engine: SyncEngine? = null
    private lateinit var store: SecureStore
    private lateinit var applier: ClipApplier
    private lateinit var expiry: SensitiveExpiry
    private var capture: CaptureManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var stoppedByUser = false
    private var restartAlreadyQueued = false
    private var lastText = "Starting…"
    private var defaultNetwork: Network? = null
    private val countdown = object : Runnable {
        override fun run() {
            val state = engine?.state
            if (state is SyncState.Backoff) {
                notifyStatus(describe(state))
                main.postDelayed(this, 1_000)
            }
        }
    }
    private val expiryCheck = Runnable { checkExpiry() }

    override fun onCreate() {
        super.onCreate()
        SyncLog.init(this)
        store = SecureStore(this)
        applier = ClipApplier(this)
        expiry = SensitiveExpiry(this)
        createChannels()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first: a started foreground service has seconds to call this or the app is killed.
        if (!enterForeground()) {
            return START_STICKY
        }
        // A null intent means Android restarted this after killing it. That counts as a start.
        when (intent?.action) {
            ACTION_STOP -> {
                userStop()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                ensureEngine()
                engine?.reconnectNow("user")
            }
            ACTION_WATCHDOG -> {
                ensureEngine()
                if (engine?.state !is SyncState.Connected) engine?.reconnectNow("watchdog")
            }
            else -> ensureEngine()
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(lastText), type)
        true
    } catch (e: Exception) {
        // Android refuses background starts without a battery exemption. Ask the alarm to retry.
        SyncLog.log("Could not enter the foreground: ${e.javaClass.simpleName}")
        scheduleRestart(this, 60_000)
        restartAlreadyQueued = true
        stopSelf()
        false
    }

    private fun ensureEngine() {
        if (engine != null) return
        val config = store.loadConfig()
        if (config == null) {
            SyncLog.log("No saved login, so sync can't start")
            notifyStatus("Open the app to log in")
            return
        }
        AsyncStorageBridge(this).setValue("sync_requested", "true")
        // After a restart the JS side hasn't run, so a custom CA must be applied from here too.
        runCatching {
            AsyncStorageBridge(this).getValue("ssl_ca_bundle")?.takeIf { it.isNotBlank() }
                ?.let { ServerTls.configure(config.serverUrl, it) }
        }.onFailure { SyncLog.log("Custom CA not applied: ${it.javaClass.simpleName}") }
        val credentials = ClipCredentials(
            config.username,
            config.loginHash,
            config.encryptionKey?.let { Base64.decode(it, Base64.DEFAULT) },
        )
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .apply { ServerTls.applyTo(this) }
            .build()
        val saved = store.loadSessionCookies()?.let(::parseCookies)
        val created = SyncEngine(ClipCascadeClient(config.serverUrl, http), credentials, this, saved)
        engine = created
        registerNetworkCallback()
        created.start()
        capture = CaptureManager(this, config) { clip, sensitive -> onLocalClip(clip, sensitive) }.also { it.start() }
        scheduleWatchdog(this)
        checkExpiry()
        SyncLog.log("Service started")
    }

    private fun onLocalClip(clip: Clip, sensitive: Boolean) {
        engine?.sendClip(clip)
        if (sensitive) {
            SyncLog.log("Sensitive clip: expires in 5 min")
            expiry.arm(LoopGuard.hash(clip.type, clip.content))
            checkExpiry()
        }
    }

    /** Sends a clip regardless of the dedupe rules, for "Send clipboard" and the share sheet. */
    fun sendManual(clip: Clip, sensitive: Boolean = false) {
        engine?.sendClipForced(clip)
        if (sensitive) {
            expiry.arm(LoopGuard.hash(clip.type, clip.content))
            checkExpiry()
        }
    }

    private fun checkExpiry() {
        main.removeCallbacks(expiryCheck)
        val (hash, deadline) = expiry.pending() ?: return
        val wait = deadline - System.currentTimeMillis()
        if (wait > 0) {
            // This timer can run late in Doze, and then fires as soon as the phone wakes.
            main.postDelayed(expiryCheck, wait)
            return
        }
        engine?.expireIfCurrent(hash) { wasCurrent ->
            expiry.disarm()
            main.post {
                if (wasCurrent) {
                    runCatching { applier.clear() }
                    SyncLog.log("Sensitive clip expired and was cleared")
                } else {
                    SyncLog.log("Sensitive clip was already replaced")
                }
            }
        }
    }

    override fun onState(state: SyncState) {
        main.removeCallbacks(countdown)
        val text = describe(state)
        main.post {
            notifyStatus(text)
            if (state is SyncState.Backoff) main.post(countdown)
        }
        runCatching {
            AsyncStorageBridge(this).setValue("wsStatusMessage", (if (state is SyncState.Connected) "✅ " else "") + text)
        }
    }

    override fun onRemoteClip(clip: Clip) {
        if (runCatching { AsyncStorageBridge(this).getValue("receive_clipboard") == "false" }.getOrDefault(false)) return
        main.post {
            try {
                capture?.ignoreNextChange()
                val files = applier.apply(clip)
                SyncLog.log("Applied a received ${clip.type.wireName} clip")
                if (files > 0) notifyFilesSaved(files)
            } catch (e: Exception) {
                // For example, Android's binder limit on large clipboard items.
                SyncLog.log("Could not apply a ${clip.type.wireName} clip: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onSessionChanged(session: Session?) {
        runCatching { store.saveSessionCookies(session?.cookieHeader) }
    }

    override fun log(message: String) = SyncLog.log(message)

    private fun describe(state: SyncState): String = when (state) {
        SyncState.Connected -> "Connected"
        SyncState.Connecting -> "Connecting…"
        SyncState.Authenticating -> "Logging in…"
        SyncState.Disconnected -> "Disconnected"
        is SyncState.NoNetwork -> "No network"
        is SyncState.Backoff -> {
            val seconds = TimeUnit.MILLISECONDS.toSeconds(state.retryAtMillis - System.currentTimeMillis() + 999)
            "Reconnecting in ${seconds.coerceAtLeast(0)} s…"
        }
        is SyncState.NeedsLogin -> "Login refused. Open the app to log in again"
        is SyncState.Unsupported -> "Server is in ${state.mode} mode"
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed(network)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Regaining validation (captive portal, Doze exit) deserves a reconnect too.
                if (network != defaultNetwork) changed(network)
            }

            override fun onLost(network: Network) {
                if (network == defaultNetwork) {
                    defaultNetwork = null
                    SyncLog.log("Default network lost")
                    engine?.onNetworkChanged(false)
                }
            }

            private fun changed(network: Network) {
                if (network == defaultNetwork) return
                defaultNetwork = network
                SyncLog.log("Default network changed")
                engine?.onNetworkChanged(true)
            }
        }
        // The callback fires right away with the current network, if there is one.
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { SyncLog.log("No network callback: ${it.javaClass.simpleName}") }
    }

    private fun userStop() {
        SyncLog.log("Stopped by the user")
        stoppedByUser = true
        AsyncStorageBridge(this).setValue("sync_requested", "false")
        cancelWatchdog(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The user swiped the app from recents. On Samsung this can also take the service with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SyncLog.log("Task removed")
        if (!stoppedByUser) scheduleRestart(this, 2_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        capture?.stop()
        engine?.stop()
        if (!stoppedByUser && !restartAlreadyQueued) {
            SyncLog.log("Service destroyed unexpectedly, scheduling a restart")
            scheduleRestart(this, 5_000)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyStatus(text: String) {
        lastText = text
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun notifyFilesSaved(count: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ClipCascade Plus")
            .setContentText(if (count == 1) "1 file saved to Downloads" else "$count files saved to Downloads")
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(FILES_NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ClipCascade Plus")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Reconnect now", action(ACTION_RECONNECT, 1))
            .addAction(0, "Send clipboard", sendClipboardAction())
            .addAction(0, "Status", statusAction())
            .build()
    }

    /** Goes through a focused screen, because the service itself can't read the clipboard. */
    private fun sendClipboardAction(): PendingIntent = PendingIntent.getActivity(
        this, 2, clipGrabIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusAction(): PendingIntent = PendingIntent.getActivity(
        this, 3, Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, SyncService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Received files", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val ACTION_STOP = "com.clipcascade.plus.STOP"
        const val ACTION_RECONNECT = "com.clipcascade.plus.RECONNECT"
        const val ACTION_WATCHDOG = "com.clipcascade.plus.WATCHDOG"
        private const val CHANNEL_STATUS = "clipcascade_plus_status"
        private const val CHANNEL_EVENTS = "clipcascade_plus_events"
        private const val NOTIFICATION_ID = 7001
        private const val FILES_NOTIFICATION_ID = 7002

        @Volatile var instance: SyncService? = null
            private set

        val isRunning: Boolean get() = instance != null
        val currentState: SyncState? get() = instance?.engine?.state
        val activeCapture: String? get() = instance?.capture?.activeProviderName

        fun send(context: Context, action: String? = null) {
            val intent = Intent(context, SyncService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background start refused, usually for lack of a battery exemption.
                SyncLog.log("Service start refused: ${e.javaClass.simpleName}")
                scheduleRestart(context, 60_000)
            }
        }

        /** True if the user wants sync on, and native sync is set up. */
        fun isWanted(context: Context): Boolean =
            runCatching {
                SecureStore(context).loadConfig() != null && AsyncStorageBridge(context).isSyncRequested()
            }.getOrDefault(false)

        /** An alarm survives process death. The exact-alarm permission isn't needed for this type. */
        fun scheduleRestart(context: Context, delayMillis: Long) {
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMillis, pending,
                )
            }
        }

        fun isBatteryExempt(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        private fun parseCookies(header: String): Session = Session(
            header.split("; ").mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1) else null
            }.toMap(),
        )
    }
}
