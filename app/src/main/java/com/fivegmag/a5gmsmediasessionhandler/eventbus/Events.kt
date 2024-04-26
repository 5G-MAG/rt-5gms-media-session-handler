package com.fivegmag.a5gmsmediasessionhandler.eventbus

import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation

class ServiceAccessInformationUpdatedEvent(
    val clientId: Int,
    val previousServiceAccessInformation: ServiceAccessInformation?,
    val updatedServiceAccessInformation: ServiceAccessInformation?,
)