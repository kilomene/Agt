package com.novainc.agt

import com.novainc.agt.core.CpuSampler
import com.novainc.agt.ui.formatDuration
import com.novainc.agt.ui.formatLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v1.4.0 design helpers: the banner uptime form, the legs card
 * latency form, and the CPU sampler math behind the telemetry tile. */
class AgtFormatTest {

    @Test
    fun durationUnderADayHasNoDayPrefix() {
        assertEquals("00:00:00", formatDuration(0))
        assertEquals("01:01:01", formatDuration(3_661_000))
        assertEquals("23:59:59", formatDuration(86_399_000))
    }

    @Test
    fun durationOverADayMatchesTheDesignForm() {
        val ms = (6L * 86_400 + 14L * 3_600 + 22L * 60 + 8) * 1000
        assertEquals("6d 14:22:08", formatDuration(ms))
    }

    @Test
    fun durationNeverGoesNegative() {
        assertEquals("00:00:00", formatDuration(-5_000))
    }

    @Test
    fun latencyIsEmptyUntilMeasured() {
        assertEquals("", formatLatency(null))
        assertEquals("38 ms", formatLatency(38))
        assertEquals("112 ms", formatLatency(112))
    }

    @Test
    fun cpuPercentBetweenComputesBusyShare() {
        val prev = longArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        // Total delta 1000, idle+iowait delta 800: 20 percent busy.
        val cur = longArrayOf(100, 0, 100, 700, 100, 0, 0, 0, 0, 0)
        assertEquals(20f, CpuSampler.percentBetween(prev, cur), 0.01f)
    }

    @Test
    fun cpuPercentBetweenHandlesFullBusyAndNoDelta() {
        val prev = longArrayOf(0, 0, 0, 500, 0, 0, 0, 0, 0, 0)
        val busy = longArrayOf(250, 0, 250, 500, 0, 0, 0, 0, 0, 0)
        assertEquals(100f, CpuSampler.percentBetween(prev, busy), 0.01f)
        assertEquals(0f, CpuSampler.percentBetween(prev, prev), 0.01f)
    }

    @Test
    fun cpuLineParsing() {
        val parsed = CpuSampler.parseCpuLine("cpu  100 0 100 800 12 0 3 0 0 0")
        assertTrue(parsed != null)
        assertEquals(100L, parsed!![0])
        assertEquals(800L, parsed[3])
        assertNull(CpuSampler.parseCpuLine("intr 1 2 3"))
        assertNull(CpuSampler.parseCpuLine("cpu x y z"))
    }
}
