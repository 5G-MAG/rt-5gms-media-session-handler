package com.fivegmag.a5gmsmediasessionhandler.models

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.IConsumptionReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import okhttp3.Headers
import java.util.HashMap
import java.util.UUID
import java.util.Timer

data class ClientSessionData(
    var messenger: Messenger,
    var playbackState: String = PlayerStates.UNKNOWN,
    var initializedSession: Boolean = false,
    // TS 26.510 V19.2.0 clause 5.4.2.1: "In response, the Media Session Handler shall allocate a globally unique media delivery session identifier"
    var mediaDeliverySessionId: String = UUID.randomUUID().toString(),
    // TS 26.510 V19.2.0 table 11.2.3-1: the general status of the media delivery session; null until a session is created
    var generalStatus: GeneralSessionStatus? = null,
    // the metrics reporting configuration of the report sent last (table 11.6.2-1, lastMetricsReport)
    var lastMetricsConfigurationId: String? = null,
    // the client metrics configurations of the manifest, which replace those of the Service Access Information for the session
    // (TS 26.512 V19.3.0 clause 4.9.2)
    var manifestMetricsConfigurations: ArrayList<com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration>? = null,
    // the slices that the UE can use (null: the platform does not tell) and the networks obtained for the Data Network names of the configurations
    var availableSlices: List<com.fivegmag.a5gmsmediasessionhandler.controller.SliceId>? = null,
    var dataNetworks: HashMap<String, android.net.Network?> = HashMap(),
    // the format of the reports of a manifest configuration is gzip
    // step 10 of TS 26.501 V19.4.0 clause 5.2.3: the parameters of the MPD that the Media Player notified (null until it did)
    var manifestParameters: ManifestParameters? = null,
    // the access token that the application gave to createMediaDeliverySession: presented to the Media AF at M5 (TS 26.510 V19.2.0 table 11.2.2.1-1)
    var accessToken: String? = null,
    // the Media AS domain names of the media delivery session (TS 26.510 V19.2.0 table 11.2.2.1-1), which the scope of a Dynamic Policy follows (clause 11.3.1.2)
    var domainNames: List<String> = emptyList(),
    // the Dynamic Policy client API of this media delivery session (clause 11.3); null until the application uses it
    var dynamicPolicyService: com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyService? = null,
    // the Network Assistance client API of this media delivery session (clause 11.4); null until the application uses it
    var networkAssistanceService: com.fivegmag.a5gmsmediasessionhandler.controller.NetworkAssistanceService? = null,
    var manifestGzip: HashMap<String, Boolean> = HashMap(),
    var qoeMetricsReportingSessionData: HashMap<String, QoeMetricsReportingSessionDataEntry> = HashMap(),
    var consumptionReportingSessionData: ConsumptionReportingSessionData = ConsumptionReportingSessionData(),
    var serviceAccessInformationSessionData: ServiceAccessInformationSessionData = ServiceAccessInformationSessionData()
)

/** The value of the Authorization header for an access token, or null without one (RFC 6750) */
fun bearer(accessToken: String?): String? = accessToken?.let { "Bearer $it" }

data class ManifestParameters(
    val dynamic: Boolean,
    val durationMs: Long?,
    val minBufferTimeMs: Long?,
    val periodCount: Int
)

data class QoeMetricsReportingSessionDataEntry(
    var api: IMetricsReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
    // TS 26.510 V19.2.0 table 11.6.2-1: the scheme and the most recently sent report of this metrics reporting configuration
    var scheme: String? = null,
    var lastReport: String? = null,
    // clause 5.3.5.2: whether this client was selected by samplePercentage of the configuration; decided once per media delivery session
    var selected: Boolean = false,
    // the samplePercentage that the draw was made for
    var drawnForSamplePercentage: Float? = null,
    // metrics reporting of this configuration is active (METRICS_REPORTING_ACTIVATED was sent for it and STOPPED was not)
    var active: Boolean = false,
    // the start of the media delivery session for this configuration (System.nanoTime based, in ms): reportingStartOffset and
    // reportingDuration count from it
    var sessionStartMs: Long = 0L,
    // a report was requested from the Media Stream Handler and has not arrived; the window is checked when it was requested
    var reportRequested: Boolean = false,
    var singleReportTimer: Timer? = null,
)

data class ConsumptionReportingSessionData(
    var api: IConsumptionReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
    // TS 26.510 V19.2.0 table 11.5.2-1: the most recently sent consumption report
    var lastReport: String? = null,
    // clause 5.3.6.2: whether this client was selected by samplePercentage; decided once per media delivery session
    var selected: Boolean = false,
    // consumption reporting is active for this media delivery session (CONSUMPTION_REPORTING_ACTIVATED was sent and STOPPED was not)
    var active: Boolean = false,
)

data class ServiceAccessInformationSessionData(
    var serviceAccessInformation: ServiceAccessInformation? = null,
    var api: IServiceAccessInformationApi? = null,
    var responseHeaders: Headers? = null,
    var requestTimer: Timer? = null,
)