package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmsmediasessionhandler.controller.SessionController
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryPointOrderUnitTest {
    private fun entry(locator: String, contentType: String?, vararg profiles: String) =
        EntryPoint(locator, contentType, if (profiles.isEmpty()) null else arrayListOf(*profiles))

    private fun order(entries: List<EntryPoint>, supported: Set<String> = emptySet()) =
        SessionController.orderEntryPoints(entries, supported).map { it.locator }

    @Test
    fun dashComesBeforeHlsBeforeTheOthersAndTheOrderWithinATypeIsKept() {
        val entries = listOf(
            entry("x", "video/mp4"), entry("h1", ContentTypes.HLS), entry("d1", ContentTypes.DASH),
            entry("h2", ContentTypes.HLS), entry("d2", ContentTypes.DASH)
        )
        assertEquals(listOf("d1", "d2", "h1", "h2", "x"), order(entries))
    }

    @Test
    fun anEntryPointOfAnUnsupportedProfileIsLeftOut() {
        val entries = listOf(entry("a", ContentTypes.DASH, "urn:p:a"), entry("b", ContentTypes.DASH, "urn:p:b"), entry("c", ContentTypes.DASH))
        assertEquals(listOf("b", "c"), order(entries, setOf("urn:p:b")))
    }

    @Test
    fun withoutAProfileListEverythingIsOffered() {
        val entries = listOf(entry("a", ContentTypes.DASH, "urn:p:a"), entry("b", ContentTypes.HLS))
        assertEquals(listOf("a", "b"), order(entries))
    }

    @Test
    fun anEntryPointWithOneSupportedProfileOfSeveralStays() {
        assertEquals(listOf("a"), order(listOf(entry("a", ContentTypes.DASH, "urn:p:x", "urn:p:y")), setOf("urn:p:y")))
    }
}
