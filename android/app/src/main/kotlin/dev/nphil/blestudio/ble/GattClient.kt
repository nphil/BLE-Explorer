package dev.nphil.blestudio.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.ConnectAttempt
import dev.nphil.blestudio.model.ConnectionFacts
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import dev.nphil.blestudio.model.GattDatabase
import dev.nphil.blestudio.model.WriteType
import dev.nphil.blestudio.model.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle of the single GATT link this client owns. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String, val services: GattDatabase) : ConnectionState
    data class Failed(val address: String, val reason: String) : ConnectionState
}

/** Negotiated PHYs as reported by `onPhyRead`/`onPhyUpdate`. */
data class PhyPair(val tx: Int, val rx: Int)

/**
 * One GATT connection at a time, every request serialised and bounded by a deadline.
 *
 * A fresh [BluetoothDevice] is resolved through [BluetoothAdapter.getRemoteDevice] before every
 * connect: reusing the object attached to an old `ScanResult` makes the stack reuse the address
 * type and the routing it cached during discovery, which is the classic cause of `status 133`.
 *
 * Nothing here ever waits without a bound, and a request that misses its deadline is always
 * reported as failed — never optimistically treated as delivered.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val discoverTimeoutMs: Long = DEFAULT_DISCOVER_TIMEOUT_MS,
    operationTimeoutMs: Long = GattOperationQueue.DEFAULT_TIMEOUT_MS,
) {
    private val queue = GattOperationQueue(operationTimeoutMs)
    private val connectGate = Mutex()
    private val lastTimestampMicros = AtomicLong(0L)
    private val linkSamples = AtomicInteger()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _facts = MutableStateFlow(ConnectionFacts())
    val facts: StateFlow<ConnectionFacts> = _facts.asStateFlow()

    private val _subscriptions = MutableStateFlow<Set<CharacteristicRef>>(emptySet())
    val subscriptions: StateFlow<Set<CharacteristicRef>> = _subscriptions.asStateFlow()

    /**
     * Link-setup timings this client has ever recorded, including the ones
     * [ConnectionFacts.reconnectSamplesMs] has since dropped off its front.
     *
     * That list is capped, so its size stops growing while samples keep arriving; a saver that
     * wants "what is new since last time" has to count the arrivals, not index the list.
     */
    val linkSampleCount: Int get() = linkSamples.get()

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    /** Label of the request currently waiting for a callback, for progress affordances. */
    val inFlightLabel: String? get() = queue.inFlightLabel

    @Volatile
    private var session: Session? = null

    /**
     * The `BluetoothGatt` handle only exists once `connectGatt` returns, but the stack may already
     * be delivering callbacks by then. The session is published *before* the call and [claim]s the
     * handle from whichever side sees it first, so an early `onConnectionStateChange` is never
     * mistaken for a stale connection and closed out from under us.
     */
    private class Session(val address: String) {
        val linkReady = CompletableDeferred<Unit>()
        val linkClosed = CompletableDeferred<Unit>()
        val characteristics = ConcurrentHashMap<CharacteristicRef, BluetoothGattCharacteristic>()

        @Volatile
        var gatt: BluetoothGatt? = null
            private set

        @Synchronized
        fun claim(candidate: BluetoothGatt): Boolean {
            val current = gatt
            if (current == null) {
                gatt = candidate
                return true
            }
            return current === candidate
        }

        fun link(): BluetoothGatt = gatt ?: throw GattDisconnectedException("Not connected to a device.")
    }

    fun hasConnectPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Connect, discover, negotiate MTU and read the PHY. Any previously held link is torn down
     * first so only one device is ever connected.
     *
     * @return the freshly discovered attribute database.
     */
    suspend fun connect(address: String): GattDatabase = connectGate.withLock {
        val startedAtEpochMs = System.currentTimeMillis()
        val startedAtNanos = System.nanoTime()
        try {
            val database = doConnect(address, startedAtNanos)
            recordAttempt(startedAtEpochMs, elapsedMs(startedAtNanos), true, null)
            database
        } catch (cancellation: CancellationException) {
            teardown("Connection to $address was cancelled", ConnectionState.Disconnected)
            throw cancellation
        } catch (error: Throwable) {
            val reason = error.message ?: error::class.java.simpleName
            recordAttempt(startedAtEpochMs, elapsedMs(startedAtNanos), false, reason)
            teardown(reason, ConnectionState.Failed(address, reason))
            throw error
        }
    }

    private suspend fun doConnect(address: String, startedAtNanos: Long): GattDatabase {
        if (!hasConnectPermission()) {
            throw GattOperationException("Nearby devices permission (BLUETOOTH_CONNECT) has not been granted.")
        }
        val adapter = bluetoothManager.adapter
            ?: throw GattOperationException("This device has no Bluetooth adapter.")
        if (!adapter.isEnabled) throw GattOperationException("Bluetooth is switched off.")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw GattOperationException("\"$address\" is not a valid Bluetooth address.")
        }

        teardown("Replaced by a new connection to $address", null)
        queue.reopen()
        _facts.update { it.copy(negotiatedMtu = null, txPhy = null, rxPhy = null) }
        _subscriptions.value = emptySet()
        _state.value = ConnectionState.Connecting(address)
        emitSystem(AttOperation.OTHER, "Connecting to $address")

        // Fresh device handle on every attempt — never a cached ScanResult route.
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val active = Session(address)
        session = active
        val opened = device.connectGatt(
            context,
            /* autoConnect = */ false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
        ) ?: throw GattOperationException("The Bluetooth stack refused to open a GATT client.")
        if (!active.claim(opened)) {
            runCatching { opened.close() }
            throw GattOperationException("The GATT client was replaced while connecting.")
        }

        val linked = withTimeoutOrNull(connectTimeoutMs) { runCatching { active.linkReady.await() } }
            ?: throw GattTimeoutException("Connecting to $address timed out after $connectTimeoutMs ms")
        linked.getOrThrow()
        val linkMs = elapsedMs(startedAtNanos)
        _facts.update { it.copy(reconnectSamplesMs = (it.reconnectSamplesMs + linkMs).takeLast(RECONNECT_SAMPLES)) }
        // Counted after the list is published, so a reader that takes the count first and the
        // facts second can only ever under-count - which the next save picks up.
        linkSamples.incrementAndGet()

        queue.submit<Unit>(GattOpKey.Discover, "Service discovery", discoverTimeoutMs) {
            active.link().discoverServices()
        }
        val services = active.link().services.orEmpty()
        active.characteristics.clear()
        for (service in services) {
            for (characteristic in service.characteristics) {
                active.characteristics[service.ref(characteristic)] = characteristic
            }
        }
        val database = services.toGattDatabase()
        _state.value = ConnectionState.Connected(address, database)
        emitSystem(
            AttOperation.DISCOVERY,
            "Discovered ${services.size} services, ${active.characteristics.size} characteristics",
            status = BluetoothGatt.GATT_SUCCESS,
        )

        // MTU and PHY are advisory: a peripheral that refuses them is still perfectly usable.
        runCatching { requestMtu(PREFERRED_MTU) }
            .onFailure { emitSystem(AttOperation.ERROR, "MTU negotiation failed: ${it.message}") }
        runCatching { readPhy() }
            .onFailure { emitSystem(AttOperation.ERROR, "PHY read failed: ${it.message}") }
        return database
    }

    /** Fail everything outstanding, drop the link and release the client interface. */
    fun disconnect() {
        teardown("Disconnected by the operator", ConnectionState.Disconnected)
    }

    /** Release every resource; call from `ViewModel.onCleared`. */
    fun close() {
        teardown("Client closed", ConnectionState.Disconnected)
    }

    suspend fun readCharacteristic(ref: CharacteristicRef): ByteArray {
        val active = requireSession()
        val characteristic = requireCharacteristic(active, ref)
        emitEvent(EventDirection.LOCAL_TO_DEVICE, AttOperation.READ_REQUEST, ref, EMPTY, null)
        val result = try {
            queue.submit<ByteArray>(GattOpKey.CharacteristicRead(characteristic.instanceId, ref.uuid), "Read ${displayName(ref.uuid)}") {
                active.link().readCharacteristic(characteristic)
            }
        } catch (error: GattOperationException) {
            emitEvent(EventDirection.DEVICE_TO_LOCAL, AttOperation.ERROR, ref, EMPTY, error.status, error.message.orEmpty())
            noteAuthenticationNeeded(error.status)
            throw error
        }
        observeLatency(result.elapsedMs)
        emitEvent(EventDirection.DEVICE_TO_LOCAL, AttOperation.READ_RESPONSE, ref, result.value, BluetoothGatt.GATT_SUCCESS)
        return result.value
    }

    suspend fun writeCharacteristic(ref: CharacteristicRef, value: ByteArray, writeType: WriteType) {
        val active = requireSession()
        val characteristic = requireCharacteristic(active, ref)
        val platformType = when (writeType) {
            WriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.SIGNED -> BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
        }
        val requestOp = if (writeType == WriteType.WITHOUT_RESPONSE) AttOperation.WRITE_COMMAND else AttOperation.WRITE_REQUEST
        emitEvent(EventDirection.LOCAL_TO_DEVICE, requestOp, ref, value, null)
        val result = try {
            queue.submit<Unit>(GattOpKey.CharacteristicWrite(characteristic.instanceId, ref.uuid), "Write ${displayName(ref.uuid)}") {
                writeCharacteristicCompat(active.link(), characteristic, value, platformType)
            }
        } catch (error: GattOperationException) {
            emitEvent(EventDirection.DEVICE_TO_LOCAL, AttOperation.ERROR, ref, EMPTY, error.status, error.message.orEmpty())
            noteAuthenticationNeeded(error.status)
            throw error
        }
        observeLatency(result.elapsedMs)
        if (writeType == WriteType.WITHOUT_RESPONSE) {
            _facts.update { if (it.writeWithoutResponseVerified) it else it.copy(writeWithoutResponseVerified = true) }
        }
        emitEvent(
            EventDirection.DEVICE_TO_LOCAL,
            AttOperation.WRITE_RESPONSE,
            ref,
            EMPTY,
            BluetoothGatt.GATT_SUCCESS,
            note = "${result.elapsedMs} ms",
        )
    }

    suspend fun readDescriptor(ref: DescriptorRef): ByteArray {
        val active = requireSession()
        val descriptor = requireDescriptor(active, ref)
        val ownerId = descriptor.characteristic.instanceId
        val result = try {
            queue.submit<ByteArray>(GattOpKey.DescriptorRead(ownerId, ref.uuid), "Read descriptor ${displayName(ref.uuid)}") {
                active.link().readDescriptor(descriptor)
            }
        } catch (error: GattOperationException) {
            emitEvent(EventDirection.DEVICE_TO_LOCAL, AttOperation.ERROR, ref.characteristic, EMPTY, error.status, error.message.orEmpty())
            noteAuthenticationNeeded(error.status)
            throw error
        }
        observeLatency(result.elapsedMs)
        emitEvent(
            EventDirection.DEVICE_TO_LOCAL,
            AttOperation.READ_RESPONSE,
            ref.characteristic,
            result.value,
            BluetoothGatt.GATT_SUCCESS,
            note = "descriptor ${shortUuid(ref.uuid)}",
        )
        return result.value
    }

    suspend fun writeDescriptor(ref: DescriptorRef, value: ByteArray) {
        val active = requireSession()
        val descriptor = requireDescriptor(active, ref)
        writeDescriptorInternal(active, descriptor, ref.characteristic, value)
    }

    /**
     * Subscribe (or unsubscribe) using the property the characteristic actually advertises:
     * NOTIFY wins over INDICATE when both are present because it costs one packet instead of two.
     *
     * @return true when the peripheral was subscribed with indications rather than notifications.
     */
    suspend fun setNotificationsEnabled(ref: CharacteristicRef, enabled: Boolean): Boolean {
        val active = requireSession()
        val characteristic = requireCharacteristic(active, ref)
        val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            throw GattOperationException("${displayName(ref.uuid)} advertises neither NOTIFY nor INDICATE.")
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
            ?: throw GattOperationException("${displayName(ref.uuid)} has no Client Characteristic Configuration descriptor.")

        if (!active.link().setCharacteristicNotification(characteristic, enabled)) {
            throw GattOperationException("The Bluetooth stack refused to route notifications for ${displayName(ref.uuid)}.")
        }
        val indication = supportsIndicate && !supportsNotify
        val payload = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            indication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        try {
            writeDescriptorInternal(active, cccd, ref, payload)
        } catch (error: GattOperationException) {
            runCatching { active.link().setCharacteristicNotification(characteristic, false) }
            throw error
        }
        _subscriptions.update { current -> if (enabled) current + ref else current - ref }
        emitSystem(
            AttOperation.OTHER,
            buildString {
                append(if (enabled) "Subscribed to " else "Unsubscribed from ")
                append(displayName(ref.uuid))
                if (enabled) append(if (indication) " (indications)" else " (notifications)")
            },
            status = BluetoothGatt.GATT_SUCCESS,
        )
        return indication
    }

    suspend fun requestMtu(mtu: Int): Int {
        val active = requireSession()
        val result = queue.submit<Int>(GattOpKey.Mtu, "MTU request") { active.link().requestMtu(mtu) }
        _facts.update { it.copy(negotiatedMtu = result.value) }
        emitSystem(AttOperation.OTHER, "MTU negotiated: ${result.value} bytes", status = BluetoothGatt.GATT_SUCCESS)
        return result.value
    }

    suspend fun readPhy(): PhyPair {
        val active = requireSession()
        val result = queue.submit<PhyPair>(GattOpKey.Phy, "PHY read") {
            active.link().readPhy()
            true
        }
        _facts.update { it.copy(txPhy = result.value.tx, rxPhy = result.value.rx) }
        emitSystem(
            AttOperation.OTHER,
            "PHY: tx ${phyName(result.value.tx)}, rx ${phyName(result.value.rx)}",
            status = BluetoothGatt.GATT_SUCCESS,
        )
        return result.value
    }

    suspend fun readRemoteRssi(): Int {
        val active = requireSession()
        return queue.submit<Int>(GattOpKey.Rssi, "RSSI read") { active.link().readRemoteRssi() }.value
    }

    private suspend fun writeDescriptorInternal(
        active: Session,
        descriptor: BluetoothGattDescriptor,
        owner: CharacteristicRef,
        value: ByteArray,
    ) {
        val ownerId = descriptor.characteristic.instanceId
        val key = GattOpKey.DescriptorWrite(ownerId, descriptor.uuid.toString())
        emitEvent(
            EventDirection.LOCAL_TO_DEVICE,
            AttOperation.WRITE_REQUEST,
            owner,
            value,
            null,
            note = "descriptor ${shortUuid(descriptor.uuid.toString())}",
        )
        val result = try {
            queue.submit<Unit>(key, "Write descriptor ${displayName(descriptor.uuid.toString())}") {
                writeDescriptorCompat(active.link(), descriptor, value)
            }
        } catch (error: GattOperationException) {
            emitEvent(EventDirection.DEVICE_TO_LOCAL, AttOperation.ERROR, owner, EMPTY, error.status, error.message.orEmpty())
            noteAuthenticationNeeded(error.status)
            throw error
        }
        observeLatency(result.elapsedMs)
        emitEvent(
            EventDirection.DEVICE_TO_LOCAL,
            AttOperation.WRITE_RESPONSE,
            owner,
            EMPTY,
            BluetoothGatt.GATT_SUCCESS,
            note = "descriptor ${shortUuid(descriptor.uuid.toString())}",
        )
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    /** True when [candidate] belongs to the live session; late callbacks from an old link are dropped. */
    private fun owns(candidate: BluetoothGatt): Boolean = session?.claim(candidate) == true

    private fun requireSession(): Session = session
        ?: throw GattDisconnectedException("Not connected to a device.")

    private fun requireCharacteristic(active: Session, ref: CharacteristicRef): BluetoothGattCharacteristic =
        active.characteristics[ref]
            ?: throw GattOperationException("${displayName(ref.uuid)} is not part of the connected device's database.")

    private fun requireDescriptor(active: Session, ref: DescriptorRef): BluetoothGattDescriptor {
        val characteristic = requireCharacteristic(active, ref.characteristic)
        val uuid = runCatching { java.util.UUID.fromString(ref.uuid) }.getOrNull()
            ?: throw GattOperationException("\"${ref.uuid}\" is not a UUID.")
        val descriptors = characteristic.descriptors
        val byIndex = descriptors.getOrNull(ref.index)?.takeIf { it.uuid == uuid }
        return byIndex
            ?: descriptors.firstOrNull { it.uuid == uuid }
            ?: throw GattOperationException("Descriptor ${shortUuid(ref.uuid)} is missing from ${displayName(ref.characteristic.uuid)}.")
    }

    private fun teardown(reason: String, finalState: ConnectionState?) {
        val active = session ?: run {
            if (finalState != null) _state.value = finalState
            return
        }
        session = null
        queue.shutdown(reason)
        _subscriptions.value = emptySet()
        if (!active.linkReady.isCompleted) {
            active.linkReady.completeExceptionally(GattDisconnectedException(reason))
        }
        val gatt = active.gatt
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            // Give the stack a moment to emit STATE_DISCONNECTED so the peripheral learns the link
            // is gone, then release the client interface unconditionally. Bounded — never an open
            // await.
            val closer = scope.launch {
                withTimeoutOrNull(CLOSE_GRACE_MS) { active.linkClosed.await() }
                runCatching { gatt.close() }
            }
            // `close()` is called from `ViewModel.onCleared`, by which point the viewModelScope is
            // already cancelled and the coroutine above never runs. Releasing the client interface
            // is not optional: the stack allows a handful per process and a leaked one is only
            // reclaimed when the process dies, so close it directly on that path.
            closer.invokeOnCompletion { cause -> if (cause != null) runCatching { gatt.close() } }
        }
        emitSystem(AttOperation.OTHER, "Disconnected from ${active.address}: $reason")
        if (finalState != null) _state.value = finalState
    }

    private fun onLinkLost(address: String, status: Int) {
        val reason = if (status == BluetoothGatt.GATT_SUCCESS) {
            "The peripheral closed the connection."
        } else {
            "Link lost (${gattStatusName(status)})."
        }
        teardown(reason, ConnectionState.Failed(address, reason))
    }

    private fun recordAttempt(startedAtEpochMs: Long, durationMs: Long, success: Boolean, error: String?) {
        _facts.update { current ->
            val attempt = ConnectAttempt(
                startedEpochMs = startedAtEpochMs,
                durationMs = durationMs,
                success = success,
                source = EventSource.LIVE_GATT,
                error = error,
            )
            current.copy(connectAttempts = (current.connectAttempts + attempt).takeLast(CONNECT_ATTEMPTS))
        }
    }

    private fun observeLatency(elapsedMs: Long) {
        _facts.update { current ->
            if ((current.maxObservedResponseMs ?: -1L) >= elapsedMs) current
            else current.copy(maxObservedResponseMs = elapsedMs)
        }
    }

    private fun noteAuthenticationNeeded(status: Int?) {
        val needsPairing = status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_AUTHORIZATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_AUTH_FAIL
        if (!needsPairing) return
        _facts.update { if (it.pairingRequired == true) it else it.copy(pairingRequired = true) }
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

    private fun nextTimestampMicros(): Long {
        val now = System.currentTimeMillis() * 1_000L
        while (true) {
            val previous = lastTimestampMicros.get()
            val candidate = if (now > previous) now else previous + 1
            if (lastTimestampMicros.compareAndSet(previous, candidate)) return candidate
        }
    }

    private fun emitEvent(
        direction: EventDirection,
        operation: AttOperation,
        ref: CharacteristicRef?,
        payload: ByteArray,
        status: Int?,
        note: String = "",
    ) {
        _events.tryEmit(
            BleEvent(
                timestampEpochMicros = nextTimestampMicros(),
                direction = direction,
                source = EventSource.LIVE_GATT,
                operation = operation,
                serviceUuid = ref?.serviceUuid,
                characteristicUuid = ref?.uuid,
                attributeHandle = ref?.instanceId,
                payloadHex = payload.toHex(),
                status = status,
                note = note,
            ),
        )
    }

    private fun emitSystem(operation: AttOperation, note: String, status: Int? = null) {
        _events.tryEmit(
            BleEvent(
                timestampEpochMicros = nextTimestampMicros(),
                direction = EventDirection.SYSTEM,
                source = EventSource.LIVE_GATT,
                operation = operation,
                payloadHex = "",
                status = status,
                note = note,
            ),
        )
    }

    private fun refFor(characteristic: BluetoothGattCharacteristic): CharacteristicRef? {
        val service = characteristic.service ?: return null
        return service.ref(characteristic)
    }

    @Suppress("DEPRECATION")
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val active = session
            if (active == null || !active.claim(gatt)) {
                // A link we no longer track: release its client interface instead of leaking it.
                runCatching { gatt.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        active.linkReady.complete(Unit)
                    } else {
                        active.linkReady.completeExceptionally(
                            GattOperationException("Connection failed (${gattStatusName(status)})", status),
                        )
                    }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    active.linkClosed.complete(Unit)
                    if (!active.linkReady.isCompleted) {
                        active.linkReady.completeExceptionally(
                            GattOperationException("Connection failed (${gattStatusName(status)})", status),
                        )
                    } else {
                        onLinkLost(active.address, status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!owns(gatt)) return
            resolve(GattOpKey.Discover, status, Unit)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!owns(gatt)) return
            resolve(GattOpKey.CharacteristicRead(characteristic.instanceId, characteristic.uuid.toString()), status, value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!owns(gatt)) return
            val value = characteristic.value ?: EMPTY
            resolve(GattOpKey.CharacteristicRead(characteristic.instanceId, characteristic.uuid.toString()), status, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!owns(gatt)) return
            resolve(GattOpKey.CharacteristicWrite(characteristic.instanceId, characteristic.uuid.toString()), status, Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!owns(gatt)) return
            publishNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!owns(gatt)) return
            publishNotification(characteristic, characteristic.value ?: EMPTY)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            if (!owns(gatt)) return
            resolve(
                GattOpKey.DescriptorRead(descriptor.characteristic.instanceId, descriptor.uuid.toString()),
                status,
                value,
            )
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!owns(gatt)) return
            resolve(
                GattOpKey.DescriptorRead(descriptor.characteristic.instanceId, descriptor.uuid.toString()),
                status,
                descriptor.value ?: EMPTY,
            )
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!owns(gatt)) return
            resolve(
                GattOpKey.DescriptorWrite(descriptor.characteristic.instanceId, descriptor.uuid.toString()),
                status,
                Unit,
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!owns(gatt)) return
            resolve(GattOpKey.Mtu, status, mtu)
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (!owns(gatt)) return
            resolve(GattOpKey.Phy, status, PhyPair(txPhy, rxPhy))
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (!owns(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            _facts.update { it.copy(txPhy = txPhy, rxPhy = rxPhy) }
            emitSystem(AttOperation.OTHER, "PHY updated: tx ${phyName(txPhy)}, rx ${phyName(rxPhy)}", status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (!owns(gatt)) return
            resolve(GattOpKey.Rssi, status, rssi)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            if (!owns(gatt)) return
            emitSystem(AttOperation.DISCOVERY, "The peripheral reported a Service Changed indication.")
        }
    }

    private fun resolve(key: GattOpKey, status: Int, value: Any?) {
        if (status == BluetoothGatt.GATT_SUCCESS) queue.complete(key, value)
        else queue.fail(key, status, gattStatusName(status))
    }

    private fun publishNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val ref = refFor(characteristic) ?: return
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        emitEvent(
            EventDirection.DEVICE_TO_LOCAL,
            if (indication) AttOperation.INDICATION else AttOperation.NOTIFICATION,
            ref,
            value,
            null,
        )
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_DISCOVER_TIMEOUT_MS = 20_000L
        const val PREFERRED_MTU = 517

        private const val CLOSE_GRACE_MS = 2_000L
        private const val EVENT_BUFFER = 512
        private const val RECONNECT_SAMPLES = 20
        private const val CONNECT_ATTEMPTS = 50
        private val EMPTY = ByteArray(0)

        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_AUTHORIZATION = 8
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val GATT_AUTH_FAIL = 137

        fun phyName(phy: Int): String = when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "Coded"
            else -> "PHY $phy"
        }

        /** Human-readable ATT/GATT status; the numeric code is always kept alongside. */
        fun gattStatusName(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "success"
            0x01 -> "invalid handle (1)"
            0x02 -> "read not permitted (2)"
            0x03 -> "write not permitted (3)"
            0x04 -> "invalid PDU (4)"
            GATT_INSUFFICIENT_AUTHENTICATION -> "insufficient authentication (5) — the device needs pairing"
            0x06 -> "request not supported (6)"
            0x07 -> "invalid offset (7)"
            GATT_INSUFFICIENT_AUTHORIZATION -> "insufficient authorization (8)"
            0x09 -> "prepare queue full (9)"
            0x0A -> "attribute not found (10)"
            0x0B -> "attribute not long (11)"
            0x0C -> "insufficient encryption key size (12)"
            0x0D -> "invalid attribute value length (13)"
            0x0E -> "unlikely error (14)"
            GATT_INSUFFICIENT_ENCRYPTION -> "insufficient encryption (15) — the device needs pairing"
            0x10 -> "unsupported group type (16)"
            0x11 -> "insufficient resources (17)"
            0x13 -> "remote device terminated the connection (19)"
            0x16 -> "local host terminated the connection (22)"
            0x22 -> "LMP response timeout (34)"
            GATT_AUTH_FAIL -> "authentication failure (137)"
            0x8F -> "connection congested (143)"
            0x85 -> "generic GATT error (133) — usually a stale device handle or an out-of-range peripheral"
            0x3E -> "connection establishment failed (62)"
            else -> "status $status"
        }
    }
}
