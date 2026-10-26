package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.Ecgi
import com.fivegmag.a5gmscommonlibrary.models.LocationArea5G
import com.fivegmag.a5gmscommonlibrary.models.Ncgi
import com.fivegmag.a5gmscommonlibrary.models.NetworkAreaInfo
import com.fivegmag.a5gmscommonlibrary.models.PlmnId
import com.fivegmag.a5gmscommonlibrary.models.Tai
import com.fivegmag.a5gmsmediasessionhandler.controller.LocationFilter
import com.fivegmag.a5gmsmediasessionhandler.controller.UeCell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TS 26.510 V19.2.0 table 8.7.3.1-1: metrics are collected only where the locationFilter says
class LocationFilterUnitTest {
    private val plmn = PlmnId("262", "01")
    private val nrCell = UeCell("262", "01", 0x1234, 0xABCDEF012, null)
    private val lteCell = UeCell("262", "01", 0x0042, null, 0x1A2B3C4)

    @Test
    fun withoutAFilterTheUeIsEverywhere() {
        assertTrue(LocationFilter.isLocated(null, nrCell))
        assertTrue(LocationFilter.isLocated(emptyList(), null))
    }

    @Test
    fun withAFilterAndNoKnownCellTheUeIsNowhere() {
        assertFalse(LocationFilter.isLocated(listOf(LocationArea5G(NetworkAreaInfo(tais = arrayListOf(Tai(plmn, "001234"))))), null))
    }

    @Test
    fun aTrackingAreaMatchesByPlmnAndTac() {
        val area = listOf(LocationArea5G(NetworkAreaInfo(tais = arrayListOf(Tai(plmn, "001234")))))
        assertTrue(LocationFilter.isLocated(area, nrCell))
        assertFalse(LocationFilter.isLocated(area, UeCell("262", "02", 0x1234, 0xABCDEF012, null)))
        assertFalse(LocationFilter.isLocated(area, UeCell("262", "01", 0x1235, 0xABCDEF012, null)))
    }

    @Test
    fun anNrCellMatchesByItsHexadecimalIdentity() {
        val area = listOf(LocationArea5G(NetworkAreaInfo(ncgis = arrayListOf(Ncgi(plmn, "0ABCDEF012")))))
        assertTrue(LocationFilter.isLocated(area, nrCell))
        assertFalse(LocationFilter.isLocated(area, UeCell("262", "01", 0x1234, 0x1, null)))
    }

    @Test
    fun anEutraCellMatchesByItsHexadecimalIdentity() {
        val area = listOf(LocationArea5G(NetworkAreaInfo(ecgis = arrayListOf(Ecgi(plmn, "1A2B3C4")))))
        assertTrue(LocationFilter.isLocated(area, lteCell))
        assertFalse(LocationFilter.isLocated(area, nrCell))
    }

    @Test
    fun anyOneOfSeveralAreasIsEnough() {
        val areas = listOf(
            LocationArea5G(NetworkAreaInfo(tais = arrayListOf(Tai(plmn, "00FFFF")))),
            LocationArea5G(NetworkAreaInfo(tais = arrayListOf(Tai(plmn, "001234"))))
        )
        assertTrue(LocationFilter.isLocated(areas, nrCell))
    }

    @Test
    fun anAreaWithoutANetworkAreaNeverMatches() {
        assertFalse(LocationFilter.isLocated(listOf(LocationArea5G(null)), nrCell))
    }
}
