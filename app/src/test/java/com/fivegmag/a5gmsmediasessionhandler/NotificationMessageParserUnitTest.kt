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

    // Table 10.2.3-1: a Dynamic Policy Instance notification as the Media AF publishes it
    @Test
    fun readsADynamicPolicyNotification() {
        val payload = """{"type":"NOTIFICATION_DYNAMIC_POLICY_INSTANCE","reason":"NOTIFICATION_REASON_QOS_MONITORING_RESULTS","entityTag":"\"x\"",
            "dynamicPolicy":{"dynamicPolicyId":"d1","sessionId":"s1","policyTemplateId":"pt1","applicationFlowBindings":[],"qosEnforcement":true,
            "l4SEnabled":false,"qoSMonitoringEnabled":true,"qoSMonitoringResults":{"dlDelays":[5]}}}"""
        val message = NotificationMessageParser.parse(payload)!!
        assertEquals(NotificationMessageParser.NOTIFICATION_DYNAMIC_POLICY_INSTANCE, message.type)
        assertEquals(NotificationMessageParser.REASON_QOS_MONITORING_RESULTS, message.reason)
        assertEquals("d1", message.dynamicPolicy!!.dynamicPolicyId)
        assertEquals(5, message.dynamicPolicy!!.qoSMonitoringResults!!.dlDelays!![0])
        assertNull(message.networkAssistanceSession)
        assertNull(message.serviceAccessInformation)
    }

    @Test
    fun readsANetworkAssistanceSessionNotification() {
        val payload = """{"type":"NOTIFICATION_NETWORK_ASSISTANCE_SESSION","reason":"NOTIFICATION_REASON_PROVISIONING_UPDATE",
            "networkAssistanceSession":{"naSessionId":"n1","sessionId":"s1","applicationFlowDescription":{"filterMethod":"5_TUPLE"}}}"""
        val message = NotificationMessageParser.parse(payload)!!
        assertEquals(NotificationMessageParser.NOTIFICATION_NETWORK_ASSISTANCE_SESSION, message.type)
        assertEquals("n1", message.networkAssistanceSession!!.naSessionId)
        assertNull(message.dynamicPolicy)
    }

    @Test
    fun theBackgroundDataTransferCancellationReasonHasTheNameOfTheSpecification() {
        assertEquals("NOTIFICATION_REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION", NotificationMessageParser.REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION)
    }
}
