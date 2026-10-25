package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.service.NotificationMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationMessageParserUnitTest {
    // TS 26.510 V19.2.0 table 10.2.3-1: type and reason are mandatory
    @Test
    fun readsAServiceAccessInformationNotification() {
        val payload = """{"type":"NOTIFICATION_SERVICE_ACCESS_INFORMATION","reason":"NOTIFICATION_REASON_PROVISIONING_UPDATE","entityTag":"\"abc\"",
            "serviceAccessInformation":{"provisioningSessionId":"ps1","streamingAccess":{"entryPoints":[{"locator":"http://as.example/m.mpd"}]}}}"""
        val message = NotificationMessageParser.parse(payload)
        assertNotNull(message)
        assertEquals("NOTIFICATION_SERVICE_ACCESS_INFORMATION", message!!.type)
        assertEquals("ps1", message.serviceAccessInformation!!.provisioningSessionId)
        assertEquals("http://as.example/m.mpd", message.serviceAccessInformation!!.streamingAccess!!.entryPoints!![0].locator)
    }

    @Test
    fun refusesWhatIsNotANotification() {
        assertNull(NotificationMessageParser.parse("{not json"))
        assertNull(NotificationMessageParser.parse("""{"reason":"NOTIFICATION_REASON_PROVISIONING_UPDATE"}"""))
        assertNull(NotificationMessageParser.parse("""{"type":"NOTIFICATION_SERVICE_ACCESS_INFORMATION"}"""))
    }

    @Test
    fun brokerUrlIsGivenToTheClientWithItsScheme() {
        assertEquals("tcp://broker.example:1883", NotificationMessageParser.brokerUri("mqtt://broker.example:1883"))
        assertEquals("ssl://broker.example", NotificationMessageParser.brokerUri("mqtts://broker.example"))
    }
}
