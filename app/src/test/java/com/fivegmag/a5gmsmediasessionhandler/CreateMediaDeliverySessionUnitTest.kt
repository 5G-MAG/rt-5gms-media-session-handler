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
 * TS 26.510 V19.2.0 clause 5.4.2.1: "An external service identifier shall be provided as input parameter to the API method." The text defines
 * no error code for a missing one; the reply of createMediaDeliverySession says that the creation did not succeed and carries no media
 * delivery session identifier.
 */
class CreateMediaDeliverySessionUnitTest {
    private val af = MediaAfDouble()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing = mockk<OutgoingMessageHandler>(relaxed = true)
    private val handler = IncomingMessageHandler(sessions, outgoing)
    private val replyTo = Messenger(null as Handler?)

    @Before
    fun mockReplies() {
        // the Bundle of the platform keeps nothing on a JVM: what is put into it is recorded
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putBoolean(any(), any()) } returns Unit
        every { anyConstructed<Bundle>().putString(any(), any()) } returns Unit
    }

    @After
    fun stop() {
        unmockkConstructor(Bundle::class)
        af.close()
    }

    private fun create(serviceId: String?) {
        val bundle = mockk<Bundle>(relaxed = true)
        every { bundle.getString(SessionHandlerBundleKeys.SERVICE_ID) } returns serviceId
        val msg = mockk<Message>(relaxed = true)
        every { msg.data } returns bundle
        msg.replyTo = replyTo
        msg.what = SessionHandlerMessageTypes.CREATE_MEDIA_DELIVERY_SESSION
        handler.IncomingHandler().handleMessage(msg)
    }

    private fun assertRefused() {
        verify(exactly = 1) { outgoing.sendMessage(SessionHandlerMessageTypes.CREATE_MEDIA_DELIVERY_SESSION_REPLY, any(), replyTo) }
        verify(exactly = 1) { anyConstructed<Bundle>().putBoolean("succeeded", false) }
        verify(exactly = 0) { anyConstructed<Bundle>().putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, any()) }
        assertEquals(0, af.requests.size)
        assertEquals(0, sessions.size)
    }

    @Test
    fun anEmptyServiceIdentifierIsRefused() {
        create("")
        assertRefused()
    }

    @Test
    fun aMissingServiceIdentifierIsRefused() {
        create(null)
        assertRefused()
    }

    @Test
    fun aServiceIdentifierOfBlanksIsRefused() {
        create("  ")
        assertRefused()
    }
}
