package com.novainc.agt.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import java.io.File

/** A snapshot of real device telemetry, read live from Android APIs.
 * Every value comes from the device itself: battery intent, StatFs,
 * ActivityManager, /proc/stat deltas, and the active network's real
 * link capabilities. Nothing is estimated or invented. */
data class TelemetrySnapshot(
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val batteryTempC: Double,
    val storageFreeGb: Double,
    val storageTotalGb: Double,
    val memoryAvailGb: Double,
    val memoryTotalGb: Double,
    val memoryUsedGb: Double,
    val cpuPercent: Float,
    val networkMbps: Double,
    val networkLabel: String,
    val deviceUptimeMs: Long
)

/** CPU load from /proc/stat deltas. The pure math is separated so it
 * can be unit tested without a device. */
object CpuSampler {
    private var prev: LongArray? = null

    /** Percent busy between two /proc/stat cpu lines, 0..100.
     * Fields are the numeric columns after "cpu": user, nice, system,
     * idle, iowait, irq, softirq, steal, guest, guest_nice. */
    fun percentBetween(prev: LongArray, cur: LongArray): Float {
        if (prev.size < 5 || cur.size < 5) return 0f
        val prevTotal = prev.sum()
        val curTotal = cur.sum()
        val totalDelta = curTotal - prevTotal
        if (totalDelta <= 0) return 0f
        val idleDelta = (cur[3] + cur[4]) - (prev[3] + prev[4])
        val busy = 1.0 - idleDelta.toDouble() / totalDelta.toDouble()
        return (busy * 100.0).toFloat().coerceIn(0f, 100f)
    }

    fun parseCpuLine(line: String): LongArray? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6 || parts[0] != "cpu") return null
        return try {
            LongArray(parts.size - 1) { i -> parts[i + 1].toLong() }
        } catch (e: Exception) {
            null
        }
    }

    /** Sample now; returns load since the previous sample (0 on the
     * very first call, when there is nothing to compare against). */
    @Synchronized
    fun sample(): Float {
        val cur = try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() }
            if (line == null) null else parseCpuLine(line)
        } catch (e: Exception) {
            null
        } ?: return 0f
        val p = prev
        prev = cur
        if (p == null) return 0f
        return percentBetween(p, cur)
    }
}

object Telemetry {
    fun read(context: Context): TelemetrySnapshot {
        val app = context.applicationContext
        val batteryIntent = app.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        // Battery temperature arrives in tenths of a degree Celsius.
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (tempTenths > 0) tempTenths / 10.0 else 0.0

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalGb = mem.totalMem / 1_000_000_000.0
        val availGb = mem.availMem / 1_000_000_000.0

        val (mbps, label) = network(app)

        return TelemetrySnapshot(
            batteryPercent = percent,
            batteryCharging = charging,
            batteryTempC = tempC,
            storageFreeGb = freeBytes / 1_000_000_000.0,
            storageTotalGb = totalBytes / 1_000_000_000.0,
            memoryAvailGb = availGb,
            memoryTotalGb = totalGb,
            memoryUsedGb = (totalGb - availGb).coerceAtLeast(0.0),
            cpuPercent = CpuSampler.sample(),
            networkMbps = mbps,
            networkLabel = label,
            deviceUptimeMs = SystemClock.elapsedRealtime()
        )
    }

    /** The active network's real downstream link rate and a short
     * label (WiFi, 5G, 4G, LTE, Mobile). The rate is the link
     * capability Android reports for the live network. */
    private fun network(context: Context): Pair<Double, String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 0.0 to ""
            val mbps = caps.linkDownstreamBandwidthKbps / 1000.0
            val label = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // The radio type can need a permission the app does
                    // not hold; a failure there must not cost the rate.
                    try {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        @Suppress("DEPRECATION")
                        when (tm.dataNetworkType) {
                            TelephonyManager.NETWORK_TYPE_NR -> "5G"
                            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                            else -> "Mobile"
                        }
                    } catch (e: Exception) {
                        "Mobile"
                    }
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> ""
            }
            mbps to label
        } catch (e: Exception) {
            0.0 to ""
        }
    }
}
