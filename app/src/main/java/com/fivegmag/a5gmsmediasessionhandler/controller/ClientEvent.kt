package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys

/**
 * A notification event or a method reply as the Media Session Handler sends it to the client of a media delivery session: the name of the event
 * and the payload as keys of SessionHandlerBundleKeys. Kept free of the platform's Bundle so that what is sent can be tested; the message
 * handlers turn the payload into a bundle.
 */
data class ClientEvent(val name: String, val payload: Map<String, Any?>) {
    companion object {
        /** An event whose payload starts with the media delivery session identifier (the first item of every payload in tables 11.3.2-2 and 11.3.2-3) */
        fun of(name: String, mediaDeliverySessionId: String, vararg items: Pair<String, Any?>) = ClientEvent(
            name,
            linkedMapOf<String, Any?>(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId).apply {
                items.forEach { (key, value) -> if (value != null) put(key, value) }
            }
        )
    }
}
