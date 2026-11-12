package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.ClientDataReportingModes
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientDataReportingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ISO/IEC 23009-1:2026 clauses K.3.7, K.4.2.7, K.4.3.7, K.6 and I.3.6 (table I.4) as TS 26.512 V19.3.0 clauses 10.5.1 and G.5.2 refer to them */
class ClientDataReportingParserUnitTest {
    private val url = "http://as.example.net/svc/manifest.mpd"
    private val scheme = "urn:mpeg:dash:cta-5004:2023"
    private val eventScope = """<Scope schemeIdUri="urn:mpeg:dash:event:service-description:2024"/>"""

    private fun mpd(
        reporting: String,
        scope: String = "",
        baseUrls: String = "",
        period: String = """<Period id="1"><AdaptationSet id="1"/></Period>"""
    ) = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">$baseUrls
        <ServiceDescription id="1">$scope$reporting</ServiceDescription>$period</MPD>"""

    private fun cdr(parameters: String, attributes: String = "") = """<ClientDataReporting schemeIdUri="$scheme" $attributes>$parameters</ClientDataReporting>"""

    private fun entry(mpd: String) = ClientDataReportingParser.parse(mpd, url).single()

    private fun none(mpd: String) = assertTrue(ClientDataReportingParser.parse(mpd, url).isEmpty())

    // the example of clause K.6.4: its descriptions are scoped to the activation by an Event, which is not applied
    @Test
    fun aDescriptionThatAnEventActivatesIsNotApplied() {
        val example = mpd(
            cdr("""<CMCDParameters mode="header" includeInRequests="segment" keys="br bl cid dl mtp nor ot sf v"/>""", """serviceLocations="beta" adaptationSets="1""""),
            eventScope,
            """<BaseURL serviceLocation="alpha">https://cdn1.example.com/</BaseURL><BaseURL serviceLocation="beta">https://cdn2.example.com/</BaseURL>"""
        )
        assertTrue(ClientDataReportingParser.parse(example, url).isEmpty())
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>"""), """<Scope schemeIdUri="urn:other"/>"""), url).isEmpty())
    }

    // the same description without a Scope applies to all clients (table K.10)
    @Test
    fun aDescriptionWithoutScopeIsApplied() {
        val e = entry(
            mpd(
                cdr("""<CMCDParameters mode="header" includeInRequests="segment" keys="br bl cid dl mtp nor ot sf v"/>""", """serviceLocations="beta" adaptationSets="1""""),
                baseUrls = """<BaseURL serviceLocation="alpha">https://cdn1.example.com/</BaseURL><BaseURL serviceLocation="beta">https://cdn2.example.com/</BaseURL>"""
            )
        )
        assertEquals(listOf("https://cdn2.example.com/"), e.serviceLocationUrls)
        assertEquals(listOf("a", "v", "av", "c", "tt"), e.objectTypes)
        assertEquals(ClientDataReportingModes.CMCD_HEADERS, e.mode)
        assertEquals(listOf("br", "bl", "cid", "dl", "mtp", "nor", "ot", "sf", "v"), e.keys)
    }

    // table K.17 and the schema: defaults
    @Test
    fun theDefaultsAreQueryModeAndSegmentRequests() {
        val e = entry(mpd(cdr("""<CMCDParameters keys="bl"/>""")))
        assertEquals(ClientDataReportingModes.CMCD_QUERY, e.mode)
        assertEquals(listOf("a", "v", "av", "c", "tt"), e.objectTypes)
        assertNull(e.serviceLocationUrls)
        assertNull(e.sessionId)
        assertNull(e.contentId)
    }

    @Test
    fun theKeysAreMandatoryAndSeparatedByWhiteSpace() {
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters mode="header"/>""")), url).isEmpty())
        assertEquals(listOf("br", "bl"), entry(mpd(cdr("""<CMCDParameters keys="  br   bl "/>"""))).keys)
        // a comma is not a separator: the token is not a key
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br,bl"/>"""))).keys)
    }

    // table K.8: keys that CTA-5004 does not define are ignored and not reported
    @Test
    fun keysThatAreNotDefinedAreIgnored() {
        assertEquals(listOf("br", "sid"), entry(mpd(cdr("""<CMCDParameters keys="br xx sid -custom"/>"""))).keys)
    }

    @Test
    fun anInvalidModeLeavesTheElementOut() {
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters mode="json" keys="br"/>""")), url).isEmpty())
    }

    // table I.4: request types; unknown URNs are dropped, * is every request
    @Test
    fun requestTypesAreGivenAsTheObjectTypesOfTheRequests() {
        assertEquals(listOf("i", "m"), entry(mpd(cdr("""<CMCDParameters includeInRequests="init mpd" keys="br"/>"""))).objectTypes)
        assertEquals(listOf("a", "v", "av", "c", "tt", "i"), entry(mpd(cdr("""<CMCDParameters includeInRequests="segment urn:example:x init" keys="br"/>"""))).objectTypes)
        assertNull(entry(mpd(cdr("""<CMCDParameters includeInRequests="segment *" keys="br"/>"""))).objectTypes)
        // known types that the player does not mark with an object type, and unknown ones, cover nothing
        assertEquals(emptyList<String>(), entry(mpd(cdr("""<CMCDParameters includeInRequests="steering urn:example:x" keys="br"/>"""))).objectTypes)
    }

    // table K.8: contentID and sessionID, at most 64 characters
    @Test
    fun identifiersAreValidWithAtMost64Characters() {
        val e = entry(mpd(cdr("""<CMCDParameters keys="cid sid" contentID="c-1" sessionID="s-1"/>""")))
        assertEquals("c-1", e.contentId)
        assertEquals("s-1", e.sessionId)
        val long = "x".repeat(65)
        val tooLong = entry(mpd(cdr("""<CMCDParameters keys="cid sid" contentID="$long" sessionID="$long"/>""")))
        assertNull(tooLong.contentId)
        assertNull(tooLong.sessionId)
        assertEquals("x".repeat(64), entry(mpd(cdr("""<CMCDParameters keys="cid" contentID="${"x".repeat(64)}"/>"""))).contentId)
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="cid" contentID="a b"/>"""))).contentId)
    }

    // table K.7: serviceLocations is a space-delimited list of labels (clause 5.6.6), BaseURL at any level
    @Test
    fun serviceLocationsAreResolvedAtAnyLevelOfTheManifest() {
        val period = """<Period id="1"><BaseURL serviceLocation="p">media/</BaseURL>
            <AdaptationSet id="1"><BaseURL serviceLocation="q">http://other.example/video/seg.mp4</BaseURL><Representation id="r"/></AdaptationSet></Period>"""
        val e = entry(
            mpd(
                cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="a  p q missing""""), period = period,
                baseUrls = """<BaseURL serviceLocation="a">https://cdn.example/base/</BaseURL><BaseURL serviceLocation="b">cdn2/</BaseURL>"""
            )
        )
        // a relative BaseURL is resolved against each alternative of the level above, an absolute one stands alone (clause 5.6.5)
        assertEquals(
            listOf("https://cdn.example/base/", "https://cdn.example/base/media/", "http://as.example.net/svc/cdn2/media/", "http://other.example/video/"),
            e.serviceLocationUrls
        )
    }

    // table K.7 and clause 10.5.1: the list restricts the reporting to those Service Locations; only an absent attribute means all of them
    @Test
    fun aLabelThatNoBaseUrlCarriesMatchesNoRequestWithoutAnyBaseUrl() {
        assertEquals(emptyList<String>(), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="no-such-label""""))).serviceLocationUrls)
    }

    @Test
    fun aLabelThatNoBaseUrlCarriesMatchesNoRequestWithOtherLabelledBaseUrls() {
        val base = """<BaseURL serviceLocation="a">https://cdn.example/a/</BaseURL><BaseURL serviceLocation="b">https://cdn.example/b/</BaseURL><BaseURL>https://cdn.example/c/</BaseURL>"""
        assertEquals(emptyList<String>(), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="no-such-label"""") , baseUrls = base)).serviceLocationUrls)
        // an empty attribute names no location
        assertEquals(emptyList<String>(), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="""""), baseUrls = base)).serviceLocationUrls)
    }

    @Test
    fun aLabelThatABaseUrlCarriesGivesItsUrlAndNoOther() {
        val base = """<BaseURL serviceLocation="a">https://cdn.example/a/</BaseURL><BaseURL serviceLocation="b">https://cdn.example/b/</BaseURL>"""
        assertEquals(listOf("https://cdn.example/b/"), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="b""""), baseUrls = base)).serviceLocationUrls)
    }

    @Test
    fun anAbsentAttributeMeansAllServiceLocations() {
        val base = """<BaseURL serviceLocation="a">https://cdn.example/a/</BaseURL>"""
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br"/>"""), baseUrls = base)).serviceLocationUrls)
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br"/>"""))).serviceLocationUrls)
    }

    // table K.7: the filter by Adaptation Set; the player cannot tell the Adaptation Set of a request, so only a filter that names all of them is applied
    @Test
    fun anAdaptationSetFilterThatNamesAllAdaptationSetsRestrictsNothing() {
        val two = """<Period><AdaptationSet id="1"/><AdaptationSet id="2"/></Period>"""
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="2 1""""), period = two)).serviceLocationUrls)
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="1""""), period = two), url).isEmpty())
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="1""""), period = """<Period><AdaptationSet id="1"/><AdaptationSet/></Period>"""), url).isEmpty())
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="video""""), period = two), url).isEmpty())
    }

    // table K.16: ClientDataReporting is a descriptor; CMCDParameters 0..N, each an entry
    @Test
    fun theSchemeIsTheCta5004Descriptor() {
        val other = mpd("""<ClientDataReporting schemeIdUri="urn:other"><CMCDParameters keys="br"/></ClientDataReporting>""")
        assertTrue(ClientDataReportingParser.parse(other, url).isEmpty())
        val two = mpd(cdr("""<CMCDParameters keys="br"/><CMCDParameters mode="header" keys="bl"/>"""))
        assertEquals(listOf("br", "bl"), ClientDataReportingParser.parse(two, url).map { it.keys!!.single() })
    }

    // table 4: the ServiceDescription of the Period overrides those of the MPD
    @Test
    fun theDescriptionOfThePeriodOverridesThatOfTheMpd() {
        val period = """<Period id="1"><AdaptationSet id="1"/><ServiceDescription id="2">${cdr("""<CMCDParameters keys="bl"/>""")}</ServiceDescription></Period>"""
        assertEquals(listOf("bl"), entry(mpd(cdr("""<CMCDParameters keys="br"/>"""), period = period)).keys)
    }

    @Test
    fun withSeveralPeriodsADescriptionOfAPeriodMakesNoneApplicable() {
        val periods = """<Period id="1"><AdaptationSet id="1"/><ServiceDescription id="2">${cdr("""<CMCDParameters keys="bl"/>""")}</ServiceDescription></Period><Period id="2"/>"""
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>"""), period = periods), url).isEmpty())
        // without descriptions in the Periods the one of the MPD holds
        assertEquals(listOf("br"), entry(mpd(cdr("""<CMCDParameters keys="br"/>"""), period = "<Period id=\"1\"/><Period id=\"2\"/>")).keys)
    }

    @Test
    fun aManifestWithoutServiceDescriptionOrNotXmlHasNoEntry() {
        assertTrue(ClientDataReportingParser.parse("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>""", url).isEmpty())
        assertTrue(ClientDataReportingParser.parse("not xml", url).isEmpty())
    }

    // listing G.5.2.4-1 of TS 26.512 differs from ISO/IEC 23009-1 (register item SD-032); ISO is followed, so the listing is not applied
    @Test
    fun theListingOfTs26512AsPublishedIsNotApplied() {
        val listing = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
  <BaseURL serviceLocation="dist1">http://distribution-1.example/</BaseURL>
  <ServiceDescription id="1250">$eventScope
    <ClientDataReporting scheme="$scheme" serviceLocations="dist1" adaptationSets="video">
      <CMCDParameters mode="header" contentID="md:cid:EIDR:1" includeInRequests="segment" keys="br,bl,cid,dl,mtp,nor,ot,sf,v"/>
    </ClientDataReporting>
  </ServiceDescription>
  <Period id="1"><AdaptationSet mimeType="video/mp4"/></Period></MPD>"""
        none(listing)
        // without the Scope the Scope is not what stops it: scheme is not the descriptor, and neither are the label and the comma-separated keys
        none(listing.replace(eventScope, ""))
        // with the ISO descriptor and ids the remaining listing syntax is read as ISO reads it: comma-separated keys are no keys, a label is not an id
        none(listing.replace(eventScope, "").replace("""scheme="$scheme"""", """schemeIdUri="$scheme""""))
        val iso = listing.replace(eventScope, "").replace("""scheme="$scheme"""", """schemeIdUri="$scheme"""").replace("""adaptationSets="video"""", """adaptationSets="1"""").replace("""<AdaptationSet mimeType""", """<AdaptationSet id="1" mimeType""")
        assertNull(entry(iso).keys)
    }

    @Test
    fun anEntryWithoutModeUsesQuery() {
        assertEquals(ClientDataReportingModes.CMCD_QUERY, entry(mpd(cdr("""<CMCDParameters keys="bl"/>"""))).mode)
    }
}
