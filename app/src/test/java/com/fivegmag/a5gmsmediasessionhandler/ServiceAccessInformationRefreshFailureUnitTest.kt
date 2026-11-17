package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Properties

/** TS 26.510 V19.2.0 clause 5.3.2.3: "the Media Session Handler shall periodically check for updated Service Access Information" */
class ServiceAccessInformationRefreshFailureUnitTest {
    private val af = MediaAfDouble()
    private val retrofitBuilder = Retrofit.Builder().addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val sessions = HashMap<Int, ClientSessionData>()
    private val controller = ServiceAccessInformationController(sessions, retrofitBuilder)

    init {
        controller.initialize(Properties().apply { setProperty("defaultServiceAccessInformationTimerInterval", "1") })
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?))
        val data = sessions[1]!!.serviceAccessInformationSessionData
        data.api = retrofitBuilder.baseUrl(af.baseUrl).build().create(IServiceAccessInformationApi::class.java)
        data.m5BaseUrl = af.baseUrl
    }

    @After
    fun stop() {
        controller.stopUpdateTimer(1)
        af.close()
    }

    @Test
    fun aCheckThatGetsNoAnswerIsFollowedByTheNextOne() {
        af.answer("GET", "service-access-information/ps1", MediaAfDouble.Response(200, """{"provisioningSessionId":"ps1"}""", headers = mapOf("Cache-Control" to "max-age=1")))
        runBlocking { controller.updateServiceAccessInformation(ServiceListEntry("ps1", "service", null), 1) }
        val data = sessions[1]!!.serviceAccessInformationSessionData
        val armed = data.requestTimer
        assertNotNull(armed)
        // the Media AF is gone: the check that is due fails
        af.close()
        val deadline = System.currentTimeMillis() + 6_000
        while (data.requestTimer === armed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the timer of the next check was not armed after the failed one", data.requestTimer !== armed)
    }
}
