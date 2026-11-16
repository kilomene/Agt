package com.novainc.agt.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.novainc.agt.MainActivity
import com.novainc.agt.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The heart of Agt: a foreground service that keeps the host alive.
 *
 * It is not tied to any Activity: it keeps running with the screen locked,
 * in the background, until the owner stops it or Android itself is killed
 * and restarted by the boot receiver. On start it brings up the whole
 * runtime: the supervisor restores deployments, the three legs connect,
 * and the agent job engine starts polling. Every 5 seconds it writes a
 * heartbeat line to the app-private log that the Home screen reads.
 */
class AgtService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var networkMonitor: NetworkMonitor? = null
    private var startedAtMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ensureExternalHome()
        AlertNotifier.ensureChannel(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext).also { it.start(scope) }
        network = networkMonitor
    }

    /**
     * Give the owner a visible home for the app on shared storage:
     * Android/data/com.novainc.agt/files. Android creates that folder
     * only once the app actually uses its external files dir, and the
     * owner looks for the app there (his report 2026-11-16: no
     * com.novainc.agt folder under Android/data). Create it, a home
     * subfolder, and a README that says what lives where, on every
     * service start. A storage failure must never stop the service.
     */
    private fun ensureExternalHome() {
        try {
            val ext = getExternalFilesDir(null) ?: return
            java.io.File(ext, "home").mkdirs()
            val readme = java.io.File(ext, "README.txt")
            if (!readme.exists()) {
                readme.writeText(
                    "AGT lives here: Android/data/com.novainc.agt/files\n" +
                        "home/ is your folder on this phone's shared storage.\n" +
                        "The server itself (deployments, keys, logs) stays in the\n" +
                        "app's private storage, where other apps cannot touch it.\n"
                )
            }
            HeartbeatLog.append(applicationContext, "external home ready: ${ext.absolutePath}")
        } catch (e: Exception) {
            HeartbeatLog.append(applicationContext, "external home failed: ${e.message}")
        }
    }

    /**
     * Hold a partial wake lock for the whole life of the service: the
     * screen can lock and the phone can sit in a pocket, the CPU keeps
     * serving. The lock is reference-count-free and released exactly
     * once, in onDestroy, so it can never leak past the service.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AGT::HostWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        _wakeLockHeld.value = true
        HeartbeatLog.append(applicationContext, "wake lock acquired")
    }

    /**
     * Hold a WiFi lock next to the wake lock, the same pairing Termux
     * uses: the CPU stays awake AND the radio stays awake, so the
     * tunnel and the doorbell never wait on a sleeping WiFi chip.
     * Low-latency mode where the platform offers it.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(mode, "AGT::HostWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            HeartbeatLog.append(applicationContext, "wifi lock acquired")
        } catch (e: Exception) {
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
        wifiLock = null
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        wakeLock = null
        _wakeLockHeld.value = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        acquireWifiLock()
        armWatchdog(applicationContext)
        if (heartbeatJob?.isActive != true) {
            startedAtMs = SystemClock.elapsedRealtime()
            _running.value = true
            _serviceUptimeBaseMs.value = startedAtMs
            HeartbeatLog.append(applicationContext, "service started")
            AgtRuntime.get(applicationContext).startAll()
            heartbeatJob = scope.launch {
                var beat = 0
                while (isActive) {
                    beat += 1
                    val upSec = (SystemClock.elapsedRealtime() - startedAtMs) / 1000
                    val runtime = AgtRuntime.get(applicationContext)
                    val legs = "tunnel=${runtime.tunnel.state.value} " +
                        "firebase=${runtime.firebase.state.value} " +
                        "supabase=${if (runtime.supabase.configured) "ready" else "not-configured"}"
                    // The wake lock is the host's lifeline: verify it
                    // every beat and re-acquire on the spot if the
                    // system ever dropped it.
                    if (wakeLock?.isHeld != true) {
                        acquireWakeLock()
                        HeartbeatLog.append(applicationContext, "wake lock re-acquired")
                    }
                    if (wifiLock?.isHeld != true) {
                        acquireWifiLock()
                        HeartbeatLog.append(applicationContext, "wifi lock re-acquired")
                    }
                    HeartbeatLog.append(
                        applicationContext,
                        "heartbeat #$beat  uptime ${upSec}s  link ${networkMonitor?.reconnector?.state?.value}  wakelock=${if (_wakeLockHeld.value) "held" else "off"}  wifilock=${if (wifiLock?.isHeld == true) "held" else "off"}  $legs"
                    )
                    // The persistent notification is a live instrument,
                    // not a static label: refresh its line every beat.
                    try {
                        val deployments = runtime.store.loadDeployments()
                        val runningCount = deployments.count { it.status == "running" }
                        val line = WidgetStatus.statusLine(true, runningCount, deployments.size) +
                            ". Tunnel " + runtime.tunnel.state.value.name.lowercase() + "."
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIFICATION_ID, buildNotification(line))
                    } catch (e: Exception) {
                    }
                    com.novainc.agt.ui.AgtWidget.updateAll(applicationContext)
                    delay(HEARTBEAT_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents is not a stop order: schedule the
        // comeback before Android finishes tearing the task down.
        if (!userStopped) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * One-second comeback alarm, fired through RestartReceiver. Set on
     * task removal and on destroy (unless the owner stopped the host
     * deliberately), so a system kill is a blink, not an outage.
     */
    private fun scheduleRestart() {
        try {
            val am = getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(applicationContext, RestartReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                applicationContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pending
            )
            HeartbeatLog.append(applicationContext, "restart scheduled")
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        if (!userStopped) scheduleRestart()
        HeartbeatLog.append(applicationContext, "service stopped")
        _running.value = false
        releaseWakeLock()
        releaseWifiLock()
        AgtRuntime.get(applicationContext).stopAll()
        networkMonitor?.stop()
        networkMonitor = null
        network = null
        heartbeatJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Agt host service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the Agt host running" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Host online. Serving in the background."): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agt is running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_agt)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agt_host"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_MS = 5_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _wakeLockHeld = MutableStateFlow(false)
        val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

        private val _serviceUptimeBaseMs = MutableStateFlow(0L)
        val serviceUptimeBaseMs: StateFlow<Long> = _serviceUptimeBaseMs.asStateFlow()

        @Volatile
        var network: NetworkMonitor? = null
            private set

        /** True only when the owner deliberately stopped the host;
         * every comeback road (sticky, alarm, boot) respects it. */
        @Volatile
        var userStopped: Boolean = false

        /**
         * Arm the next watchdog alarm, 2 minutes out, exact where the
         * platform allows it. RestartReceiver re-arms on every firing,
         * so the chain survives process death; a deliberate stop ends
         * it because the receiver checks userStopped first.
         */
        fun armWatchdog(context: Context) {
            try {
                val app = context.applicationContext
                val am = app.getSystemService(AlarmManager::class.java) ?: return
                val intent = Intent(app, RestartReceiver::class.java).apply {
                    action = "com.novainc.agt.WATCHDOG"
                }
                val pending = PendingIntent.getBroadcast(
                    app, 77, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val at = System.currentTimeMillis() + WatchdogPolicy.INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } catch (e: Exception) {
            }
        }

        fun start(context: Context) {
            userStopped = false
            val intent = Intent(context.applicationContext, AgtService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            userStopped = true
            context.applicationContext.stopService(
                Intent(context.applicationContext, AgtService::class.java)
            )
        }
    }
}
