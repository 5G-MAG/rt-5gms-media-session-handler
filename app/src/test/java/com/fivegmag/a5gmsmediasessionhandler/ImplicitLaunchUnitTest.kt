package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.DeliveryProtocols
import com.fivegmag.a5gmscommonlibrary.helpers.MultipathModes
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyInvocationConfiguration
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.StreamingAccess
import com.fivegmag.a5gmscommonlibrary.models.TimeWindow
import com.fivegmag.a5gmsmediasessionhandler.controller.ImplicitLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ImplicitLaunchUnitTest {
    private val base = "http://launch.3gppservices.org/ms/svc-1"
    private val now = OffsetDateTime.parse("2026-10-28T10:30:00Z")
    private val mpd = EntryPoint("http://as.example.net/svc-1/manifest.mpd", ContentTypes.DASH, arrayListOf("urn:mpeg:dash:profile:isoff-live:2011"))
    private val mp4 = EntryPoint("http://as.example.net/svc-1/movie.mp4", "video/mp4", null)
    private val upcoming = arrayListOf(BdtWindow(TimeWindow("2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z"), "5 Mbps"))
    private val ended = arrayListOf(BdtWindow(TimeWindow("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z"), "5 Mbps"))

    private fun launch(query: String = ""): ImplicitLaunch = ImplicitLaunch.prepare(base + query).launch!!

    private fun sai(vararg bindings: PolicyTemplateBinding) = ServiceAccessInformation(
        "ps1", null, StreamingAccess(arrayListOf(mpd, mp4)), null, null,
        dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(arrayListOf("http://af.example.net/"), arrayListOf(*bindings), arrayListOf("5_TUPLE"))
    )

    // TS 26.510 V19.2.0 clause 6.3: a URI that does not conform, or a service type that is not supported, gets an error response
    @Test
    fun aUrlThatIsNotAcceptedGetsAnErrorStatusAndNoLaunch() {
        for (url in listOf("https://example.org/ms/svc-1", "ftp://launch.3gppservices.org/ms/svc-1", "http://launch.3gppservices.org/generic/svc-1", "$base?estimated-volume=1")) {
            val prepared = ImplicitLaunch.prepare(url)
            assertNull(url, prepared.launch)
            assertEquals(url, ImplicitLaunch.STATUS_BAD_REQUEST, prepared.status)
        }
    }

    // TS 26.512 V19.3.0 clause 4.8.3.2: the M5 request goes to the af-host-address, or to the default host
    @Test
    fun theMediaAfIsTheOneThatTheUrlNames() {
        assertEquals("http://af.example.net:8080/3gpp-maf-session-handling/v1/", launch("?af-host-address=af.example.net:8080").m5BaseUrl)
        assertEquals("http://ms.af.3gppservices.org/3gpp-maf-session-handling/v1/", launch().m5BaseUrl)
        assertEquals("svc-1", launch().externalServiceId)
    }

    // table 12.4-1: estimated-volume has no unit; a whole number of at least 1 is read as bytes
    @Test
    fun theEstimatedVolumeIsAWholeNumberOfBytes() {
        assertEquals(5000000L, launch("?service-operation-point=BDT&estimated-volume=5000000").estimatedVolume)
        assertNull(launch("?service-operation-point=BDT").estimatedVolume)
        for (volume in listOf("0", "-1", "5MB", "1.5", "99999999999999999999")) {
            val prepared = ImplicitLaunch.prepare("$base?service-operation-point=BDT&estimated-volume=$volume")
            assertNull(volume, prepared.launch)
            assertEquals(volume, ImplicitLaunch.STATUS_BAD_REQUEST, prepared.status)
        }
    }

    // clause 4.8.3.3 item 1 and clause 12.4 (last paragraph): the Media Stream Handler is instantiated if the URL asks for it
    @Test
    fun theMediaStreamHandlerIsInstantiatedOnlyIfTheUrlAsksForIt() {
        assertFalse(launch().launchesMedia)
        assertFalse(launch("?af-host-address=af.example.net&content-id=c&delivery-protocol=HTTP_2&multipath-mode").launchesMedia)
        assertTrue(launch("?media-entry-point=http%3A%2F%2Fas.example.net%2Fm.mpd").launchesMedia)
        assertTrue(launch("?content-type=video%2Fmp4").launchesMedia)
        assertTrue(launch("?profile=urn%3Ap%3Aa").launchesMedia)
        assertTrue(launch("?service-operation-point=HD").launchesMedia)
        assertTrue(launch().select(listOf(mpd, mp4)).isEmpty())
    }

    // clause 4.8.3.3 item 2: one of the Media Entry Points of the Service Access Information, filtered by content-type and profile
    @Test
    fun oneMediaEntryPointIsSelectedThatPassesTheFilters() {
        assertEquals(listOf(mpd), launch("?service-operation-point=HD").select(listOf(mpd, mp4)))
        assertEquals(listOf(mp4), launch("?content-type=video%2Fmp4").select(listOf(mpd, mp4)))
        assertEquals(listOf(mp4), launch("?content-type=video%2Fmp4&content-type=audio%2Fmp4").select(listOf(mpd, mp4)))
        assertEquals(listOf(mpd), launch("?content-type=VIDEO%2FMP4&content-type=application%2Fdash%2Bxml%3Bcodecs%3Dx").select(listOf(mpd, mp4)))
        assertEquals(listOf(mpd), launch("?profile=urn%3Ampeg%3Adash%3Aprofile%3Aisoff-live%3A2011").select(listOf(mpd, mp4)))
        assertTrue(launch("?profile=urn%3Ap%3Aother").select(listOf(mpd, mp4)).isEmpty())
        assertTrue(launch("?content-type=audio%2Fmp4").select(listOf(mpd, mp4)).isEmpty())
    }

    @Test
    fun anEntryPointWithoutAContentTypeDoesNotPassAContentTypeFilter() {
        assertTrue(launch("?content-type=video%2Fmp4").select(listOf(EntryPoint("http://as.example.net/x", null, null))).isEmpty())
    }

    // clause 4.8.3.3 item 1: the Media Entry Point of media-entry-point is the one that is used
    @Test
    fun theMediaEntryPointOfTheUrlIsTheOneThatIsUsed() {
        val entry = EntryPoint("http://other.example.net/m.mpd", ContentTypes.DASH, null)
        val launch = launch("?media-entry-point=http%3A%2F%2Fother.example.net%2Fm.mpd")
        assertEquals("http://other.example.net/m.mpd", launch.mediaEntryPoint)
        assertEquals(listOf(entry), launch.select(listOf(entry)))
    }

    // clause 4.8.3.3 items 2 and 3: nothing is started when no Media Entry Point is left, no Policy Template binding matches the
    // service-operation-point, or (estimated-volume) the binding offers no Background Data Transfer window that has not ended
    @Test
    fun nothingIsStartedWhenTheServiceAccessInformationHasWhatTheUrlAsksFor() {
        val qos = PolicyTemplateBinding("HD", "pt-hd")
        val bdt = PolicyTemplateBinding("BDT", "pt-bdt", bdtWindows = upcoming)
        val past = PolicyTemplateBinding("OLD", "pt-old", bdtWindows = ended)
        val sai = sai(qos, bdt, past)

        val plain = launch("?service-operation-point=HD")
        assertFalse(plain.refuses(sai, listOf(mpd), now))

        val unknown = launch("?service-operation-point=NO_SUCH_POINT")
        assertTrue(unknown.refuses(sai, listOf(mpd), now))
        assertEquals(ImplicitLaunch.STATUS_NOT_FOUND, unknown.failureStatus)

        assertTrue(launch("?service-operation-point=HD&estimated-volume=5000000").refuses(sai, listOf(mpd), now))
        assertTrue(launch("?service-operation-point=OLD&estimated-volume=5000000").refuses(sai, listOf(mpd), now))
        assertFalse(launch("?service-operation-point=BDT&estimated-volume=5000000").refuses(sai, listOf(mpd), now))

        val none = launch("?content-type=audio%2Fmp4")
        assertTrue(none.refuses(sai, emptyList(), now))
        assertEquals(ImplicitLaunch.STATUS_NOT_FOUND, none.failureStatus)

        // a URL that does not ask for the Media Stream Handler needs no Media Entry Point
        assertFalse(launch().refuses(sai, emptyList(), now))
        assertTrue(launch("?service-operation-point=HD").refuses(ServiceAccessInformation("ps1", null, null, null, null), listOf(mpd), now))
    }

    @Test
    fun withoutARefusalTheFailureIsTheMediaAfsOwn() {
        assertEquals(ImplicitLaunch.STATUS_BAD_GATEWAY, launch().failureStatus)
    }

    // clause 4.9.1A, table 12.4-1: delivery-protocol in decreasing order of preference, multipath-mode as a preference
    @Test
    fun theContentDeliveryPreferencesPopulateTheDesiredConfiguration() {
        val launch = launch("?delivery-protocol=HTTP_3&delivery-protocol=HTTP_2&delivery-protocol=HTTP_1_1&multipath-mode=true")
        assertEquals(listOf(DeliveryProtocols.HTTP_3, DeliveryProtocols.HTTP_2, DeliveryProtocols.HTTP_1_1), launch.deliveryProtocols)
        assertEquals(MultipathModes.PREFERRED, launch.multipathMode)
        assertEquals(MultipathModes.PREFERRED, launch("?multipath-mode").multipathMode)
    }

    @Test
    fun withoutPreferencesTheConfigurationIsLeftAlone() {
        assertNull(launch().deliveryProtocols)
        assertNull(launch().multipathMode)
    }

    // table 12.4-1 client-data-reporting-mode: handed on as written; omitted, nothing is handed on
    @Test
    fun theClientDataReportingModeIsTakenFromTheUrl() {
        assertEquals("cmcd-query", launch("?client-data-reporting-mode=cmcd-query").clientDataReportingMode)
        assertEquals("cmcd-headers", launch("?client-data-reporting-mode=cmcd-headers").clientDataReportingMode)
        assertNull(launch().clientDataReportingMode)
    }

    // the text has no value for a protocol that is not listed: it is a suggestion, so it is dropped
    @Test
    fun aDeliveryProtocolThatIsNotListedIsDropped() {
        assertEquals(listOf(DeliveryProtocols.HTTP_2), launch("?delivery-protocol=SPDY&delivery-protocol=HTTP_2").deliveryProtocols)
        assertNull(launch("?delivery-protocol=SPDY").deliveryProtocols)
    }

    // table 12.4-1: access-token is presented at M5, content-id identifies the media consumed
    @Test
    fun theAccessTokenAndTheContentIdentifierAreTakenFromTheUrl() {
        val launch = launch("?access-token=tok%2B1&content-id=movie-7")
        assertEquals("tok+1", launch.accessToken)
        assertEquals("movie-7", launch.contentId)
        assertNotNull(launch.m5BaseUrl)
    }

    // clause 5.4.2.2: an implementation-specific period of inactivity ends the session; media that is consumed is not inactivity
    @Test
    fun aSessionIsNotInactiveWhileMediaIsConsumed() {
        assertTrue(ImplicitLaunch.isConsuming(PlayerStates.READY))
        assertTrue(ImplicitLaunch.isConsuming(PlayerStates.PLAYING))
        assertTrue(ImplicitLaunch.isConsuming(PlayerStates.BUFFERING))
        assertFalse(ImplicitLaunch.isConsuming(PlayerStates.ENDED))
        assertFalse(ImplicitLaunch.isConsuming(PlayerStates.IDLE))
        assertFalse(ImplicitLaunch.isConsuming(PlayerStates.PAUSED))
        assertFalse(ImplicitLaunch.isConsuming(PlayerStates.UNKNOWN))
        assertTrue(ImplicitLaunch.INACTIVITY_PERIOD_MS > 0)
    }
}
