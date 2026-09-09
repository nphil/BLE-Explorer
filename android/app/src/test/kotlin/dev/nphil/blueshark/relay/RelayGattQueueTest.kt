package dev.nphil.blueshark.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The queue is the relay's reliability core: a lost or mismatched GATT callback must never look like
 * a success, and nothing may wait forever. Every case here corresponds to a failure that is easy to
 * reintroduce and impossible to see in a screenshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayGattQueueTest {

    private val charUuid: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    private val otherUuid: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")

    @Test
    fun `a matching response resolves the operation with its value`() = runTest {
        val queue = RelayGattQueue()
        val operation = async {
            queue.execute(RelayGattQueue.Kind.READ_CHARACTERISTIC, 42, charUuid, "read") { true }
        }
        runCurrent()
        queue.complete(RelayGattQueue.Kind.READ_CHARACTERISTIC, 42, charUuid, 0, byteArrayOf(1, 2, 3))
        val outcome = operation.await()
        assertTrue(outcome.ok)
        assertArrayEquals(byteArrayOf(1, 2, 3), outcome.value)
    }

    @Test
    fun `a response for another attribute is ignored and the operation times out`() = runTest {
        val unmatched = ArrayList<String>()
        val queue = RelayGattQueue { unmatched += it }
        val operation = async {
            queue.execute(RelayGattQueue.Kind.READ_CHARACTERISTIC, 42, charUuid, "read ffe1") { true }
        }
        runCurrent()
        // Same kind and handle, different attribute: the stack answered something else.
        queue.complete(RelayGattQueue.Kind.READ_CHARACTERISTIC, 42, otherUuid, 0, byteArrayOf(9))
        // Right attribute, wrong operation kind.
        queue.complete(RelayGattQueue.Kind.WRITE_CHARACTERISTIC, 42, charUuid, 0, null)
        advanceUntilIdle()
        val outcome = operation.await()
        assertFalse(outcome.ok)
        assertEquals(RelayGattQueue.STATUS_TIMEOUT, outcome.status)
        assertEquals(null, outcome.value)
        assertEquals(2, unmatched.size)
        assertTrue(unmatched.all { it.contains("read ffe1") })
    }

    @Test
    fun `an operation the stack refuses fails immediately instead of waiting`() = runTest {
        val queue = RelayGattQueue()
        val outcome = queue.execute(RelayGattQueue.Kind.WRITE_CHARACTERISTIC, 7, charUuid, "write") { false }
        assertEquals(RelayGattQueue.STATUS_NOT_ISSUED, outcome.status)
        assertFalse(outcome.ok)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a security exception while issuing is reported as not issued`() = runTest {
        val logged = ArrayList<String>()
        val queue = RelayGattQueue { logged += it }
        val outcome = queue.execute(RelayGattQueue.Kind.READ_DESCRIPTOR, 3, charUuid, "read cccd") {
            throw SecurityException("BLUETOOTH_CONNECT")
        }
        assertEquals(RelayGattQueue.STATUS_NOT_ISSUED, outcome.status)
        assertTrue(logged.single().contains("BLUETOOTH_CONNECT"))
    }

    @Test
    fun `losing the link releases the waiter and refuses further work`() = runTest {
        val queue = RelayGattQueue()
        val operation = async {
            queue.execute(RelayGattQueue.Kind.WRITE_CHARACTERISTIC, 11, charUuid, "write") { true }
        }
        runCurrent()
        queue.failAll(RelayGattQueue.STATUS_LINK_LOST)
        val outcome = operation.await()
        assertEquals(RelayGattQueue.STATUS_LINK_LOST, outcome.status)
        assertTrue(queue.isClosed)

        var issued = false
        val afterwards = queue.execute(RelayGattQueue.Kind.READ_CHARACTERISTIC, 12, charUuid, "read") {
            issued = true
            true
        }
        assertEquals(RelayGattQueue.STATUS_LINK_LOST, afterwards.status)
        assertFalse("a closed queue must not touch the gatt again", issued)
    }

    @Test
    fun `operations are serialized so the stack only ever sees one in flight`() = runTest {
        val queue = RelayGattQueue()
        val trace = ArrayList<String>()
        val first = async {
            queue.execute(RelayGattQueue.Kind.READ_CHARACTERISTIC, 1, charUuid, "first") {
                trace += "issue first"
                true
            }
        }
        val second = async {
            queue.execute(RelayGattQueue.Kind.READ_CHARACTERISTIC, 2, otherUuid, "second") {
                trace += "issue second"
                true
            }
        }
        runCurrent()
        assertEquals(listOf("issue first"), trace)

        queue.complete(RelayGattQueue.Kind.READ_CHARACTERISTIC, 1, charUuid, 0, byteArrayOf(1))
        runCurrent()
        assertEquals(listOf("issue first", "issue second"), trace)

        queue.complete(RelayGattQueue.Kind.READ_CHARACTERISTIC, 2, otherUuid, 0, byteArrayOf(2))
        assertTrue(first.await().ok)
        assertTrue(second.await().ok)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
