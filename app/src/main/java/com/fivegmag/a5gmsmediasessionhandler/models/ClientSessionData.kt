package com.fivegmag.a5gmsmediasessionhandler.models

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.MetricsReportingApi
import okhttp3.Headers
import java.util.HashMap
import java.util.Timer

data class ClientSessionData(
    var messenger: Messenger,
    var playbackState: String = PlayerStates.UNKNOWN,
    var initializedSession: Boolean = false,
    var qoeMetricsReportingSessionData: HashMap<String, QoeMetricsReportingSessionDataEntry> = HashMap(),
    var consumptionReportingSessionData: ConsumptionReportingSessionData = ConsumptionReportingSessionData(),
    var serviceAccessInformationSessionData: ServiceAccessInformationSessionData = ServiceAccessInformationSessionData()
)

data class QoeMetricsReportingSessionDataEntry(
    var api: MetricsReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
)

data class ConsumptionReportingSessionData(
    var api: ConsumptionReportingApi? = null,
    var reportingTimer: Timer? = null,
    var reportingSelectedServerAddress: String? = null,
)

data class ServiceAccessInformationSessionData(
    var serviceAccessInformation: ServiceAccessInformation? = null,
    var api: ServiceAccessInformationApi? = null,
    var responseHeaders: Headers? = null,
    var requestTimer: Timer? = null,
)