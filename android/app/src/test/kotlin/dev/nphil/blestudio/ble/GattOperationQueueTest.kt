package dev.nphil.blestudio.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue is the one place where a mistake silently corrupts evidence: Android's stack allows a
 * single outstanding ATT request and offers no request id, so correlation has to be exact.
 */
class GattOperationQueueTest {

    @Test
    fun `runs one request at a time and answers them in submission order`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 10_000)
        val launched = Channel<GattOpKey>(Channel.UNLIMITED)
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val answered = mutableListOf<Int>()

        val jobs = (1..3).map { index ->
            val key = GattOpKey.CharacteristicRead(index, "uuid-$index")
            launch {
                val result = queue.submit<Int>(key, "read $index") {
                    val concurrent = live.incrementAndGet()
                    peak.updateAndGet { seen -> maxOf(seen, concurrent) }
                    launched.trySend(key)
                    true
                }
                answered += result.value
            }
        }

        repeat(3) {
            val key = launched.receive() as GattOpKey.CharacteristicRead
            live.decrementAndGet()
            assertTrue("callback for $key should have matched", queue.complete(key, key.instanceId))
        }
        jobs.joinAll()

        assertEquals(1, peak.get())
        assertEquals(listOf(1, 2, 3), answered)
    }

    @Test
    fun `a request that misses its deadline fails instead of reporting success`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 5_000)

        val failure = assertFailsWith<GattTimeoutException> {
            queue.submit<Int>(GattOpKey.Mtu, "MTU request") { true }
        }
        assertTrue(failure.message!!.contains("timed out"))

        // A different attribute is unaffected: the link stays usable after a timeout.
        val next = async { queue.submit<Int>(GattOpKey.Rssi, "RSSI read") { true } }
        runCurrent()
        assertTrue(queue.complete(GattOpKey.Rssi, -47))
        assertEquals(-47, next.await().value)
    }

    @Test
    fun `a late callback never answers the next request for the same attribute`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 1_000)
        val key = GattOpKey.CharacteristicRead(7, "0000fff1-0000-1000-8000-00805f9b34fb")

        assertFailsWith<GattTimeoutException> { queue.submit<ByteArray>(key, "first read") { true } }

        val second = async { queue.submit<ByteArray>(key, "second read") { true } }
        runCurrent()

        // The peripheral finally answers the abandoned first read.
        assertFalse(queue.complete(key, byteArrayOf(0x11)))
        // …and the genuine answer to the second read still lands.
        assertTrue(queue.complete(key, byteArrayOf(0x22)))
        assertEquals(0x22, second.await().value.single().toInt())
    }

    @Test
    fun `a callback for a different attribute is ignored`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 2_000)
        val wanted = GattOpKey.CharacteristicRead(3, "uuid-a")
        val other = GattOpKey.CharacteristicRead(4, "uuid-b")

        // Wrapped in runCatching: a failing `async` would otherwise cancel the whole test scope.
        val op = async { runCatching { queue.submit<ByteArray>(wanted, "read") { true } } }
        runCurrent()
        assertFalse(queue.complete(other, byteArrayOf(0x01)))
        assertFailsWith<GattTimeoutException> { op.await().getOrThrow() }
    }

    @Test
    fun `an error status is surfaced with the status attached`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 2_000)
        val key = GattOpKey.CharacteristicWrite(9, "uuid-c")

        val op = async { runCatching { queue.submit<Unit>(key, "Write") { true } } }
        runCurrent()
        assertTrue(queue.fail(key, status = 5, detail = "insufficient authentication"))

        val failure = assertFailsWith<GattOperationException> { op.await().getOrThrow() }
        assertEquals(5, failure.status)
        assertTrue(failure.message!!.contains("insufficient authentication"))
    }

    @Test
    fun `shutdown fails the in-flight request and blocks new ones until reopen`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 10_000)

        val op = async { runCatching { queue.submit<Unit>(GattOpKey.Discover, "Service discovery") { true } } }
        runCurrent()
        queue.shutdown("link lost")
        assertFailsWith<GattDisconnectedException> { op.await().getOrThrow() }
        assertFailsWith<GattDisconnectedException> { queue.submit<Unit>(GattOpKey.Discover, "Service discovery") { true } }

        queue.reopen()
        val afterReconnect = async { queue.submit<Int>(GattOpKey.Mtu, "MTU request") { true } }
        runCurrent()
        assertTrue(queue.complete(GattOpKey.Mtu, 247))
        assertEquals(247, afterReconnect.await().value)
    }

    @Test
    fun `a request the stack refuses fails at once and frees the queue`() = runTest {
        val queue = GattOperationQueue(defaultTimeoutMs = 10_000)

        val refused = assertFailsWith<GattOperationException> {
            queue.submit<Int>(GattOpKey.Rssi, "RSSI read") { false }
        }
        assertFalse(refused is GattTimeoutException)
        assertTrue(refused.message!!.contains("rejected"))

        val next = async { queue.submit<Int>(GattOpKey.Rssi, "RSSI read") { true } }
        runCurrent()
        assertTrue(queue.complete(GattOpKey.Rssi, -60))
        assertEquals(-60, next.await().value)
    }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    val error = runCatching(block).exceptionOrNull()
        ?: throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
    if (error !is T) throw AssertionError("Expected ${T::class.java.simpleName} but got $error")
    return error
}
