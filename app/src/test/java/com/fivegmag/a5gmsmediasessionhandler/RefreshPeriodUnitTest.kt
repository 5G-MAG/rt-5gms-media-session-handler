package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import org.junit.Assert.assertEquals
import okhttp3.Headers
import org.junit.Test
import retrofit2.Retrofit

class RefreshPeriodUnitTest {
    private val controller = ServiceAccessInformationController(HashMap(), Retrofit.Builder())

    @Test
    fun aMaxAgeOfZeroIsRefreshedAfterOneSecond() = assertEquals(1L, controller.getRefreshTimerValue(0, -1, 10))

    @Test
    fun anAgeThatExceedsTheMaxAgeIsRefreshedAfterOneSecond() = assertEquals(1L, controller.getRefreshTimerValue(-5, -1, 10))

    @Test
    fun theMaxAgeIsUsedWhenItIsLargerThanOneSecond() = assertEquals(60L, controller.getRefreshTimerValue(60, -1, 10))

    @Test
    fun theSmallerOfMaxAgeAndExpiresIsUsed() = assertEquals(20L, controller.getRefreshTimerValue(60, 20, 10))

    @Test
    fun withoutHeadersTheConfiguredDefaultIsUsed() = assertEquals(10L, controller.getRefreshTimerValue(-1, -1, 10))

    @Test
    fun aDefaultOfZeroIsRaisedToOneSecond() = assertEquals(1L, controller.getRefreshTimerValue(-1, -1, 0))

    @Test
    fun theMinimumCanBeConfigured() {
        controller.initialize(java.util.Properties().apply {
            setProperty("defaultServiceAccessInformationTimerInterval", "10")
            setProperty("minServiceAccessInformationTimerInterval", "5")
        })
        assertEquals(5L, controller.getRefreshTimerValue(0, -1, 10))
        assertEquals(60L, controller.getRefreshTimerValue(60, -1, 10))
    }

    private fun httpDate(millis: Long): String {
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(millis))
    }

    @Test
    fun anExpiresInTheFutureGivesTheSecondsUntilIt() {
        val now = 1_800_000_000_000L
        val headers = Headers.of("Expires", httpDate(now + 100_000))
        assertEquals(100L, controller.getPeriodFromExpiresHeader(headers, now))
    }

    @Test
    fun anExpiresInThePastGivesZeroNotTheTimeSinceIt() {
        val now = 1_800_000_000_000L
        val headers = Headers.of("Expires", httpDate(now - 3_600_000))
        assertEquals(0L, controller.getPeriodFromExpiresHeader(headers, now))
        assertEquals(1L, controller.getRefreshTimerValue(-1, controller.getPeriodFromExpiresHeader(headers, now), 10))
    }

    @Test
    fun withoutAnExpiresHeaderThereIsNoPeriod() = assertEquals(-1L, controller.getPeriodFromExpiresHeader(Headers.of()))

    @Test
    fun anExpiresThatIsNotADateMeansAlreadyExpired() {
        assertEquals(0L, controller.getPeriodFromExpiresHeader(Headers.of("Expires", "0")))
        assertEquals(0L, controller.getPeriodFromExpiresHeader(Headers.of("Expires", "tomorrow")))
    }
}
