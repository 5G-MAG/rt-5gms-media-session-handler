package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.InactivityWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TS 26.510 V19.2.0 clause 5.4.2.2: a media delivery session that was launched implicitly ends after an implementation-specific period of inactivity */
class InactivityWatchUnitTest {
    private val inactive = CopyOnWriteArrayList<Int>()
    private val first = CountDownLatch(1)
    private val watch = InactivityWatch(300) { inactive.add(it); first.countDown() }

    @After
    fun stop() = watch.reset()

    @Test
    fun aClientThatSendsNothingIsInactiveOnce() {
        watch.watch(1)
        assertTrue(first.await(3, TimeUnit.SECONDS))
        Thread.sleep(700)
        assertEquals(listOf(1), inactive.toList())
        assertFalse(watch.isWatching(1))
    }

    @Test
    fun activityRestartsThePeriod() {
        val started = System.nanoTime()
        watch.watch(1)
        for (i in 1..3) {
            Thread.sleep(150)
            watch.touch(1)
        }
        assertTrue(inactive.isEmpty())
        assertTrue(first.await(3, TimeUnit.SECONDS))
        // the period counts from the last activity, 450 ms after the start
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 450 + 300 - 20)
    }

    @Test
    fun aClientThatIsNotWatchedIsNotConcerned() {
        watch.touch(7)
        assertFalse(watch.isWatching(7))
        Thread.sleep(500)
        assertTrue(inactive.isEmpty())
    }

    @Test
    fun aSessionThatEndedIsNoLongerWatched() {
        watch.watch(1)
        watch.watch(2)
        watch.resetClientSession(1)
        assertTrue(first.await(3, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(listOf(2), inactive.toList())
    }

    @Test
    fun watchingAgainStartsANewPeriod() {
        watch.watch(1)
        Thread.sleep(200)
        watch.watch(1)
        Thread.sleep(200)
        assertTrue(inactive.isEmpty())
        assertTrue(first.await(3, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertEquals(listOf(1), inactive.toList())
    }

    @Test
    fun resetStopsAllWatching() {
        watch.watch(1)
        watch.watch(2)
        watch.reset()
        Thread.sleep(600)
        assertTrue(inactive.isEmpty())
    }
}
