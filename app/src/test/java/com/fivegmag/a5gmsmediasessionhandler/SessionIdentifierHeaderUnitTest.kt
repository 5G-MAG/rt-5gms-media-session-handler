package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.network.SessionIdentifierHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionIdentifierHeaderUnitTest {
    @Test
    fun theHeaderIsCmcdSession() = assertEquals("CMCD-Session", SessionIdentifierHeader.NAME)

    @Test
    fun theValueIsTheSidKeyAsAQuotedString() =
        assertEquals("sid=\"270da3f1-4d4d-4e8e-9c72-d809cd73e97a\"", SessionIdentifierHeader.value("270da3f1-4d4d-4e8e-9c72-d809cd73e97a"))
}
