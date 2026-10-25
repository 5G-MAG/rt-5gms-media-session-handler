package com.fivegmag.a5gmsmediasessionhandler.models

import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlingStates

/**
 * The general status of a media delivery session (TS 26.510 V19.2.0 table 11.2.3-1): the content identifier currently associated with
 * it and its sessionHandlingState, ACTIVE or ERRORED.
 */
data class GeneralSessionStatus(
    val contentIdentifier: String?,
    val mediaEntryPointUrl: String?,
    var sessionHandlingState: String = SessionHandlingStates.ACTIVE
) {
    /** clause 11.2.2.1: ACTIVE if all actions are successful, ERRORED if any of them fails */
    companion object {
        fun after(contentIdentifier: String?, mediaEntryPointUrl: String?, succeeded: Boolean) =
            GeneralSessionStatus(
                contentIdentifier,
                mediaEntryPointUrl,
                if (succeeded) SessionHandlingStates.ACTIVE else SessionHandlingStates.ERRORED
            )
    }
}
