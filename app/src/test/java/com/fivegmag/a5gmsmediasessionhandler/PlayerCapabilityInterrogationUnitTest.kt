package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.PlayerCapabilityInterrogation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class PlayerCapabilityInterrogationUnitTest {
    // TS 26.512 V19.3.0 clause 4.7.3: the capabilities that the Media Player reports are the capabilities
    @Test
    fun theReplyOfThePlayerIsTheResult() {
        val interrogation = PlayerCapabilityInterrogation(2000)
        val asked = ArrayList<String>()
        val capabilities = interrogation.interrogate("c/s1") {
            asked.add("c/s1")
            thread { interrogation.onReply("c/s1", listOf("CAPABILITY_L4S", "CAPABILITY_QOS")) }
        }
        assertEquals(setOf("CAPABILITY_L4S", "CAPABILITY_QOS"), capabilities)
        assertEquals(listOf("c/s1"), asked)
    }

    // A Media Player that does not answer reports nothing, so it has no capability
    @Test
    fun aPlayerThatDoesNotAnswerHasNoCapability() {
        assertEquals(emptySet<String>(), PlayerCapabilityInterrogation(50).interrogate("c/s1") {})
    }

    @Test
    fun aReplyWithoutCapabilitiesIsAnEmptyList() {
        val interrogation = PlayerCapabilityInterrogation(2000)
        assertEquals(emptySet<String>(), interrogation.interrogate("c/s1") { interrogation.onReply("c/s1", null) })
    }

    // A reply for another media delivery session or a late reply is ignored
    @Test
    fun aReplyOfAnotherSessionIsIgnored() {
        val interrogation = PlayerCapabilityInterrogation(100)
        val capabilities = interrogation.interrogate("c/s1") { interrogation.onReply("c/s2", listOf("CAPABILITY_L4S")) }
        assertEquals(emptySet<String>(), capabilities)
        interrogation.onReply("c/s1", listOf("CAPABILITY_L4S"))
    }
}
