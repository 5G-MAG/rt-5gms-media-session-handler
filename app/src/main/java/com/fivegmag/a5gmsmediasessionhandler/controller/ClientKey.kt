package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Message
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import java.util.Objects

/**
 * The key of a media delivery session context: the application (the uid of the sender) and the Media Stream Handler instance that
 * sent the message. An application that runs several instances has several media delivery sessions at the same time.
 */
object ClientKey {
    fun of(msg: Message): Int {
        val instance = msg.peekData()?.getString(SessionHandlerBundleKeys.CLIENT_INSTANCE_ID) ?: ""
        return Objects.hash(msg.sendingUid, instance)
    }
}
