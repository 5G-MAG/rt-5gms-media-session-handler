package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmscommonlibrary.models.StreamingAccess
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ControllerSimplificationTest {
    private val sessionController = SessionController(hashMapOf())
    private val staticEntries =
        arrayListOf(EntryPoint("https://example.test/static.mpd", ContentTypes.DASH, null))
    private val dynamicEntries =
        arrayListOf(EntryPoint("https://example.test/dynamic.mpd", ContentTypes.DASH, null))
    private val sai = ServiceAccessInformation(
        "session", null, StreamingAccess(dynamicEntries), null, null
    )

    @Test
    fun staticEntryPointsTakePrecedenceOverSai() {
        assertSame(
            staticEntries,
            sessionController.getFinalEntryPoints(ServiceListEntry("session", "item", staticEntries), sai)
        )
    }

    @Test
    fun missingOrEmptyStaticEntryPointsUseSai() {
        for (entries in listOf(null, arrayListOf<EntryPoint>())) {
            assertSame(
                dynamicEntries,
                sessionController.getFinalEntryPoints(ServiceListEntry("session", "item", entries), sai)
            )
        }
    }

    @Test
    fun missingSaiPreservesNullEmptyAndNonemptyStaticEntryPoints() {
        for (entries in listOf(null, arrayListOf<EntryPoint>(), staticEntries)) {
            assertSame(
                entries,
                sessionController.getFinalEntryPoints(ServiceListEntry("session", "item", entries), null)
            )
        }
    }

    @Test
    fun expiresPeriodStillDiscardsCurrentMilliseconds() {
        for ((header, expiration) in listOf(
            "Fri, 01 Jan 2100 00:00:00 GMT" to 4_102_444_800L,
            "Thu, 01 Jan 1970 00:00:00 GMT" to 0L
        )) {
            val headers = Headers.Builder().add("Expires", header).build()
            val before = System.currentTimeMillis() / 1000
            val period = expiresPeriod(headers)
            val after = System.currentTimeMillis() / 1000
            val first = abs(before - expiration)
            val last = abs(after - expiration)
            assertTrue("Unexpected whole-second period: $period", period in minOf(first, last)..maxOf(first, last))
        }
    }

    @Test
    fun missingOrMalformedExpiresHeaderStillReturnsMinusOne() {
        assertEquals(-1L, expiresPeriod(Headers.Builder().build()))
        assertEquals(-1L, expiresPeriod(Headers.Builder().add("Expires", "invalid").build()))
    }

    private fun expiresPeriod(headers: Headers): Long {
        val controller = ServiceAccessInformationController(hashMapOf(), Retrofit.Builder())
        val method = ServiceAccessInformationController::class.java.getDeclaredMethod(
            "getPeriodFromExpiresHeader", Headers::class.java
        )
        method.isAccessible = true
        return method.invoke(controller, headers) as Long
    }
}
