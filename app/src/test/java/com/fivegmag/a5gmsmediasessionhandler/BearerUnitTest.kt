package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.models.bearer
import com.fivegmag.a5gmsmediasessionhandler.network.IConsumptionReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header

class BearerUnitTest {
    @Test
    fun anAccessTokenIsPresentedAsABearerToken() {
        assertEquals("Bearer abc", bearer("abc"))
    }

    @Test
    fun withoutAnAccessTokenNoHeaderIsSent() {
        assertNull(bearer(null))
    }

    @Test
    fun everyRequestToTheMediaAfCarriesTheAuthorizationHeader() {
        for ((api, name) in listOf(
            IServiceAccessInformationApi::class.java to "fetchServiceAccessInformation",
            IConsumptionReportingApi::class.java to "sendConsumptionReport",
            IMetricsReportingApi::class.java to "sendMetricsReport"
        )) {
            val method = api.methods.first { it.name == name }
            val headers = method.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<Header>().map { it.value }
            assertTrue("$name has no Authorization header", headers.contains("Authorization"))
        }
    }
}
