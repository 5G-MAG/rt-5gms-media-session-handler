package com.fivegmag.a5gmsmediasessionhandler

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.service.IncomingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TS 26.510 V19.2.0 clauses 5.4.2.2 and 6.3, TS 26.512 V19.3.0 clause 4.8.3.4: the Media Session Handler returns an HTTP status to the application
 * that handed it a 3GPP Service URL; a URL that it does not accept gets an error response and causes no M5 traffic. The reply carries the status
 * and no media delivery session identifier (clause 5.4.2.2).
 */
class LaunchServiceUrlUnitTest {
    private val af = MediaAfDouble()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing = mockk<OutgoingMessageHandler>(relaxed = true)
    private val handler = IncomingMessageHandler(sessions, outgoing)
    private val replyTo = Messenger(null as Handler?)

    @Before
    fun mockReplies() {
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putInt(any(), any()) } returns Unit
        every { anyConstructed<Bundle>().putString(any(), any()) } returns Unit
    }

    @After
    fun stop() {
        unmockkConstructor(Bundle::class)
        af.close()
    }

    private fun launch(url: String?) {
        val bundle = mockk<Bundle>(relaxed = true)
        every { bundle.getString(SessionHandlerBundleKeys.SERVICE_URL) } returns url
        val msg = mockk<Message>(relaxed = true)
        every { msg.data } returns bundle
        msg.replyTo = replyTo
        msg.what = SessionHandlerMessageTypes.LAUNCH_SERVICE_URL
        handler.IncomingHandler().handleMessage(msg)
    }

    private fun assertAnswered(status: Int) {
        verify(exactly = 1) { outgoing.sendMessage(SessionHandlerMessageTypes.LAUNCH_SERVICE_URL_REPLY, any(), replyTo) }
        verify(exactly = 1) { anyConstructed<Bundle>().putInt(SessionHandlerBundleKeys.HTTP_RESPONSE_CODE, status) }
        verify(exactly = 0) { anyConstructed<Bundle>().putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, any()) }
        assertEquals(0, af.requests.size)
    }

    @Test
    fun aUriThatIsNotA3gppServiceUrlGetsAnErrorResponse() {
        launch("https://example.org/ms/svc-1?af-host-address=${af.baseUrl.substringAfter("//").substringBefore("/")}")
        assertAnswered(400)
    }

    @Test
    fun aServiceTypeThatIsNotSupportedGetsAnErrorResponse() {
        launch("https://launch.3gppservices.org/generic/svc-1")
        assertAnswered(400)
    }

    @Test
    fun parametersThatAreNotConsistentGetAnErrorResponse() {
        launch("http://launch.3gppservices.org/ms/svc-1?estimated-volume=5000000")
        assertAnswered(400)
    }

    @Test
    fun aMissingUrlGetsAnErrorResponse() {
        launch(null)
        assertAnswered(400)
    }

    // the Media Session Handler serves registered clients, as it does for createMediaDeliverySession()
    @Test
    fun aClientThatHasNotRegisteredGetsAnErrorResponseAndNoM5Traffic() {
        launch("http://launch.3gppservices.org/ms/svc-1?af-host-address=${af.baseUrl.substringAfter("//").substringBefore("/")}")
        assertAnswered(400)
        assertEquals(0, sessions.size)
    }
}
