package com.fivegmag.a5gmsmediasessionhandler.controller

import java.util.Timer

/**
 * Reports that the Media AF could not take (503, TS 26.510 V19.2.0 clauses 5.3.5.2 and 5.3.6.2), kept for a later attempt. The oldest are
 * dropped beyond limit, so that an endpoint that stays unavailable cannot make the list grow without end.
 */
class PendingReports(@Volatile var limit: Int) {
    private val bodies = ArrayDeque<String>()

    /** The timer of the next attempt, if one is scheduled */
    @Volatile var retryTimer: Timer? = null

    @Synchronized fun store(body: String) {
        bodies.addLast(body)
        while (bodies.size > limit) bodies.removeFirst()
    }

    @Synchronized fun takeAll(): List<String> {
        val all = bodies.toList()
        bodies.clear()
        return all
    }

    @Synchronized fun size(): Int = bodies.size

    fun cancel() {
        retryTimer?.cancel()
        retryTimer = null
    }

    companion object {
        /** The seconds to wait: the delta-seconds of a Retry-After header, otherwise defaultSeconds */
        fun retryDelaySeconds(retryAfter: String?, defaultSeconds: Long): Long =
            retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: defaultSeconds
    }
}
