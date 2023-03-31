/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface ServiceAccessInformationApi {

    @GET("service-access-information/{provisioningSessionId}")
    fun fetchServiceAccessInformation(@Path("provisioningSessionId") provisioningSessionId: String?): Call<ServiceAccessInformation>?

}