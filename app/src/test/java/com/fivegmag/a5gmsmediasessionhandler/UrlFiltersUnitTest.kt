package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.matchesUrlFilters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFiltersUnitTest {
    // without urlFilters all media delivery sessions are reported
    @Test
    fun noFiltersMeanAll() {
        assertTrue(matchesUrlFilters(null, "http://as.example/a.mpd"))
        assertTrue(matchesUrlFilters(emptyList(), "http://as.example/a.mpd"))
    }

    @Test
    fun aMatchingPatternSelectsTheSession() {
        assertTrue(matchesUrlFilters(listOf("^https://other", "live/.*\\.mpd$"), "https://as.example/live/a.mpd"))
    }

    @Test
    fun noMatchMeansNoReporting() {
        assertFalse(matchesUrlFilters(listOf("vod/"), "https://as.example/live/a.mpd"))
        assertFalse(matchesUrlFilters(listOf("vod/"), null))
    }

    @Test
    fun anInvalidPatternMatchesNothing() {
        assertFalse(matchesUrlFilters(listOf("("), "https://as.example/live/a.mpd"))
    }
}
