package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.ManifestMetricsConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestMetricsConfigurationUnitTest {
    private val mpd = """<?xml version="1.0"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT30S" minBufferTime="PT2S">
  <Metrics metrics="BufferLevel(1500) AvgThroughput(2000) InitialPlayoutDelay">
    <Reporting schemeIdUri="urn:3GPP:ns:PSS:DASH:QM10">
      <qm:ThreeGPQualityReporting xmlns:qm="urn:3GPP:ns:PSS:AdaptiveHTTPStreaming:2009:qm"
          reportingServer="http://metrics.example/qoe" samplePercentage="50" reportingInterval="10" format="gzip" apn="internet"/>
    </Reporting>
  </Metrics>
  <Metrics metrics="RepSwitchList">
    <Reporting schemeIdUri="urn:other:scheme"/>
  </Metrics>
  <Period/>
</MPD>"""

    // TS 26.247 V19.1.0 clauses 10.4 and 10.5: the Metrics element, the QM10 Reporting element and its scheme information
    @Test
    fun readsTheQm10ConfigurationOfTheManifest() {
        val configurations = ManifestMetricsConfiguration.parse(mpd)
        assertEquals(1, configurations.size)
        val c = configurations[0]
        assertEquals(listOf("BufferLevel", "AvgThroughput", "InitialPlayoutDelay"), c.metricsKeys)
        assertEquals(2L, c.samplingPeriod)
        assertEquals("http://metrics.example/qoe", c.reportingServer)
        assertEquals(50f, c.samplePercentage!!, 0.001f)
        assertEquals(10L, c.reportingInterval)
        assertTrue(c.gzip)
        assertEquals("internet", c.apn)
    }

    @Test
    fun theKeysAreGivenToTheReportersAsTermIdentifiers() {
        val converted = ManifestMetricsConfiguration.parse(mpd)[0].toClientConfiguration(0)
        assertEquals("manifest-0", converted.metricsReportingConfigurationId)
        assertEquals("urn:3GPP:ns:PSS:DASH:QM10#BufferLevel", converted.metrics[0])
        assertEquals("urn:3GPP:ns:PSS:DASH:QM10", converted.scheme)
        assertNull(converted.urlFilters)
    }

    @Test
    fun aManifestWithoutAConfigurationOrNotXmlHasNone() {
        assertTrue(ManifestMetricsConfiguration.parse("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>").isEmpty())
        assertTrue(ManifestMetricsConfiguration.parse("not xml").isEmpty())
    }

    private fun mpdWith(type: String, extra: String, ranges: String) = """<?xml version="1.0"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" $extra minBufferTime="PT2S">
  <Metrics metrics="BufferLevel(1000)">
    $ranges
    <Reporting schemeIdUri="urn:3GPP:ns:PSS:DASH:QM10">
      <qm:ThreeGPQualityReporting xmlns:qm="urn:3GPP:ns:PSS:AdaptiveHTTPStreaming:2009:qm" reportingServer="http://m.example/q" reportingInterval="5"/>
    </Reporting>
  </Metrics>
  <Period/>
</MPD>"""

    // ISO/IEC 23009-1:2026 table 42: Range@starttime and Range@duration of a static presentation are in Media Presentation time
    @Test
    fun aRangeOfAStaticPresentationIsTheWindowOfTheReporting() {
        val c = ManifestMetricsConfiguration.parse(mpdWith("static", "mediaPresentationDuration=\"PT60S\"", "<Range starttime=\"PT10S\" duration=\"PT20S\"/>"))[0]
        val converted = c.toClientConfiguration(0)
        assertEquals(10L, converted.reportingStartOffset)
        assertEquals(20L, converted.reportingDuration)
    }

    @Test
    fun withoutARangeTheWholeContentIsCollected() {
        val converted = ManifestMetricsConfiguration.parse(mpdWith("static", "", ""))[0].toClientConfiguration(0)
        assertNull(converted.reportingStartOffset)
        assertNull(converted.reportingDuration)
    }

    // for a dynamic presentation the start is a wall clock time: MPD@availabilityStartTime plus @starttime
    @Test
    fun theStartOfADynamicPresentationIsWallClockTime() {
        val ast = "2026-10-25T12:00:00Z"
        val c = ManifestMetricsConfiguration.parse(mpdWith("dynamic", "availabilityStartTime=\"$ast\"", "<Range starttime=\"PT15M\" duration=\"PT2M\"/>"))[0]
        val now = java.time.Instant.parse("2026-10-25T12:14:00Z").toEpochMilli()
        val converted = c.toClientConfiguration(0, now)
        assertEquals(60L, converted.reportingStartOffset)
        assertEquals(120L, converted.reportingDuration)
        // after the start the window has begun
        assertEquals(0L, c.toClientConfiguration(0, java.time.Instant.parse("2026-10-25T12:20:00Z").toEpochMilli()).reportingStartOffset)
    }

    @Test
    fun severalRangesGiveOneWindowThatCoversThem() {
        val c = ManifestMetricsConfiguration.parse(mpdWith("static", "", "<Range starttime=\"PT10S\" duration=\"PT5S\"/><Range starttime=\"PT30S\" duration=\"PT10S\"/>"))[0]
        assertEquals(10L, c.toClientConfiguration(0).reportingStartOffset)
        assertEquals(30L, c.toClientConfiguration(0).reportingDuration)
    }
}
