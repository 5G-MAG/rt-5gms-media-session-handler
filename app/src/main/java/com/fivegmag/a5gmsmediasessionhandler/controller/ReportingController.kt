package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel

open class ReportingController(
    val clientsSessionData: HashMap<Int, ClientSessionModel>
) {

    val utils = Utils()

    fun shouldReportAccordingToSamplePercentage(samplePercentage: Float?): Boolean {
        if (samplePercentage != null && samplePercentage <= 0) {
            return false
        }

        if (samplePercentage == null || samplePercentage >= 100.0) {
            return true
        }

        return utils.generateRandomFloat() < samplePercentage
    }
}