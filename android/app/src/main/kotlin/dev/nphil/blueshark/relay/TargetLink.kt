package dev.nphil.blueshark.relay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import dev.nphil.blueshark.model.ConnectAttempt
import dev.nphil.blueshark.model.EventSource
import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.GattDescriptorRecord
import dev.nphil.blueshark.model.GattServiceRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Generic Access and Generic Attribute are owned by the local stack and cannot be mirrored. */
internal val GAP_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
internal val GATT_SERVICE_UUID: UUID = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
internal val SCCD_UUID: UUID = UUID.fromString("00002903-0000-1000-8000-00805F9B34FB")

internal const val DEFAULT_ATT_MTU = 23
internal const val MAX_ATT_MTU = 517

/**
 * Value of `BluetoothStatusCodes.SUCCESS`, declared locally: that class only exists from API 33, so
 * comparing against it outside an SDK check would drag it into a code path API 31/32 executes.
 */
internal const val PLATFORM_STATUS_SUCCESS = 0

/** Descriptors have no public instance id, so they are addressed through their characteristic. */
internal fun BluetoothGattDescriptor.ownerInstanceId(): Int = characteristic?.instanceId ?: 0

/**
 * Single-use client link to the real device.
 *
 * A [TargetLink] owns exactly one [BluetoothGatt] for one connection attempt: the route is resolved
 * from a fresh [BluetoothAdapter.getRemoteDevice] on every connect, and once the link is closed the
 * instance is spent. That rules out the classic failure of reconnecting through a stale
 * `ScanResult`-derived device, which the stack accepts and then never connects.
 */
