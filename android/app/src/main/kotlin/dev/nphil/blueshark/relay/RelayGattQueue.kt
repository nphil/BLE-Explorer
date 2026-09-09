package dev.nphil.blueshark.relay

import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Serialized, bounded operation queue for the relay's target-side [BluetoothGatt].
 *
 * The Android GATT client tolerates exactly one outstanding ATT transaction per connection; a
 * second request while one is in flight is dropped by the stack and its callback never arrives.
 * Every target-side operation therefore passes through [execute], which
 *
 *  * holds a mutex for the whole request/response round trip, so operations cannot interleave,
 *  * records the expected callback (kind, owning `instanceId` and attribute UUID) and refuses to
 *    resolve a request from an unrelated response - `BluetoothGattDescriptor` has no public
 *    instance id, so a descriptor is identified by its characteristic's instance id plus its UUID,
 *  * bounds every wait with a timeout and reports [STATUS_TIMEOUT] instead of a success, and
 *  * fails every waiter immediately when the link drops ([failAll]), so nothing awaits forever.
 */
internal class RelayGattQueue(private val onUnmatched: (String) -> Unit = {}) {

    enum class Kind { DISCOVER, MTU, READ_CHARACTERISTIC, WRITE_CHARACTERISTIC, READ_DESCRIPTOR, WRITE_DESCRIPTOR }

    /** Result of one round trip. [status] is a GATT status, or one of the STATUS_* sentinels below. */
    class Outcome(val status: Int, val value: ByteArray?) {
        val ok: Boolean get() = status == BluetoothGatt.GATT_SUCCESS
    }

    private class Pending(
        val kind: Kind,
        val handle: Int,
        val uuid: UUID?,
        val label: String,
        val deferred: CompletableDeferred<Outcome>,
    )

    private val mutex = Mutex()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var closedStatus: Int? = null

    val isClosed: Boolean get() = closedStatus != null

    /**
     * Runs one operation to completion. [issue] must start the operation on the [BluetoothGatt] and
     * return whether the stack accepted it; it is invoked with the queue lock held and after the
     * expected response has been registered, so a callback delivered on another thread cannot race
     * ahead of the bookkeeping.
     */
    suspend fun execute(
        kind: Kind,
        handle: Int,
        uuid: UUID?,
        label: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        issue: () -> Boolean,
    ): Outcome = mutex.withLock {
        val closed = closedStatus
        if (closed != null) return@withLock Outcome(closed, null)
        val slot = Pending(kind, handle, uuid, label, CompletableDeferred())
        pending = slot
        try {
            val accepted = try {
                issue()
            } catch (security: SecurityException) {
                onUnmatched("$label rejected by the platform: ${security.message}")
                false
            }
            if (!accepted) {
                Outcome(STATUS_NOT_ISSUED, null)
            } else {
                withTimeoutOrNull(timeoutMs) { slot.deferred.await() } ?: Outcome(STATUS_TIMEOUT, null)
            }
        } finally {
            if (pending === slot) pending = null
        }
    }

    /** Called from the GATT callback thread; resolves the in-flight operation when it matches. */
    fun complete(kind: Kind, handle: Int, uuid: UUID?, status: Int, value: ByteArray?) {
        val slot = pending
        if (slot == null) {
            onUnmatched("Ignored a late $kind response (handle $handle, status ${statusText(status)}); nothing was in flight")
            return
        }
        if (slot.kind != kind || slot.handle != handle || slot.uuid != uuid) {
            onUnmatched(
                "Ignored an unexpected $kind response (handle $handle, uuid $uuid) while waiting for ${slot.label}",
            )
            return
        }
        pending = null
        slot.deferred.complete(Outcome(status, value))
    }

    /** Fails the in-flight operation and refuses further work; used when the target link drops. */
    fun failAll(status: Int) {
        closedStatus = status
        val slot = pending
        pending = null
        slot?.deferred?.complete(Outcome(status, null))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DISCOVER_TIMEOUT_MS = 20_000L

        /** No ATT response arrived inside the timeout: the operation state is unknown, never successful. */
        const val STATUS_TIMEOUT = 0x201

        /** The stack refused to start the operation (busy, wrong state, missing permission). */
        const val STATUS_NOT_ISSUED = 0x202

        /** The link was gone before or during the operation. */
        const val STATUS_LINK_LOST = 0x203

        fun statusText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "success"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "read not permitted (0x02)"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "write not permitted (0x03)"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "insufficient authentication (0x05)"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "request not supported (0x06)"
            BluetoothGatt.GATT_INVALID_OFFSET -> "invalid offset (0x07)"
            0x08 -> "insufficient authorization (0x08)" // GATT_INSUFFICIENT_AUTHORIZATION is API 33+
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "invalid attribute length (0x0D)"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "insufficient encryption (0x0F)"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "connection congested (0x8F)"
            BluetoothGatt.GATT_FAILURE -> "failure (0x101)"
            STATUS_TIMEOUT -> "timed out"
            STATUS_NOT_ISSUED -> "rejected by the local stack"
            STATUS_LINK_LOST -> "link lost"
            else -> "status 0x${Integer.toHexString(status).uppercase()}"
        }
    }
}
