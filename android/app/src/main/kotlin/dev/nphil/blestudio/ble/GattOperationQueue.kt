package dev.nphil.blestudio.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/** Every GATT request this app can issue, keyed so the matching callback can be correlated back. */
sealed interface GattOpKey {
    data class CharacteristicRead(val instanceId: Int, val uuid: String) : GattOpKey
    data class CharacteristicWrite(val instanceId: Int, val uuid: String) : GattOpKey
    data class DescriptorRead(val characteristicInstanceId: Int, val uuid: String) : GattOpKey
    data class DescriptorWrite(val characteristicInstanceId: Int, val uuid: String) : GattOpKey
    data object Discover : GattOpKey
    data object Mtu : GattOpKey
    data object Phy : GattOpKey
    data object Rssi : GattOpKey
}

/** A GATT request that the stack rejected, answered with an error status, or never answered. */
open class GattOperationException(
    message: String,
    /** ATT/GATT status from the callback, when the failure came from the peripheral. */
    val status: Int? = null,
) : Exception(message)

/** The peripheral never answered within the operation deadline. Link is left untouched. */
class GattTimeoutException(message: String) : GattOperationException(message)

/** The queue was torn down (disconnect, link loss) while this request was queued or in flight. */
class GattDisconnectedException(message: String) : GattOperationException(message)

/** Result of one completed request plus how long the peripheral took to answer it. */
class GattOpResult<T>(val value: T, val elapsedMs: Long)

/**
 * Serialises GATT traffic: Android's stack allows exactly one outstanding ATT request per
 * connection, and a second `readCharacteristic`/`writeCharacteristic` issued before the previous
 * callback simply returns `false` and is dropped.
 *
 * Callers suspend in [submit]; the fair [Mutex] gives FIFO ordering and guarantees a single
 * in-flight request. The GATT callback thread resolves the request through [complete]/[fail].
 * Every request is bounded by a deadline: a request that times out is reported as a failure and
 * its key is remembered as orphaned, so a late callback can never be mistaken for the answer to
 * the *next* request on the same attribute.
 */
class GattOperationQueue(private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private class Pending(
        val key: GattOpKey,
        val label: String,
        val deferred: CompletableDeferred<Any?>,
        val startedAtNanos: Long,
    )

    private val gate = Mutex()
    private val inFlight = AtomicReference<Pending?>(null)
    private val orphaned = HashMap<GattOpKey, Int>()
    private val orphanLock = Any()

    @Volatile
    private var shutdownReason: String? = null

    /**
     * Enqueue one request. [launch] issues the platform call and returns whether the stack accepted
     * it; it runs while the queue is held, so nothing else can interleave.
     *
     * @throws GattDisconnectedException when the link went away before or during the request.
     * @throws GattTimeoutException when the peripheral did not answer within [timeoutMs].
     * @throws GattOperationException when the stack refused the call or the peripheral errored.
     */
    suspend fun <T> submit(
        key: GattOpKey,
        label: String,
        timeoutMs: Long = defaultTimeoutMs,
        launch: () -> Boolean,
    ): GattOpResult<T> = gate.withLock {
        shutdownReason?.let { throw GattDisconnectedException("$label cancelled: $it") }
        val pending = Pending(key, label, CompletableDeferred(), System.nanoTime())
        inFlight.set(pending)
        try {
            if (!launch()) {
                inFlight.compareAndSet(pending, null)
                throw GattOperationException("$label was rejected by the Bluetooth stack")
            }
            val value = withTimeout(timeoutMs) { pending.deferred.await() }
            @Suppress("UNCHECKED_CAST")
            GattOpResult(value as T, (System.nanoTime() - pending.startedAtNanos) / 1_000_000L)
        } catch (timeout: TimeoutCancellationException) {
            if (inFlight.compareAndSet(pending, null)) orphan(key)
            throw GattTimeoutException("$label timed out after $timeoutMs ms")
        } finally {
            inFlight.compareAndSet(pending, null)
        }
    }

    /** Resolve the in-flight request identified by [key]. Returns false when nothing matched. */
    fun complete(key: GattOpKey, value: Any?): Boolean {
        if (dropOrphan(key)) return false
        val pending = inFlight.get() ?: return false
        if (pending.key != key) return false
        if (!inFlight.compareAndSet(pending, null)) return false
        return pending.deferred.complete(value)
    }

    /** Fail the in-flight request identified by [key] with a peripheral-supplied [status]. */
    fun fail(key: GattOpKey, status: Int, detail: String? = null): Boolean {
        if (dropOrphan(key)) return false
        val pending = inFlight.get() ?: return false
        if (pending.key != key) return false
        if (!inFlight.compareAndSet(pending, null)) return false
        val suffix = detail?.let { " ($it)" } ?: ""
        return pending.deferred.completeExceptionally(
            GattOperationException("${pending.label} failed with status $status$suffix", status),
        )
    }

    /**
     * Tear the queue down. The in-flight request fails immediately and, while [reason] is set,
     * every queued or future [submit] fails instead of touching a dead `BluetoothGatt`.
     */
    fun shutdown(reason: String) {
        shutdownReason = reason
        synchronized(orphanLock) { orphaned.clear() }
        val pending = inFlight.getAndSet(null) ?: return
        pending.deferred.completeExceptionally(GattDisconnectedException("${pending.label} cancelled: $reason"))
    }

    /** Re-arm the queue for a new connection. */
    fun reopen() {
        synchronized(orphanLock) { orphaned.clear() }
        shutdownReason = null
        inFlight.set(null)
    }

    /** Label of the request currently awaiting a callback, for UI progress. */
    val inFlightLabel: String? get() = inFlight.get()?.label

    private fun orphan(key: GattOpKey) {
        synchronized(orphanLock) { orphaned[key] = (orphaned[key] ?: 0) + 1 }
    }

    private fun dropOrphan(key: GattOpKey): Boolean = synchronized(orphanLock) {
        val count = orphaned[key] ?: return false
        if (count <= 1) orphaned.remove(key) else orphaned[key] = count - 1
        true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
