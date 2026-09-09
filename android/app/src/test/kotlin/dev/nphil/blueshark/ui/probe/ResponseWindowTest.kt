package dev.nphil.blueshark.ui.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Command Prober's correlation contract.
 *
 * A device that answers slowly must never have its reply counted as the *next* step's: that turns a
 * nonexistent opcode into an "accepted" one and a real one into silence, which is the exact wrong
 * conclusion the screen exists to prevent. Every assertion here is one clause of that contract.
 */
class ResponseWindowTest {

    @Test
    fun `a frame inside the window becomes that step's response`() = runBlocking {
        val window = ResponseWindow(stepIndex = 3, stepLabel = "0x08")

        assertTrue(window.offer(byteArrayOf(0x01, 0x00, 0x03)))

        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x03), window.await(50))
    }

    @Test
    fun `a second frame in the same window is refused, so one step never gets two answers`() = runBlocking {
        val window = ResponseWindow(stepIndex = 0, stepLabel = "0x06")
        window.offer(byteArrayOf(0x11))

        assertFalse(window.offer(byteArrayOf(0x22)))

        assertArrayEquals(byteArrayOf(0x11), window.await(50))
    }

    @Test
    fun `nothing arriving inside the window is silence, not a stale answer`() = runBlocking {
        assertNull(ResponseWindow(stepIndex = 1, stepLabel = "0x07").await(20))
    }

    @Test
    fun `a frame offered after close is refused, which is what files it as late`() = runBlocking {
        val window = ResponseWindow(stepIndex = 5, stepLabel = "0x09")
        assertNull(window.await(10))
        window.close()

        assertFalse(window.offer(byteArrayOf(0x33)))

        // And the refusal still names the step it missed, so the late frame is attributed to step
        // 5 rather than offered to step 6.
        assertEquals(5, window.stepIndex)
        assertNull(window.accepted)
    }

    /**
     * Regression: a timeout must shut the gate itself, atomically.
     *
     * When `await` merely returned on timeout and the caller closed afterwards, a frame landing in
     * that gap won `offer` - so the collector counted it as this step's answer while the runner,
     * having already read null, recorded NO_RESPONSE and discarded it. The frame was then neither
     * the answer nor a late arrival: it vanished, and the vanished frame was the evidence.
     */
    @Test
    fun `a frame arriving after the timeout is refused, never silently swallowed`() = runBlocking {
        val window = ResponseWindow(stepIndex = 4, stepLabel = "0x08")

        assertNull("the step timed out", window.await(20))

        // No close() call in between - the timeout alone must have shut the window.
        assertFalse("the late frame must lose, so route() files it under step 4", window.offer(byteArrayOf(0x77)))
        assertNull("and it must not have become this step's answer", window.accepted)
    }

    @Test
    fun `closing after a frame was accepted does not retract it`() = runBlocking {
        val window = ResponseWindow(stepIndex = 2, stepLabel = "0x23")
        window.offer(byteArrayOf(0x44))

        window.close()

        assertArrayEquals(byteArrayOf(0x44), window.accepted)
    }

    /**
     * A frame that wins the gate microseconds before the deadline is still this step's answer.
     * `await` reads the field after waiting rather than taking the timeout's word for it, so the
     * response cannot vanish between the two clocks - modelled here by a zero-length wait.
     */
    @Test
    fun `a frame that beat the deadline survives a wait that has already expired`() = runBlocking {
        val window = ResponseWindow(stepIndex = 0, stepLabel = "0x01")
        window.offer(byteArrayOf(0x55))

        assertArrayEquals(byteArrayOf(0x55), window.await(0))
    }

    @Test
    fun `exactly one of many concurrent offers wins the window`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(50) { round ->
                val window = ResponseWindow(stepIndex = round, stepLabel = "0x08")
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val winners = AtomicInteger()
                repeat(threads) { id ->
                    pool.execute {
                        start.await()
                        if (window.offer(byteArrayOf(id.toByte()))) winners.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals("round $round", 1, winners.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The runner's close-before-open ordering, played out: a straggler from step N that turns up
     * while step N+1's window is open is refused by N's closed window, and offering it to N+1 is
     * never attempted because the collector only ever holds one window - the newest.
     */
    @Test
    fun `a straggler cannot be moved from a closed window to the next one`() = runBlocking {
        val stepN = ResponseWindow(stepIndex = 7, stepLabel = "0x08")
        assertNull(stepN.await(10))
        stepN.close()

        val stepNext = ResponseWindow(stepIndex = 8, stepLabel = "0x09")
        val straggler = byteArrayOf(0x01, 0x00, 0x03)

        assertFalse("step 7's window is closed", stepN.offer(straggler))
        // The next step's own answer is unaffected by the straggler ever having existed.
        assertTrue(stepNext.offer(byteArrayOf(0x02)))
        assertArrayEquals(byteArrayOf(0x02), stepNext.await(50))
    }

    /**
     * The runner closing a window and the collector offering a frame are genuinely concurrent.
     * Whichever wins, the window is never left in a state where a frame is both this step's answer
     * and a late arrival, or neither.
     */
    @Test
    fun `the gate is decided once, so close and offer cannot both win`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { round ->
                val window = ResponseWindow(stepIndex = round, stepLabel = "0x08")
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                val accepted = AtomicBoolean(false)
                pool.execute {
                    start.await()
                    accepted.set(window.offer(byteArrayOf(0x66)))
                    done.countDown()
                }
                pool.execute {
                    start.await()
                    window.close()
                    done.countDown()
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))

                val stored = window.accepted
                if (accepted.get()) {
                    assertArrayEquals("round $round", byteArrayOf(0x66), stored)
                } else {
                    assertNull("round $round", stored)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
