/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.POST

interface IConsumptionReportingApi {
    @Headers("Content-Type: ${ContentTypes.JSON}")
    // TS 26.510 V19.2.0 clause 9.6.2: the base URL is one of the serverAddresses of the Service Access Information
    @POST("provisioning-sessions/{provisioningSessionId}/consumption-reporting/")
    fun sendConsumptionReport(
        @Path("provisioningSessionId") provisioningSessionId: String?,
        @Header("Authorization") authorization: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?
}