@SuppressLint("MissingPermission")
internal class TargetLink(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val onNotification: (BluetoothGattCharacteristic, ByteArray) -> Unit = { _, _ -> },
    private val onLinkLost: (Int) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    private val appContext: Context = context.applicationContext

    private val queue = RelayGattQueue { onLog(it) }

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var connectDeferred: CompletableDeferred<Int>? = null

    @Volatile
    var mtu: Int = DEFAULT_ATT_MTU
        private set

    @Volatile
    var connected: Boolean = false
        private set

    var targetName: String? = null
        private set

    var services: List<BluetoothGattService> = emptyList()
        private set

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(instance: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectDeferred?.complete(BluetoothGatt.GATT_SUCCESS)
                return
            }
            connected = false
            queue.failAll(RelayGattQueue.STATUS_LINK_LOST)
            val waiter = connectDeferred
            if (waiter != null && !waiter.isCompleted) {
                waiter.complete(if (status == BluetoothGatt.GATT_SUCCESS) STATUS_CLOSED_BEFORE_READY else status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLinkLost(status)
            }
        }

        override fun onMtuChanged(instance: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@TargetLink.mtu = mtu
            queue.complete(RelayGattQueue.Kind.MTU, 0, null, status, null)
        }

        override fun onServicesDiscovered(instance: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) services = instance.services.orEmpty()
            queue.complete(RelayGattQueue.Kind.DISCOVER, 0, null, status, null)
        }

        override fun onCharacteristicRead(
            instance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            queue.complete(
                RelayGattQueue.Kind.READ_CHARACTERISTIC,
                characteristic.instanceId,
                characteristic.uuid,
                status,
                value,
            )
        }

        @Deprecated("Superseded by the value-carrying overload on API 33+; still delivered on API 31/32.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            instance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue.complete(
                RelayGattQueue.Kind.READ_CHARACTERISTIC,
                characteristic.instanceId,
                characteristic.uuid,
                status,
                characteristic.value,
            )
        }

        override fun onCharacteristicWrite(
            instance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue.complete(
                RelayGattQueue.Kind.WRITE_CHARACTERISTIC,
                characteristic.instanceId,
                characteristic.uuid,
                status,
                null,
            )
        }

        override fun onDescriptorRead(
            instance: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            queue.complete(
                RelayGattQueue.Kind.READ_DESCRIPTOR,
                descriptor.ownerInstanceId(),
                descriptor.uuid,
                status,
                value,
            )
        }

        @Deprecated("Superseded by the value-carrying overload on API 33+; still delivered on API 31/32.")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(
            instance: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue.complete(
                RelayGattQueue.Kind.READ_DESCRIPTOR,
                descriptor.ownerInstanceId(),
                descriptor.uuid,
                status,
                descriptor.value,
            )
        }

        override fun onDescriptorWrite(
            instance: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue.complete(
                RelayGattQueue.Kind.WRITE_DESCRIPTOR,
                descriptor.ownerInstanceId(),
                descriptor.uuid,
                status,
                null,
            )
        }

        override fun onCharacteristicChanged(
            instance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(characteristic, value)
        }

        @Deprecated("Superseded by the value-carrying overload on API 33+; still delivered on API 31/32.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            instance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            onNotification(characteristic, characteristic.value ?: EMPTY)
        }
    }

    /**
     * Connects to [address]. Returns the attempt plus a human-readable reason when it failed.
     *
     * The transport-selecting `connectGatt` overload is deprecated on API 37 in favour of
     * `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`, which does
     * not exist below API 37. With minSdk 31 this overload is the only way to pin TRANSPORT_LE, and
     * pinning it matters: on a dual-mode gadget the default transport can pick BR/EDR and never
     * complete.
     */
    @Suppress("DEPRECATION")
    suspend fun connect(address: String, timeoutMs: Long = CONNECT_TIMEOUT_MS): ConnectOutcome {
        val startedAt = System.currentTimeMillis()
        fun outcome(error: String?): ConnectOutcome = ConnectOutcome(
            error = error,
            attempt = ConnectAttempt(
                startedEpochMs = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                success = error == null,
                source = EventSource.MITM_RELAY,
                error = error,
            ),
        )

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (invalid: IllegalArgumentException) {
            return outcome("\"$address\" is not a Bluetooth address: ${invalid.message}")
        }
        val waiter = CompletableDeferred<Int>()
        connectDeferred = waiter
        val instance = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (security: SecurityException) {
            connectDeferred = null
            return outcome("BLUETOOTH_CONNECT was refused: ${security.message}")
        }
        if (instance == null) {
            connectDeferred = null
            return outcome("The platform refused to open a GATT client for $address")
        }
        gatt = instance
        val status = withTimeoutOrNull(timeoutMs) { waiter.await() }
        connectDeferred = null
        if (status == null) {
            close()
            return outcome(
                "$address did not connect within ${timeoutMs}ms; it may be out of range, asleep, " +
                    "or already connected to another phone",
            )
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            close()
            val detail = if (status == STATUS_CLOSED_BEFORE_READY) {
                "the link dropped before it was usable"
            } else {
                RelayGattQueue.statusText(status)
            }
            return outcome("$address refused the connection: $detail")
        }
        targetName = runCatching { device.name }.getOrNull()
        return outcome(null)
    }

    /** Negotiates the largest MTU the target accepts; returns the value actually in effect. */
    suspend fun negotiateMtu(request: Int = MAX_ATT_MTU): Int {
        val instance = gatt ?: return mtu
        val outcome = queue.execute(RelayGattQueue.Kind.MTU, 0, null, "requestMtu($request)") {
            instance.requestMtu(request)
        }
        if (!outcome.ok) {
            onLog("MTU stayed at $mtu: requestMtu($request) ${RelayGattQueue.statusText(outcome.status)}")
        }
        return mtu
    }

    /** Returns null on success, otherwise the reason discovery failed. */
    suspend fun discoverServices(): String? {
        val instance = gatt ?: return "The target link is closed"
        val outcome = queue.execute(
            RelayGattQueue.Kind.DISCOVER,
            0,
            null,
            "discoverServices()",
            RelayGattQueue.DISCOVER_TIMEOUT_MS,
        ) { instance.discoverServices() }
        if (!outcome.ok) return "Service discovery failed: ${RelayGattQueue.statusText(outcome.status)}"
        if (services.isEmpty()) return "The target exposes no GATT services"
        return null
    }

    suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): RelayGattQueue.Outcome {
        val instance = gatt ?: return RelayGattQueue.Outcome(RelayGattQueue.STATUS_LINK_LOST, null)
        return queue.execute(
            RelayGattQueue.Kind.READ_CHARACTERISTIC,
            characteristic.instanceId,
            characteristic.uuid,
            "read ${characteristic.uuid}",
        ) { instance.readCharacteristic(characteristic) }
    }

    @Suppress("DEPRECATION")
    suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): RelayGattQueue.Outcome {
        val instance = gatt ?: return RelayGattQueue.Outcome(RelayGattQueue.STATUS_LINK_LOST, null)
        return queue.execute(
            RelayGattQueue.Kind.WRITE_CHARACTERISTIC,
            characteristic.instanceId,
            characteristic.uuid,
            "write ${characteristic.uuid}",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instance.writeCharacteristic(characteristic, value, writeType) == PLATFORM_STATUS_SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                instance.writeCharacteristic(characteristic)
            }
        }
    }

    suspend fun readDescriptor(descriptor: BluetoothGattDescriptor): RelayGattQueue.Outcome {
        val instance = gatt ?: return RelayGattQueue.Outcome(RelayGattQueue.STATUS_LINK_LOST, null)
        return queue.execute(
            RelayGattQueue.Kind.READ_DESCRIPTOR,
            descriptor.ownerInstanceId(),
            descriptor.uuid,
            "read descriptor ${descriptor.uuid}",
        ) { instance.readDescriptor(descriptor) }
    }

    @Suppress("DEPRECATION")
    suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): RelayGattQueue.Outcome {
        val instance = gatt ?: return RelayGattQueue.Outcome(RelayGattQueue.STATUS_LINK_LOST, null)
        return queue.execute(
            RelayGattQueue.Kind.WRITE_DESCRIPTOR,
            descriptor.ownerInstanceId(),
            descriptor.uuid,
            "write descriptor ${descriptor.uuid}",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instance.writeDescriptor(descriptor, value) == PLATFORM_STATUS_SUCCESS
            } else {
                descriptor.value = value
                instance.writeDescriptor(descriptor)
            }
        }
    }

    /** Local-only switch that has to be on before the stack forwards notifications to this process. */
    fun setNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean): Boolean {
        val instance = gatt ?: return false
        return runCatching { instance.setCharacteristicNotification(characteristic, enable) }.getOrDefault(false)
    }

    fun snapshotDatabase(): GattDatabase = GattDatabase(
        services = services.map { service ->
            GattServiceRecord(
                uuid = service.uuid.toString().uppercase(),
                instanceId = service.instanceId,
                type = if (service.type == BluetoothGattService.SERVICE_TYPE_SECONDARY) "secondary" else "primary",
                characteristics = service.characteristics.orEmpty().map { characteristic ->
                    GattCharacteristicRecord(
                        uuid = characteristic.uuid.toString().uppercase(),
                        instanceId = characteristic.instanceId,
                        properties = characteristicPropertyNames(characteristic.properties),
                        permissions = attributePermissionNames(characteristic.permissions),
                        // BluetoothGattDescriptor exposes no instance id, so the position inside the
                        // characteristic is the only stable identity available on the client side.
                        descriptors = characteristic.descriptors.orEmpty().mapIndexed { index, descriptor ->
                            GattDescriptorRecord(
                                uuid = descriptor.uuid.toString().uppercase(),
                                instanceId = index,
                                permissions = attributePermissionNames(descriptor.permissions),
                            )
                        },
                    )
                },
            )
        },
    )

    /** Idempotent; every failure path in the relay funnels through here. */
    fun close() {
        queue.failAll(RelayGattQueue.STATUS_LINK_LOST)
        val instance = gatt
        gatt = null
        connected = false
        if (instance != null) {
            runCatching { instance.disconnect() }
            runCatching { instance.close() }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        private const val STATUS_CLOSED_BEFORE_READY = 0x2FF
        private val EMPTY = ByteArray(0)
    }
}

