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
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.POST

interface ConsumptionReportingApi {
    @Headers("Content-Type: ${ContentTypes.JSON}")
    @POST("consumption-reporting/{provisioningSessionId}")
    fun sendConsumptionReport(
        @Path("provisioningSessionId") provisioningSessionId: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?
}