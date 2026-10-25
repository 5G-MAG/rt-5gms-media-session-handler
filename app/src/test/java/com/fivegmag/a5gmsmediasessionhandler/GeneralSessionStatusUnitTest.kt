package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlingStates
import com.fivegmag.a5gmsmediasessionhandler.models.GeneralSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneralSessionStatusUnitTest {
    // TS 26.510 V19.2.0 clause 11.2.2.1: ACTIVE if all actions are successful
    @Test
    fun successGivesActive() {
        val status = GeneralSessionStatus.after("news", "https://as.example/manifest.mpd", true)
        assertEquals(SessionHandlingStates.ACTIVE, status.sessionHandlingState)
        assertEquals("news", status.contentIdentifier)
        assertEquals("https://as.example/manifest.mpd", status.mediaEntryPointUrl)
    }

    // ... and ERRORED if any of them fails
    @Test
    fun failureGivesErrored() {
        assertEquals(SessionHandlingStates.ERRORED, GeneralSessionStatus.after(null, null, false).sessionHandlingState)
    }
}
