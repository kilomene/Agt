package com.novainc.agt.core

/**
 * The watchdog policy: an alarm chain that checks every 5 minutes
 * whether the host service is alive and brings it back if Android
 * killed the whole process (the one death onDestroy never sees).
 * Pure decision logic, unit tested; the alarm plumbing lives in
 * AgtService.armWatchdog and RestartReceiver.
 */
object WatchdogPolicy {
    const val INTERVAL_MS = 300_000L

    /** Restart only when the owner did not stop the host on purpose
     * and the service is not already running. */
    fun shouldRestart(userStopped: Boolean, serviceRunning: Boolean): Boolean =
        !userStopped && !serviceRunning
}
