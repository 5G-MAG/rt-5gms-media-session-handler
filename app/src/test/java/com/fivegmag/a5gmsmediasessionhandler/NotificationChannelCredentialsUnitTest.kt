package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.NotificationChannelController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class NotificationChannelCredentialsUnitTest {
    private fun controller(vararg properties: Pair<String, String>): NotificationChannelController {
        val controller = NotificationChannelController({ _, _ -> })
        controller.initialize(Properties().apply { properties.forEach { (k, v) -> setProperty(k, v) } })
        return controller
    }

    // TS 26.510 V19.2.0 clause 10.2.1: client authentication at the broker, such as a token (JWT)
    @Test
    fun theConfiguredUserNameAndPasswordAreSentToTheBroker() {
        val options = controller("mqttUsername" to "msh", "mqttPassword" to "eyJhbGciOi.payload.sig").connectionOptions()
        assertEquals("msh", options.userName)
        assertEquals("eyJhbGciOi.payload.sig", String(options.password))
    }

    @Test
    fun withoutConfiguredCredentialsTheClientConnectsAnonymously() {
        val options = controller("mqttUsername" to "", "mqttPassword" to "").connectionOptions()
        assertNull(options.userName)
        assertNull(options.password)
    }

    @Test
    fun reconnectionStaysOn() {
        assertTrue(controller().connectionOptions().isAutomaticReconnect)
    }

    // Clause 10.2.2: the sub-topics of the resources of the client are subscribed again when another channel replaces the one in use
    @Test
    fun theSubTopicsAreKeptWhenAnotherChannelReplacesTheOneInUse() {
        val controller = controller()
        controller.connect(1, "mqtt://127.0.0.1:1", "svc")
        controller.subscribe(1, "d1")
        controller.connect(1, "mqtt://127.0.0.1:1", "svc", keepSubTopics = true)
        assertEquals(setOf("svc/d1"), controller.subTopicsOf(1))
        controller.connect(1, "mqtt://127.0.0.1:1", "svc")
        assertTrue(controller.subTopicsOf(1).isEmpty())
        controller.reset()
    }
}
