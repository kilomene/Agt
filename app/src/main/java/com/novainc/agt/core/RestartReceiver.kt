package com.novainc.agt.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Two jobs, one receiver:
 *  - Comeback: AgtService schedules a one-second alarm on its way down
 *    (task removed or destroyed) and this receiver restarts the host,
 *    unless the owner deliberately stopped it.
 *  - Watchdog chain: every 5 minutes the alarm fires again; if the
 *    whole process died silently, the service is restarted here, and
 *    the next watchdog alarm is armed before returning.
 * Combined with START_STICKY and the boot receiver, the host has four
 * independent roads back to life, all respecting a deliberate stop.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (WatchdogPolicy.shouldRestart(AgtService.userStopped, AgtService.running.value)) {
            AgtService.start(context)
        }
        if (!AgtService.userStopped) {
            AgtService.armWatchdog(context)
        }
    }
}
