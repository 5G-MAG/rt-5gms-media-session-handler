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
}
