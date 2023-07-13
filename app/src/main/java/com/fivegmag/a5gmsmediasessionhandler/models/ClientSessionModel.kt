package com.fivegmag.a5gmsmediasessionhandler.models

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.MetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import java.util.*
import kotlin.collections.HashMap

data class ClientSessionModel(
    var messenger: Messenger?,
    var serviceAccessInformation: ServiceAccessInformation? = null,
    var metricReportingTimer: LinkedList<Timer> = LinkedList(),
    var serviceAccessInformationApi: ServiceAccessInformationApi? = null,
    var metricsReportingApi: MetricsReportingApi? = null
)
