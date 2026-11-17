package com.fivegmag.a5gmsmediasessionhandler

import android.os.Handler
import android.os.Messenger
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.service.IncomingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * TS 26.512 V19.3.0 clause 4.8.3.1: the Media Session Handler is the URL handler for the domain name launch.3gppservices.org and the initial path
 * element /ms, declared by an intent filter in the manifest. A 3GPP Service URL that arrives by intent gets an HTTP status (clause 4.8.3.4, which
 * names no code), and a request that cannot be served causes no M5 traffic and leaves no media delivery session.
 */
class ServiceUrlIntentUnitTest {
    private val af = MediaAfDouble()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val handler = IncomingMessageHandler(sessions, mockk<OutgoingMessageHandler>(relaxed = true))
    private val afAddress = af.baseUrl.substringAfter("//").substringBefore("/")

    @After
    fun stop() = af.close()

    private fun byIntent(url: String, invoker: Messenger?): Int {
        var status = -1
        handler.launchServiceUrlByIntent(url, invoker) { status = it }
        return status
    }

    private fun assertNothingStarted() {
        assertEquals(0, af.requests.size)
        assertEquals(0, sessions.size)
        assertFalse(handler.hasAnonymousSessions())
    }

    @Test
    fun aUriThatIsNotA3gppServiceUrlGetsAnErrorResponse() {
        assertEquals(400, byIntent("https://example.org/ms/svc-1?af-host-address=$afAddress", null))
        assertNothingStarted()
    }

    @Test
    fun aServiceTypeThatIsNotSupportedGetsAnErrorResponse() {
        assertEquals(400, byIntent("https://launch.3gppservices.org/generic/svc-1", null))
        assertNothingStarted()
    }

    // the invoker is named by the Messenger that it registered; one that did not register is not a client
    @Test
    fun aMessengerThatIsNotARegisteredClientGetsAnErrorResponse() {
        val unknown = Messenger(null as Handler?)
        assertEquals(400, byIntent("http://launch.3gppservices.org/ms/svc-1?af-host-address=$afAddress", unknown))
        assertNothingStarted()
    }

    // an anonymous invoker has no Media Stream Handler for the Media Session Handler to invoke (clause 4.8.3.3 items 1 and 2)
    @Test
    fun anAnonymousInvokerThatAsksForMediaGetsNotImplementedAndNothingStarts() {
        for (query in listOf("media-entry-point=http%3A%2F%2Fas.example.net%2Fm.mpd", "content-type=application%2Fdash%2Bxml", "profile=urn%3Ap%3Aa", "service-operation-point=sop")) {
            assertEquals(501, byIntent("http://launch.3gppservices.org/ms/svc-1?af-host-address=$afAddress&$query", null))
            assertNothingStarted()
        }
    }

    // the manifest declares exactly the handler that clause 4.8.3.1 asks for
    @Test
    fun theManifestRegistersTheUrlHandlerForTheDomainNameAndTheInitialPathElement() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val activity = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttribute("android:name") == ".ServiceUrlActivity" }
        assertEquals("true", activity.getAttribute("android:exported"))
        val filter = activity.getElementsByTagName("intent-filter").item(0) as Element
        fun names(tag: String) = filter.getElementsByTagName(tag).let { list -> (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") } }
        assertEquals(listOf("android.intent.action.VIEW"), names("action"))
        assertTrue(names("category").containsAll(listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")))
        val data = filter.getElementsByTagName("data").let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun values(attribute: String) = data.map { it.getAttribute("android:$attribute") }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("http", "https"), values("scheme"))
        assertEquals(setOf("launch.3gppservices.org"), values("host"))
        assertEquals(setOf("/ms"), values("path"))
        assertEquals(setOf("/ms/"), values("pathPrefix"))
    }
}