internal class ConnectOutcome(val error: String?, val attempt: ConnectAttempt)

internal fun characteristicPropertyNames(properties: Int): List<String> {
    val names = ArrayList<String>(4)
    if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names += "broadcast"
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "read"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "write-without-response"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "write"
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "notify"
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "indicate"
    if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names += "signed-write"
    if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) names += "extended-properties"
    return names
}

internal fun attributePermissionNames(permissions: Int): List<String> {
    val names = ArrayList<String>(2)
    if (permissions and BluetoothGattCharacteristic.PERMISSION_READ != 0) names += "read"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) names += "read-encrypted"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0) names += "read-encrypted-mitm"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) names += "write"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0) names += "write-encrypted"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0) names += "write-encrypted-mitm"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED != 0) names += "write-signed"
    if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM != 0) names += "write-signed-mitm"
    return names
}

internal class TargetSnapshot(
    val address: String,
    val name: String?,
    val mtu: Int,
    val gatt: GattDatabase,
    val mirrorableServiceUuids: List<String>,
    val skippedServiceUuids: List<String>,
)

/**
 * One-shot connect, MTU negotiation and discovery for the relay screen's "Preview target" action.
 * The link is always closed again, including on every failure path, so the target is free for the
 * vendor app or for the relay itself.
 */
@SuppressLint("MissingPermission")
internal suspend fun previewTarget(
    context: Context,
    adapter: BluetoothAdapter,
    address: String,
    onAttempt: (ConnectAttempt) -> Unit = {},
    onLog: (String) -> Unit = {},
): Result<TargetSnapshot> {
    val link = TargetLink(context, adapter, onLog = onLog)
    try {
        val connect = link.connect(address)
        onAttempt(connect.attempt)
        val error = connect.error
        if (error != null) return Result.failure(IllegalStateException(error))
        val mtu = link.negotiateMtu()
        val discoveryError = link.discoverServices()
        if (discoveryError != null) return Result.failure(IllegalStateException(discoveryError))
        val skipped = link.services
            .filter { it.uuid == GAP_SERVICE_UUID || it.uuid == GATT_SERVICE_UUID }
            .map { it.uuid.toString().uppercase() }
        val mirrorable = link.services
            .filterNot { it.uuid == GAP_SERVICE_UUID || it.uuid == GATT_SERVICE_UUID }
            .filter { it.type == BluetoothGattService.SERVICE_TYPE_PRIMARY }
            .map { it.uuid.toString().uppercase() }
        return Result.success(
            TargetSnapshot(
                address = address,
                name = link.targetName,
                mtu = mtu,
                gatt = link.snapshotDatabase(),
                mirrorableServiceUuids = mirrorable,
                skippedServiceUuids = skipped,
            ),
        )
    } finally {
        link.close()
    }
}
