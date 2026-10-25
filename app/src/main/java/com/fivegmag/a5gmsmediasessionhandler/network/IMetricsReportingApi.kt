package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

interface IMetricsReportingApi {
    @Headers("Content-Type: ${ContentTypes.QOE_REPORT_XML}")
    // TS 26.510 V19.2.0 clause 9.5.2
    @POST("provisioning-sessions/{provisioningSessionId}/metrics-reporting/{metricsReportingConfigurationId}")
    fun sendMetricsReport(
        @Path("provisioningSessionId") provisioningSessionId: String?,
        @Path("metricsReportingConfigurationId") metricsReportingConfigurationId: String?,
        @Header("Authorization") authorization: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?

    /** A report to the reporting server of a manifest configuration (TS 26.247 V19.1.0 clause 10.6.3: an HTTP POST with the XML report) */
    @POST
    fun sendMetricsReportToServer(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Header("Content-Encoding") contentEncoding: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?
}