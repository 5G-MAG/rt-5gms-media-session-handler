package com.fivegmag.a5gmsmediasessionhandler

import android.net.NetworkCapabilities
import com.fivegmag.a5gmscommonlibrary.models.Snssai
import com.fivegmag.a5gmsmediasessionhandler.controller.DataNetwork
import com.fivegmag.a5gmsmediasessionhandler.controller.SliceId
import com.fivegmag.a5gmsmediasessionhandler.controller.SliceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceAndDataNetworkUnitTest {
    // TS 26.510 V19.2.0 table 8.7.3.1-1: without sliceScope metrics are reported regardless of network slice
    @Test
    fun noScopeMeansAnySlice() {
        assertTrue(SliceScope.applies(null, null))
        assertTrue(SliceScope.applies(emptyList(), listOf(SliceId(1, null))))
    }

    @Test
    fun theSliceMustBeInTheSlicingConfigurationOfTheUe() {
        val ue = listOf(SliceId(1, 0x010203), SliceId(2, null))
        assertTrue(SliceScope.applies(listOf(Snssai(1, "010203")), ue))
        assertTrue(SliceScope.applies(listOf(Snssai(2)), ue))
        assertTrue(SliceScope.applies(listOf(Snssai(1)), ue))
        assertFalse(SliceScope.applies(listOf(Snssai(1, "ffffff")), ue))
        assertFalse(SliceScope.applies(listOf(Snssai(3)), ue))
    }

    @Test
    fun aUeThatDoesNotTellItsSlicesIsNotInTheScope() {
        assertFalse(SliceScope.applies(listOf(Snssai(1)), null))
    }

    // the default Data Network needs no network request, a name with a network capability is mapped, any other cannot be selected
    @Test
    fun dataNetworkNamesAreMappedToCapabilities() {
        assertEquals(DataNetwork.Selection.Default, DataNetwork.select(null))
        assertEquals(DataNetwork.Selection.Default, DataNetwork.select("internet"))
        assertEquals(DataNetwork.Selection.Capability(NetworkCapabilities.NET_CAPABILITY_IMS), DataNetwork.select("IMS"))
        assertEquals(DataNetwork.Selection.Unsupported, DataNetwork.select("private.mno.example"))
    }
}
