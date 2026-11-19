package com.fivegmag.a5gmsmediasessionhandler.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** The HTTP-date of RFC 9110 clause 5.6.7, as a recipient reads it: the three formats are accepted, all in UTC */
object HttpDate {
    /** A date that is not one of the three formats gives null */
    fun parse(value: String, now: Instant = Instant.now()): Instant? {
        val text = value.trim()
        for (format in formats(now)) {
            try {
                return Instant.from(format.parse(text))
            } catch (_: java.time.DateTimeException) {
                // try the next format
            }
        }
        return null
    }

    private fun formats(now: Instant): List<DateTimeFormatter> {
        // rfc850-date has two digits for the year: one that appears to be more than 50 years in the future is the most recent year of the
        // past with those digits (clause 5.6.7), so the window of the two digits starts 49 years back
        val firstYear = now.atZone(ZoneOffset.UTC).year - 49
        val rfc850 = DateTimeFormatterBuilder()
            .appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, firstYear)
            .appendPattern(" HH:mm:ss 'GMT'")
            .toFormatter(Locale.US)
        return listOf(
            // IMF-fixdate
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US),
            rfc850,
            // asctime-date: a day of one digit is padded with a space (date3)
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)
        ).map { it.withZone(ZoneOffset.UTC) }
    }
}
