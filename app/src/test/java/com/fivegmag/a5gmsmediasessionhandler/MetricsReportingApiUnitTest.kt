package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.Headers

class MetricsReportingApiUnitTest {
    // TS 26.247 clause 10.6.1: the MIME type of an XML-formatted QoE report
    @Test
    fun qoeReportIsSentWithTheQoeReportMediaType() {
        val method = IMetricsReportingApi::class.java.methods.first { it.name == "sendMetricsReport" }
        val headers = method.getAnnotation(Headers::class.java)!!.value
        assertEquals(listOf("Content-Type: " + ContentTypes.QOE_REPORT_XML), headers.toList())
        assertEquals("application/3gpdash-qoe-report+xml", ContentTypes.QOE_REPORT_XML)
    }
}
