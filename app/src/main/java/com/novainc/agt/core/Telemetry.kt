package com.novainc.agt.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock

/** A snapshot of real device telemetry, read live from Android APIs. */
data class TelemetrySnapshot(
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val storageFreeGb: Double,
    val storageTotalGb: Double,
    val memoryAvailGb: Double,
    val memoryTotalGb: Double,
    val deviceUptimeMs: Long
)

object Telemetry {
    fun read(context: Context): TelemetrySnapshot {
        val batteryIntent = context.applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        val am = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

        return TelemetrySnapshot(
            batteryPercent = percent,
            batteryCharging = charging,
            storageFreeGb = freeBytes / 1_000_000_000.0,
            storageTotalGb = totalBytes / 1_000_000_000.0,
            memoryAvailGb = mem.availMem / 1_000_000_000.0,
            memoryTotalGb = mem.totalMem / 1_000_000_000.0,
            deviceUptimeMs = SystemClock.elapsedRealtime()
        )
    }
}
