package com.novainc.agt.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the host back after Android kills the service or the owner
 * swipes the app away: AgtService schedules a one-second alarm on its
 * way down (unless the owner deliberately stopped it), and this
 * receiver is what the alarm fires. Combined with the boot receiver
 * and START_STICKY, the host has three independent roads back to life.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AgtService.userStopped) {
            AgtService.start(context)
        }
    }
}
