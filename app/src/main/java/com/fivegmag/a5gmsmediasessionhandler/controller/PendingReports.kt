package com.fivegmag.a5gmsmediasessionhandler.controller

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        // the three formats of an HTTP-date that a recipient accepts (RFC 9110 clause 5.6.7)
        private val httpDateFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            "EEEE, dd-MMM-yy HH:mm:ss 'GMT'",
            "EEE MMM d HH:mm:ss yyyy"
        ).map { DateTimeFormatter.ofPattern(it, Locale.US).withZone(ZoneOffset.UTC) }

        private fun httpDate(value: String): Instant? {
            for (format in httpDateFormats) {
                try {
                    return Instant.from(format.parse(value))
                } catch (_: java.time.DateTimeException) {
                    // try the next format
                }
            }
            return null
        }

        /**
         * The seconds to wait: the Retry-After header as delay-seconds or as an HTTP-date (RFC 9110 clause 10.2.3; TS 26.510 V19.2.0 clauses
         * 5.3.5.2 and 5.3.6.2 say it indicates when the Media AF can take new submissions), otherwise defaultSeconds. A date that has passed
         * gives 0.
         */
        fun retryDelaySeconds(retryAfter: String?, defaultSeconds: Long, now: Instant = Instant.now()): Long {
            val value = retryAfter?.trim() ?: return defaultSeconds
            value.toLongOrNull()?.let { return if (it >= 0) it else defaultSeconds }
            val date = httpDate(value) ?: return defaultSeconds
            val wait = Duration.between(now, date)
            return if (wait.isNegative) 0 else (wait.toMillis() + 999) / 1000
        }
    }
}
