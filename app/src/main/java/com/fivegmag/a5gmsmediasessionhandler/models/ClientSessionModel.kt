package com.fivegmag.a5gmsmediasessionhandler.models

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import java.util.*
import kotlin.collections.HashMap

data class ClientSessionModel(
    var messenger: Messenger?,
    var serviceAccessInformation: ServiceAccessInformation?,
    var metricReportingTimer: LinkedList<Timer>
)
