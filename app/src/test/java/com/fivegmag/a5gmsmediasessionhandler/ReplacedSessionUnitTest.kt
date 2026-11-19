package com.fivegmag.a5gmsmediasessionhandler

import android.os.Bundle
import android.os.Handler
import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.GeneralSessionStatus
import com.fivegmag.a5gmsmediasessionhandler.service.IncomingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A second createMediaDeliverySession() on an instance that has a media delivery session. Implementation choice, text silent, see CR-023 (open
 * item O-26): TS 26.510 V19.2.0 clause 11.2.2.1 does not say what becomes of the first session. It ends as by destroyMediaDeliverySession()
 * (clause 11.2.2.2): its status is released and SESSION_HANDLING_TERMINATED (table 11.2.3-2) is sent.
 */
class ReplacedSessionUnitTest {
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing = mockk<OutgoingMessageHandler>(relaxed = true)
    private val handler = IncomingMessageHandler(sessions, outgoing)
    private val replyTo = Messenger(null as Handler?)

    @Before
    fun controllers() = handler.initialize(java.util.Properties().apply { setProperty("defaultServiceAccessInformationTimerInterval", "3600") })

    @After
    fun stop() {
        handler.reset()
        sessions.clear()
    }

    @Test
    fun theFirstSessionOfAnInstanceEndsWithSessionHandlingTerminatedWhenASecondIsCreated() {
        sessions[1] = ClientSessionData(replyTo).also { it.generalStatus = GeneralSessionStatus.after("service", "http://as.example/manifest.mpd", true) }
        mockkBundle {
            handler.endReplacedSession(1, replyTo)
            verify(exactly = 1) { outgoing.sendNotification(SessionHandlerEvents.Notification.SESSION_HANDLING_TERMINATED, any(), replyTo) }
        }
        assertNull(sessions[1]!!.generalStatus)
    }

    @Test
    fun anInstanceWithoutASessionHasNothingToEnd() {
        sessions[1] = ClientSessionData(replyTo)
        mockkBundle {
            handler.endReplacedSession(1, replyTo)
            verify(exactly = 0) { outgoing.sendNotification(any(), any(), any()) }
        }
    }

    // the Bundle of the platform keeps nothing on a JVM: the notification is built from a recording one
    private fun mockkBundle(body: () -> Unit) {
        io.mockk.mockkConstructor(Bundle::class)
        io.mockk.every { anyConstructed<Bundle>().putString(any(), any()) } returns Unit
        try { body() } finally { io.mockk.unmockkConstructor(Bundle::class) }
    }
}
