package com.novainc.agt.core

/**
 * The AGT notification catalogue. Every alert the phone shows belongs
 * to a kind, and every kind owns an Android notification channel with
 * its own importance, so the owner can tune each family separately in
 * system settings and critical events can never be drowned by status
 * noise. Pure mapping, unit tested.
 *
 * Kinds:
 *  - critical: the host itself is hurt (deployment crash loop, tunnel
 *    down). High importance, heads-up, vibration.
 *  - alert: Phoenix pushed alerts and job failures. High importance.
 *  - security: pairing events and rejected signatures. High importance.
 *  - job: agent job lifecycle (done). Default importance.
 *  - status: recoveries and quiet state changes. Low importance, no
 *    sound, still visible in the shade and the widget feed.
 */
object NotifyKind {
    const val CRITICAL = "critical"
    const val ALERT = "alert"
    const val SECURITY = "security"
    const val JOB = "job"
    const val STATUS = "status"

    const val CHANNEL_CRITICAL = "agt_critical"
    const val CHANNEL_ALERTS = "agt_alerts"
    const val CHANNEL_SECURITY = "agt_security"
    const val CHANNEL_JOBS = "agt_jobs"
    const val CHANNEL_STATUS = "agt_status"

    fun channelFor(kind: String?): String = when (kind?.lowercase()) {
        CRITICAL -> CHANNEL_CRITICAL
        SECURITY -> CHANNEL_SECURITY
        JOB -> CHANNEL_JOBS
        STATUS -> CHANNEL_STATUS
        else -> CHANNEL_ALERTS
    }

    fun isHigh(kind: String?): Boolean = when (kind?.lowercase()) {
        CRITICAL, ALERT, SECURITY -> true
        else -> false
    }
}
