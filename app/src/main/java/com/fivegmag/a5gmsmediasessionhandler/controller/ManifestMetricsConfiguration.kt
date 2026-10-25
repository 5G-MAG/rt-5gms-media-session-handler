package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Client metrics configuration signalled in the manifest (TS 26.512 V19.3.0 clause 4.9.2, TS 26.247 V19.1.0 clauses 10.4 and 10.5):
 * a Metrics element with the attribute metrics, and a Reporting element whose schemeIdUri is urn:3GPP:ns:PSS:DASH:QM10 and whose
 * scheme information (ThreeGPQualityReporting) names the reportingServer and may give samplePercentage, reportingInterval, format
 * (uncompressed or gzip) and apn.
 */
data class ManifestMetricsConfiguration(
    val metricsKeys: List<String>,
    val samplingPeriod: Long,
    val reportingServer: String,
    val samplePercentage: Float?,
    val reportingInterval: Long?,
    val gzip: Boolean,
    val apn: String?
) {
    companion object {
        const val ID_PREFIX = "manifest-"
        private const val QM10 = "urn:3GPP:ns:PSS:DASH:QM10"

        /** The configurations of the QM10 scheme in the manifest; an unreadable manifest has none */
        fun parse(mpd: String): List<ManifestMetricsConfiguration> {
            val result = ArrayList<ManifestMetricsConfiguration>()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                // a manifest is data: no external entities
                for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
                    try {
                        factory.setFeature(feature, false)
                    } catch (_: Exception) {
                        // the parser of Android does not offer the feature and does not resolve external entities either
                    }
                }
                val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)))
                val metricsElements = document.getElementsByTagNameNS("*", "Metrics")
                for (i in 0 until metricsElements.length) {
                    val metrics = metricsElements.item(i) as? Element ?: continue
                    val keysAttribute = metrics.getAttribute("metrics")
                    if (keysAttribute.isBlank()) continue
                    val reportings = metrics.getElementsByTagNameNS("*", "Reporting")
                    for (j in 0 until reportings.length) {
                        val reporting = reportings.item(j) as? Element ?: continue
                        if (reporting.getAttribute("schemeIdUri") != QM10) continue
                        val info = firstChild(reporting, "ThreeGPQualityReporting") ?: reporting
                        val server = info.getAttribute("reportingServer")
                        if (server.isBlank()) continue
                        val keys = ArrayList<String>()
                        var samplingMs: Long? = null
                        for (token in keysAttribute.trim().split(Regex("\\s+"))) {
                            // a key may carry its parameters in parentheses (TS 26.247 clause 10.4): the first number is the interval in ms
                            val name = token.substringBefore('(')
                            keys.add(name)
                            if (samplingMs == null) {
                                samplingMs = Regex("\\((\\d+)").find(token)?.groupValues?.get(1)?.toLongOrNull()
                            }
                        }
                        result.add(
                            ManifestMetricsConfiguration(
                                keys,
                                ((samplingMs ?: 1000L) + 999L) / 1000L,
                                server,
                                info.getAttribute("samplePercentage").toFloatOrNull(),
                                info.getAttribute("reportingInterval").toLongOrNull(),
                                info.getAttribute("format") == "gzip",
                                info.getAttribute("apn").ifBlank { null }
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                return emptyList()
            }
            return result
        }

        private fun firstChild(parent: Element, localName: String): Element? {
            val nodes = parent.getElementsByTagNameNS("*", localName)
            return if (nodes.length > 0) nodes.item(0) as? Element else null
        }
    }

    /** The configuration in the form that the reporting controllers work with; the identifier tells that it comes from the manifest */
    fun toClientConfiguration(index: Int): ClientMetricsReportingConfiguration =
        ClientMetricsReportingConfiguration(
            metricsReportingConfigurationId = ID_PREFIX + index,
            serverAddresses = arrayListOf(reportingServer),
            scheme = QM10,
            dataNetworkName = apn,
            reportingInterval = reportingInterval,
            samplePercentage = samplePercentage,
            urlFilters = null,
            samplingPeriod = samplingPeriod,
            metrics = ArrayList(metricsKeys.map { "$QM10#$it" })
        )
}
