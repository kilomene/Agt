package com.novainc.agt.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the host the moment the phone finishes booting. This is the piece
 * the old Termux stack never had: no human has to open anything for the
 * server to come back after a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AgtService.start(context)
        }
    }
}
