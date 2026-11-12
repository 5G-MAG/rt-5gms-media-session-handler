package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyInvocationConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyRequests
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyService
import com.fivegmag.a5gmsmediasessionhandler.controller.FlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.OffsetDateTime

/** TS 26.510 V19.2.0 table 9.2.3.1-1: sdfMethods lists the Service Data Flow description methods to be used for the application flows */
class DynamicPolicySdfMethodsUnitTest {
    private val af = MediaAfDouble()
    private val base = "provisioning-sessions/ps1/dynamic-policies/"
    private val response = """{"dynamicPolicyId":"d1","provisioningSessionId":"ps1","sessionId":"s1","policyTemplateId":"pt-qos",
        "applicationFlowBindings":[{"componentReference":"c","applicationFlowDescription":{"filterMethod":"5_TUPLE"}}],"qosEnforcement":true}"""
    private var ueAddress: String? = "10.0.0.2"

    private val endpoints = object : FlowEndpoints {
        override fun ueIpv4Address() = ueAddress
        override fun resolve(domainName: String) = listOf("192.0.2.1")
    }

    @After
    fun stop() = af.close()

    private fun activate(vararg sdfMethods: String, endpoints: FlowEndpoints? = this.endpoints): DynamicPolicyService {
        val sai = ServiceAccessInformation(
            "ps1", null, null, null, null,
            dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(arrayListOf(af.baseUrl), arrayListOf(PolicyTemplateBinding("sop-qos", "pt-qos")), arrayListOf(*sdfMethods))
        )
        val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
        val client = DynamicPolicyM5Client(retrofit.create(IDynamicPolicyApi::class.java), null)
        af.answer("POST", base, MediaAfDouble.Response(201, response, "http://af/d1"))
        return DynamicPolicyService("s1", { sai }, { client }, { endpoints }, { listOf("media.example") }, { OffsetDateTime.now() }, {}).also { it.activate("sop-qos", null) }
    }

    private fun sentFlow() = JsonParser().parse(af.requests.single().body).asJsonObject["applicationFlowBindings"].asJsonArray[0].asJsonObject["applicationFlowDescription"].asJsonObject

    @Test
    fun theFiveTupleIsUsedWhenItIsListed() {
        activate("DOMAIN_NAME", "5_TUPLE", "2_TUPLE")
        assertEquals("5_TUPLE", sentFlow()["filterMethod"].asString)
        assertEquals("192.0.2.1", sentFlow()["packetFilter"].asJsonObject["destinationAddress"].asJsonObject["ipv4Addr"].asString)
    }

    @Test
    fun aTwoTupleHasTheAddressesOnly() {
        activate("2_TUPLE")
        val flow = sentFlow()
        assertEquals("2_TUPLE", flow["filterMethod"].asString)
        val filter = flow["packetFilter"].asJsonObject
        assertEquals("10.0.0.2", filter["sourceAddress"].asJsonObject["ipv4Addr"].asString)
        assertEquals("192.0.2.1", filter["destinationAddress"].asJsonObject["ipv4Addr"].asString)
        assertTrue(!filter.has("protocolNumber") && !filter.has("sourcePort") && !filter.has("destinationPort"))
        assertTrue(!flow.has("domainName"))
    }

    // exactly one of packetFilter and domainName; the addresses of the UE and of the Media AS are not needed
    @Test
    fun aDomainNameIsDescribedByTheDomainNameAlone() {
        activate("TYPE_OF_SERVICE_MARKING", "DOMAIN_NAME", endpoints = null)
        val flow = sentFlow()
        assertEquals("DOMAIN_NAME", flow["filterMethod"].asString)
        assertEquals("media.example", flow["domainName"].asString)
        assertTrue(!flow.has("packetFilter"))
    }

    @Test
    fun theFirstListedOfTheOthersIsUsed() {
        activate("DOMAIN_NAME", "2_TUPLE")
        assertEquals("DOMAIN_NAME", sentFlow()["filterMethod"].asString)
    }

    @Test
    fun noMethodThatTheClientCanBuildSendsNothing() {
        val service = activate("TYPE_OF_SERVICE_MARKING", "FLOW_LABEL")
        assertTrue(af.requests.isEmpty())
        assertNotNull(service.lastFailure)
    }

    @Test
    fun theChoiceOfTheMethod() {
        assertEquals("5_TUPLE", DynamicPolicyRequests.filterMethodFor(listOf("2_TUPLE", "5_TUPLE")))
        assertEquals("2_TUPLE", DynamicPolicyRequests.filterMethodFor(listOf("FLOW_LABEL", "2_TUPLE", "DOMAIN_NAME")))
        assertNull(DynamicPolicyRequests.filterMethodFor(listOf("FLOW_LABEL")))
        // the list is mandatory; where it is not there the 5_TUPLE of before stays
        assertEquals("5_TUPLE", DynamicPolicyRequests.filterMethodFor(null))
        assertEquals("5_TUPLE", DynamicPolicyRequests.filterMethodFor(emptyList()))
    }
}
