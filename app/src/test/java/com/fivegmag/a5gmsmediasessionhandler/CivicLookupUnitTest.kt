package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.CivicAddress
import com.fivegmag.a5gmscommonlibrary.models.LocationArea5G
import com.fivegmag.a5gmsmediasessionhandler.controller.CivicLookup
import com.fivegmag.a5gmsmediasessionhandler.controller.LocationFilter
import com.fivegmag.a5gmsmediasessionhandler.controller.UeCivic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TS 29.572 V19.7.0 clause 6.1.6.2.14: the UE is at a civic address when it is at every element that the address names
class CivicLookupUnitTest {
    private val ue = UeCivic("DE", "Bayern", "Oberbayern", "Munich", "Schwabing", "Leopoldstrasse", "12", "80802")

    @Test
    fun countryOnlyIsTheCountry() {
        assertTrue(CivicLookup.matches(CivicAddress(country = "DE"), ue))
        assertFalse(CivicLookup.matches(CivicAddress(country = "FR"), ue))
    }

    @Test
    fun everyNamedElementMustMatchWithoutCase() {
        assertTrue(CivicLookup.matches(CivicAddress(country = "de", a3 = " munich ", pc = "80802"), ue))
        assertFalse(CivicLookup.matches(CivicAddress(country = "DE", a3 = "Munich", pc = "80803"), ue))
    }

    @Test
    fun anElementThePlatformCannotGiveIsNotMatched() {
        assertFalse(CivicLookup.matches(CivicAddress(country = "DE", flr = "3"), ue))
        assertFalse(CivicLookup.matches(CivicAddress(country = "DE", a5 = "x"), ue))
    }

    @Test
    fun anUnknownAddressMatchesNothing() {
        assertFalse(CivicLookup.matches(CivicAddress(country = "DE"), null))
    }

    @Test
    fun theLocationFilterUsesTheCivicAddresses() {
        val filter = listOf(LocationArea5G(civicAddresses = arrayListOf(CivicAddress(country = "FR"), CivicAddress(country = "DE", a3 = "Munich"))))
        assertTrue(LocationFilter.isLocated(filter, null, null, ue))
        assertFalse(LocationFilter.isLocated(filter, null, null, null))
    }
}
