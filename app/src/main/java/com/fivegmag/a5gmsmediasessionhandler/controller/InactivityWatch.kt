package com.fivegmag.a5gmsmediasessionhandler.controller

import java.util.Timer
import java.util.TimerTask

/**
 * Detects the inactivity of the clients whose media delivery session was launched implicitly (TS 26.510 V19.2.0 clause 5.4.2.2): a client that
 * sent no message for the period is reported to onInactive, once, and is no longer watched. The callback runs on the thread of the timer.
 */
class InactivityWatch(
    private val periodMs: Long,
    private val onInactive: (Int) -> Unit
) : IController {

    private val lastActivity = HashMap<Int, Long>()
    private val tasks = HashMap<Int, TimerTask>()
    private var timer: Timer? = null

    /** Starts to watch the client (again), counting from now */
    @Synchronized
    fun watch(clientId: Int) {
        lastActivity[clientId] = System.nanoTime()
        schedule(clientId, periodMs)
    }

    /** The client was active; only a client that is watched is concerned */
    @Synchronized
    fun touch(clientId: Int) {
        if (lastActivity.containsKey(clientId)) lastActivity[clientId] = System.nanoTime()
    }

    @Synchronized
    fun isWatching(clientId: Int): Boolean = lastActivity.containsKey(clientId)

    @Synchronized
    private fun schedule(clientId: Int, delayMs: Long) {
        val running = timer ?: Timer("5GMS-InactivityWatch", true).also { timer = it }
        tasks.remove(clientId)?.cancel()
        val task = object : TimerTask() {
            override fun run() = check(clientId)
        }
        tasks[clientId] = task
        running.schedule(task, delayMs)
    }

    /** The task of a client that was touched meanwhile waits for the rest of the period; one that is no longer watched ends */
    private fun check(clientId: Int) {
        val inactive = synchronized(this) {
            val last = lastActivity[clientId] ?: return
            val idleMs = (System.nanoTime() - last) / 1_000_000L
            if (idleMs < periodMs) {
                schedule(clientId, periodMs - idleMs)
                false
            } else {
                lastActivity.remove(clientId)
                tasks.remove(clientId)
                true
            }
        }
        if (inactive) onInactive(clientId)
    }

    @Synchronized
    override fun resetClientSession(clientId: Int) {
        lastActivity.remove(clientId)
        tasks.remove(clientId)?.cancel()
    }

    @Synchronized
    override fun reset() {
        lastActivity.clear()
        tasks.clear()
        timer?.cancel()
        timer = null
    }
}
