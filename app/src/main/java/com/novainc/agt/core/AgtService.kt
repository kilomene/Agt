package com.novainc.agt.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
 * and the agent job engine starts polling. Every 30 seconds it writes a
 * heartbeat line to the app-private log that the Home screen reads.
 */
class AgtService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var networkMonitor: NetworkMonitor? = null
    private var startedAtMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AlertNotifier.ensureChannel(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext).also { it.start(scope) }
        network = networkMonitor
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
                    HeartbeatLog.append(
                        applicationContext,
                        "heartbeat #$beat  uptime ${upSec}s  link ${networkMonitor?.reconnector?.state?.value}  wakelock=${if (_wakeLockHeld.value) "held" else "off"}  $legs"
                    )
                    com.novainc.agt.ui.AgtWidget.updateAll(applicationContext)
                    delay(HEARTBEAT_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        HeartbeatLog.append(applicationContext, "service stopped")
        _running.value = false
        releaseWakeLock()
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

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agt is running")
            .setContentText("Host online. Serving in the background.")
            .setSmallIcon(R.drawable.ic_stat_agt)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agt_host"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_MS = 30_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _wakeLockHeld = MutableStateFlow(false)
        val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

        private val _serviceUptimeBaseMs = MutableStateFlow(0L)
        val serviceUptimeBaseMs: StateFlow<Long> = _serviceUptimeBaseMs.asStateFlow()

        @Volatile
        var network: NetworkMonitor? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, AgtService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, AgtService::class.java)
            )
        }
    }
}
