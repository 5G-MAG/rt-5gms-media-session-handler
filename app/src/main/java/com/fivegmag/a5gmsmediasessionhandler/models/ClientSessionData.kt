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
    var qoeMetricsReportingSessionData: HashMap<String, QoeMetricsReportingSessionDataEntry> = HashMap(),
    var consumptionReportingSessionData: ConsumptionReportingSessionData = ConsumptionReportingSessionData(),
    var serviceAccessInformationSessionData: ServiceAccessInformationSessionData = ServiceAccessInformationSessionData()
)

data class QoeMetricsReportingSessionDataEntry(
    var api: IMetricsReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
    // TS 26.510 V19.2.0 table 11.6.2-1: the scheme and the most recently sent report of this metrics reporting configuration
    var scheme: String? = null,
    var lastReport: String? = null,
)

data class ConsumptionReportingSessionData(
    var api: IConsumptionReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
    // TS 26.510 V19.2.0 table 11.5.2-1: the most recently sent consumption report
    var lastReport: String? = null,
)

data class ServiceAccessInformationSessionData(
    var serviceAccessInformation: ServiceAccessInformation? = null,
    var api: IServiceAccessInformationApi? = null,
    var responseHeaders: Headers? = null,
    var requestTimer: Timer? = null,
)