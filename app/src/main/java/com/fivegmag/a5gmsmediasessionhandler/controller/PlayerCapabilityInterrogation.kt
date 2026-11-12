package com.fivegmag.a5gmsmediasessionhandler.controller

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The interrogation of the capabilities of the Media Player (TS 26.512 V19.3.0 clause 4.7.3: "The Media Session Handler shall interrogate the
 * capabilities of the Media Player"; table 13.2.4-1: capabilities, a read-only list of table 13.2.4-2). The request goes to the Media Stream
 * Handler of the media delivery session by the function given to interrogate(); the reply is handed to [onReply]. interrogate() blocks, so the caller runs it off the
 * main thread, which is also the thread of the replies. A Media Player that does not answer within the timeout, or answers nothing, has no
 * capability: the specification names no other source than the reply.
 */
class PlayerCapabilityInterrogation(private val timeoutMs: Long) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Set<String>>>()

    /** The capabilities that the Media Player of [key] reports; empty when it does not answer in time */
    fun interrogate(key: String, ask: () -> Unit): Set<String> {
        val reply = CompletableFuture<Set<String>>()
        pending[key] = reply
        return try {
            ask()
            reply.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            emptySet()
        } finally {
            pending.remove(key, reply)
        }
    }

    /** The reply of the Media Player of [key]; a reply that nobody waits for is ignored */
    fun onReply(key: String, capabilities: Collection<String>?) {
        pending[key]?.complete(capabilities.orEmpty().toSet())
    }
}
