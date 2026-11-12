package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.CellIdentifierType
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyInvocationConfiguration
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.TypedLocation
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyService
import com.fivegmag.a5gmsmediasessionhandler.controller.FlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.controller.LocationFilter
import com.fivegmag.a5gmsmediasessionhandler.controller.NetworkAssistanceService
import com.fivegmag.a5gmsmediasessionhandler.controller.UeCell
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
import com.fivegmag.a5gmsmediasessionhandler.network.INetworkAssistanceApi
import com.fivegmag.a5gmsmediasessionhandler.network.NetworkAssistanceM5Client
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.OffsetDateTime

/**
 * TS 26.510 V19.2.0 tables 9.3.3.1-1 and 9.4.3.1-1: location (TypedLocation) of the UE when the Dynamic Policy or the Network Assistance
 * Session was created or last updated, present when locationReporting of the Service Access Information is true (table 9.2.3.1-1)
 */
class UeLocationInRequestsUnitTest {
    private val af = MediaAfDouble()
    private val cell = TypedLocation(CellIdentifierType.NCGI, "001016733824")
    private val endpoints = object : FlowEndpoints {
        override fun ueIpv4Address() = "10.0.0.2"
        override fun resolve(domainName: String) = listOf("192.0.2.1")
    }

    @After
    fun stop() = af.close()

    private fun retrofit() = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()

    private fun dynamicPolicyRequest(locationReporting: Boolean, location: TypedLocation?): JsonObject {
        val sai = ServiceAccessInformation(
            "ps1", null, null, null, null, locationReporting = locationReporting,
            dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(arrayListOf(af.baseUrl), arrayListOf(PolicyTemplateBinding("sop", "pt")), arrayListOf("5_TUPLE"))
        )
        af.answer("POST", "provisioning-sessions/ps1/dynamic-policies/", MediaAfDouble.Response(201, """{"dynamicPolicyId":"d1","provisioningSessionId":"ps1","sessionId":"s1","policyTemplateId":"pt","applicationFlowBindings":[{"componentReference":"c","applicationFlowDescription":{"filterMethod":"5_TUPLE"}}]}""", "http://af/d1"))
        val client = DynamicPolicyM5Client(retrofit().create(IDynamicPolicyApi::class.java), null)
        DynamicPolicyService("s1", { sai }, { client }, { endpoints }, { listOf("media.example") }, { OffsetDateTime.now() }, {}, { location }).activate("sop", null)
        return JsonParser().parse(af.requests.single().body).asJsonObject
    }

    private fun networkAssistanceRequest(locationReporting: Boolean, location: TypedLocation?): JsonObject {
        val sai = ServiceAccessInformation("ps1", null, null, null, null, locationReporting = locationReporting, networkAssistanceConfiguration = NetworkAssistanceConfiguration(arrayListOf(af.baseUrl)))
        af.answer("POST", "provisioning-sessions/ps1/network-assistance-sessions/", MediaAfDouble.Response(201, """{"naSessionId":"n1","provisioningSessionId":"ps1","sessionId":"s1","applicationFlowDescription":{"filterMethod":"5_TUPLE"}}"""))
        af.answer("GET", "provisioning-sessions/ps1/network-assistance-sessions/n1/recommendation", MediaAfDouble.Response(404))
        val client = NetworkAssistanceM5Client(retrofit().create(INetworkAssistanceApi::class.java), null)
        NetworkAssistanceService("s1", { sai }, { client }, { endpoints }, { listOf("media.example") }, { null }, { null }, {}, { location }).requestBitRateRecommendation("video")
        return JsonParser().parse(af.requests.first { it.method == "POST" }.body).asJsonObject
    }

    @Test
    fun theDynamicPolicyCarriesTheLocationWhenLocationReportingIsTrue() {
        val location = dynamicPolicyRequest(true, cell)["location"].asJsonObject
        assertEquals("NCGI", location["locationIdentifierType"].asString)
        assertEquals("001016733824", location["location"].asString)
    }

    @Test
    fun theDynamicPolicyCarriesNoLocationWhenLocationReportingIsFalse() {
        assertFalse(dynamicPolicyRequest(false, cell).has("location"))
    }

    @Test
    fun theNetworkAssistanceSessionCarriesTheLocationWhenLocationReportingIsTrue() {
        assertEquals("001016733824", networkAssistanceRequest(true, cell)["location"].asJsonObject["location"].asString)
    }

    @Test
    fun theNetworkAssistanceSessionCarriesNoLocationWhenLocationReportingIsFalse() {
        assertFalse(networkAssistanceRequest(false, cell).has("location"))
    }

    @Test
    fun withoutAKnownCellNoLocationIsSent() {
        assertFalse(dynamicPolicyRequest(true, null).has("location"))
    }

    // the form of the Media Stream Handler for consumption reports: MCC, MNC and the cell identity
    @Test
    fun theCellIsTheNcgiOrTheEcgiOfTheRegisteredCell() {
        assertEquals(cell, LocationFilter.typedLocationOf(UeCell("001", "01", 1, 6733824, null)))
        assertEquals(TypedLocation(CellIdentifierType.ECGI, "00101123456"), LocationFilter.typedLocationOf(UeCell("001", "01", 1, null, 123456)))
        assertNull(LocationFilter.typedLocationOf(UeCell("001", "01", 1, null, null)))
        assertNull(LocationFilter.typedLocationOf(UeCell(null, null, 1, 5, null)))
        assertNull(LocationFilter.typedLocationOf(null))
    }
}
