package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.ReportingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingWindowUnitTest {
    @Test
    fun withoutOffsetAndDurationReportingCoversTheWholeSession() {
        val window = ReportingWindow(null, null)
        assertTrue(window.contains(0))
        assertTrue(window.contains(3_600_000))
        assertFalse(window.isSingleReport)
    }

    @Test
    fun reportingStartsAtTheOffset() {
        val window = ReportingWindow(10, null)
        assertFalse(window.contains(9_999))
        assertTrue(window.contains(10_000))
        assertTrue(window.contains(600_000))
    }

    @Test
    fun reportingStopsTheDurationAfterTheOffset() {
        val window = ReportingWindow(10, 20)
        assertFalse(window.contains(5_000))
        assertTrue(window.contains(10_000))
        assertTrue(window.contains(30_000))
        assertFalse(window.contains(30_001))
        assertTrue(window.hasEnded(30_001))
    }

    @Test
    fun aDurationOfZeroIsASingleReportAtTheOffset() {
        val window = ReportingWindow(7, 0)
        assertTrue(window.isSingleReport)
        assertEquals(7_000, window.startOffsetMs)
        assertTrue(window.contains(7_000))
        assertTrue(window.hasEnded(7_001))
    }

    @Test
    fun aNegativeValueIsTakenAsZero() {
        val window = ReportingWindow(-5, -1)
        assertEquals(0, window.startOffsetMs)
        assertTrue(window.contains(0))
    }
}
