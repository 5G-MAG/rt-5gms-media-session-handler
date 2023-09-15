package com.fivegmag.a5gmsmediasessionhandler.models

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi
import okhttp3.Headers
import java.util.Timer

data class ClientSessionModel(
    var messenger: Messenger?,
    var serviceAccessInformation: ServiceAccessInformation? = null,
    var serviceAccessInformationApi: ServiceAccessInformationApi? = null,
    var consumptionReportingApi: ConsumptionReportingApi? = null,
    var serviceAccessInformationResponseHeaders: Headers? = null,
    var serviceAccessInformationRequestTimer: Timer? = null,
    var consumptionReportingTimer: Timer? = null,
    var isConsumptionReport: Boolean = false
)