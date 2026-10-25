package com.fivegmag.a5gmsmediasessionhandler.controller

/**
 * The part of a media delivery session for which metrics are reported (TS 26.510 V19.2.0 tables 8.7.3.1-1 and 9.5.3.1-1):
 * reporting starts reportingStartOffset seconds after the start of the session (zero if omitted) and stops reportingDuration seconds
 * after that (never, if omitted). A duration of zero is a single report at the start offset.
 */
class ReportingWindow(startOffsetSeconds: Long?, durationSeconds: Long?) {
    private val startMs: Long = (startOffsetSeconds ?: 0L).coerceAtLeast(0L) * 1000L
    private val endMs: Long? = durationSeconds?.coerceAtLeast(0L)?.let { startMs + it * 1000L }

    /** A single report at the start offset, not periodic ones */
    val isSingleReport: Boolean = durationSeconds == 0L

    /** The time since the start of the session at which reporting begins */
    val startOffsetMs: Long get() = startMs

    fun hasStarted(elapsedMs: Long): Boolean = elapsedMs >= startMs

    fun hasEnded(elapsedMs: Long): Boolean = endMs != null && elapsedMs > endMs

    fun contains(elapsedMs: Long): Boolean = hasStarted(elapsedMs) && !hasEnded(elapsedMs)
}
