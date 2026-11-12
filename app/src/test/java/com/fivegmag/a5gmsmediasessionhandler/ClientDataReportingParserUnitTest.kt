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

    // clause K.6: a description with the Scope of the activation by an Event is read and marked; any other Scope is never applied
    @Test
    fun aDescriptionThatAnEventActivatesIsMarked() {
        val scoped = mpd(
            cdr("""<CMCDParameters mode="header" includeInRequests="segment" keys="br bl"/>""", """serviceLocations="beta" adaptationSets="1""""),
            eventScope,
            """<BaseURL serviceLocation="alpha">https://cdn1.example.com/</BaseURL><BaseURL serviceLocation="beta">https://cdn2.example.com/</BaseURL>"""
        )
        val e = entry(scoped)
        assertTrue(e.activatedByEvent)
        assertEquals(1L, e.serviceDescriptionId)
        assertEquals(listOf("https://cdn2.example.com/"), e.serviceLocationUrls)
        // not selectable by the settings API: only the descriptions without a Scope are
        assertEquals(emptyList<Long>(), ClientDataReportingParser.parseAll(scoped, url).context!!.serviceDescriptionIds)
        assertTrue(ClientDataReportingParser.parse(mpd(cdr("""<CMCDParameters keys="br"/>"""), """<Scope schemeIdUri="urn:other"/>"""), url).isEmpty())
        assertTrue(!entry(mpd(cdr("""<CMCDParameters keys="br"/>"""))).activatedByEvent)
    }

    // clause K.6.2: the Event carries the identifier of a description or the description itself
    @Test
    fun aDescriptionThatAnEventCarriesIsReadAndMarked() {
        val inline = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
            <ServiceDescription id="10">$eventScope<ClientDataReporting schemeIdUri="$scheme"><CMCDParameters keys="br"/></ClientDataReporting></ServiceDescription>
            <Period id="1"><EventStream schemeIdUri="urn:mpeg:dash:event:service-description:2024" timescale="1000">
              <Event presentationTime="0" duration="60000" id="0">10</Event>
              <Event presentationTime="60000" duration="60000" id="1"><ServiceDescription id="20"><ClientDataReporting schemeIdUri="$scheme"><CMCDParameters keys="bl"/></ClientDataReporting></ServiceDescription></Event>
            </EventStream>
            <EventStream schemeIdUri="urn:other"><Event id="5"><ServiceDescription id="30"><ClientDataReporting schemeIdUri="$scheme"><CMCDParameters keys="dl"/></ClientDataReporting></ServiceDescription></Event></EventStream>
            <AdaptationSet id="1"/></Period></MPD>"""
        val entries = ClientDataReportingParser.parse(inline, url)
        assertEquals(listOf(10L, 20L), entries.map { it.serviceDescriptionId })
        assertTrue(entries.all { it.activatedByEvent })
        assertEquals(listOf("bl"), entries[1].keys)
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

    // table K.7: the filter by Adaptation Set is carried with the ids of the manifest; the player decides (it cannot tell the Adaptation Set of a request)
    @Test
    fun theAdaptationSetFilterIsCarriedWithTheAdaptationSetIdsOfTheManifest() {
        val two = """<Period><AdaptationSet id="1"/><AdaptationSet id="2"/></Period>"""
        fun parsed(attributes: String, period: String = two) = ClientDataReportingParser.parseAll(mpd(cdr("""<CMCDParameters keys="br"/>""", attributes), period = period), url)
        assertEquals(listOf(2L, 1L), parsed("""adaptationSets="2 1"""").entries.single().adaptationSets)
        assertEquals(listOf("1", "2"), parsed("""adaptationSets="1"""").context!!.adaptationSetIds)
        assertEquals(listOf("1", ""), parsed("""adaptationSets="1"""", """<Period><AdaptationSet id="1"/><AdaptationSet/></Period>""").context!!.adaptationSetIds)
        // a token that is no unsigned integer names no Adaptation Set; an absent attribute is every Adaptation Set
        assertEquals(emptyList<Long>(), parsed("""adaptationSets="video"""").entries.single().adaptationSets)
        assertNull(parsed("").entries.single().adaptationSets)
    }

    // clause 13.2.4: the player selects and modifies the descriptions of the manifest by their @id; the Period or the MPD level decides which exist
    @Test
    fun theServiceDescriptionIdsAndLabelsOfTheManifestAreCarried() {
        val manifest = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL serviceLocation="a">http://cdn.example/a/</BaseURL>
            <ServiceDescription id="7"><ClientDataReporting schemeIdUri="$scheme"><CMCDParameters keys="br"/></ClientDataReporting></ServiceDescription>
            <ServiceDescription id="8"/>
            <ServiceDescription id="9">$eventScope</ServiceDescription>
            <ServiceDescription id="x"/>
            <Period><AdaptationSet id="1"/></Period></MPD>"""
        val result = ClientDataReportingParser.parseAll(manifest, url)
        assertEquals(7L, result.entries.single().serviceDescriptionId)
        assertEquals(listOf(7L, 8L), result.context!!.serviceDescriptionIds)
        assertEquals(listOf("a"), result.context!!.serviceLocations.map { it.label })
        assertEquals(listOf("http://cdn.example/a/"), result.context!!.serviceLocations.single().urls)
        assertNull(ClientDataReportingParser.parseAll("<not xml", url).context)
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
        assertEquals(emptyList<Long>(), entry(listing.replace(eventScope, "").replace("""scheme="$scheme"""", """schemeIdUri="$scheme"""")).adaptationSets)
        val iso = listing.replace(eventScope, "").replace("""scheme="$scheme"""", """schemeIdUri="$scheme"""").replace("""adaptationSets="video"""", """adaptationSets="1"""").replace("""<AdaptationSet mimeType""", """<AdaptationSet id="1" mimeType""")
        assertNull(entry(iso).keys)
    }

    @Test
    fun anEntryWithoutModeUsesQuery() {
        assertEquals(ClientDataReportingModes.CMCD_QUERY, entry(mpd(cdr("""<CMCDParameters keys="bl"/>"""))).mode)
    }

    // the nested locations of the phone run: the BaseURL of dist2 lies below that of dist1, so the entry that names dist1 hands over dist2 as the other one
    private val phoneBase = """<BaseURL serviceLocation="dist1">http://10.45.0.1:8080/m4d/ps-1/</BaseURL><BaseURL serviceLocation="dist2">http://10.45.0.1:8080/m4d/ps-1/dist2/</BaseURL>"""

    @Test
    fun anEntryHandsOverTheOtherServiceLocationsOfTheManifest() {
        val one = entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="dist1""""), baseUrls = phoneBase))
        assertEquals(listOf("http://10.45.0.1:8080/m4d/ps-1/"), one.serviceLocationUrls)
        assertEquals(listOf("http://10.45.0.1:8080/m4d/ps-1/dist2/"), one.otherServiceLocationUrls)
        val two = entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="dist2""""), baseUrls = phoneBase))
        assertEquals(listOf("http://10.45.0.1:8080/m4d/ps-1/"), two.otherServiceLocationUrls)
        // an unrestricted entry has no other location, nor has one that names them all
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br"/>"""), baseUrls = phoneBase)).otherServiceLocationUrls)
        assertNull(entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="dist1 dist2""""), baseUrls = phoneBase)).otherServiceLocationUrls)
    }

    // the manifests of the phone run put serviceLocations and adaptationSets on CMCDParameters: they are attributes of ClientDataReporting (tables K.16, K.17, schema
    // of the ISO text), so there they restrict nothing and the entry applies to every request
    @Test
    fun theFiltersOfTableK7AreReadOnClientDataReportingOnly() {
        val misplaced = entry(
            mpd(cdr("""<CMCDParameters keys="br" serviceLocations="no-such-label" adaptationSets="0"/>"""), period = """<Period id="0"><AdaptationSet id="0"/><AdaptationSet id="1"/></Period>""")
        )
        assertNull(misplaced.serviceLocationUrls)
        // in the right place the same filters work: no BaseURL carries the label, and the list does not name AdaptationSet 1
        val period = """<Period id="0"><AdaptationSet id="0"/><AdaptationSet id="1"/></Period>"""
        assertEquals(emptyList<String>(), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """serviceLocations="no-such-label""""), period = period)).serviceLocationUrls)
        assertEquals(listOf(0L), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="0""""), period = period)).adaptationSets)
        assertEquals(listOf("br"), entry(mpd(cdr("""<CMCDParameters keys="br"/>""", """adaptationSets="0 1""""), period = period)).keys)
    }
}
