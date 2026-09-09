package dev.nphil.blestudio.relay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.ConnectAttempt
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import dev.nphil.blestudio.model.GattDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the relay is doing. The machine only ever moves forward; a restart builds a new run. */
sealed interface RelayPhase {
    data object Idle : RelayPhase
    data object ConnectingTarget : RelayPhase
    data object Cloning : RelayPhase
    data object Advertising : RelayPhase
    data class VendorConnected(val address: String) : RelayPhase
    data object Stopping : RelayPhase
    data class Failed(val reason: String) : RelayPhase

    val label: String
        get() = when (this) {
            Idle -> "Idle"
            ConnectingTarget -> "Connecting to target"
            Cloning -> "Cloning GATT database"
            Advertising -> "Advertising as the target"
            is VendorConnected -> "Vendor app connected"
            Stopping -> "Stopping"
            is Failed -> "Failed"
        }

    val isActive: Boolean
        get() = this is ConnectingTarget || this is Cloning || this is Advertising || this is VendorConnected
}

data class RelayCounters(
    val reads: Int = 0,
    val writes: Int = 0,
    val notifies: Int = 0,
    val errors: Int = 0,
)

data class RelayConfig(
    val targetAddress: String,
    val alias: String = "",
    /** Service UUIDs to advertise; empty means every mirrored primary service. */
    val advertiseServiceUuids: List<String> = emptyList(),
)

data class RelayState(
    val phase: RelayPhase = RelayPhase.Idle,
    val targetAddress: String = "",
    val targetName: String? = null,
    val alias: String = "",
    val vendorAddress: String? = null,
    val targetMtu: Int = DEFAULT_ATT_MTU,
    val vendorMtu: Int = DEFAULT_ATT_MTU,
    val gatt: GattDatabase? = null,
    val mirroredServiceUuids: List<String> = emptyList(),
    val skippedServiceUuids: List<String> = emptyList(),
    val advertiseSummary: String? = null,
    val counters: RelayCounters = RelayCounters(),
    val connectAttempts: List<ConnectAttempt> = emptyList(),
    val log: List<String> = emptyList(),
    val startedAtEpochMs: Long? = null,
)

private class RelayFailure(val reason: String) : Exception(reason)

/**
 * Man-in-the-middle GATT relay: connects to the real device as a client, mirrors its attribute
 * database into a local [BluetoothGattServer], advertises as the device, and forwards every ATT
 * transaction of the vendor app to the real device and back.
 *
 * Hard limits of the approach, none of which this class can work around:
 *  * the phone's public address is broadcast, so a vendor app that pins the device MAC will not match;
 *  * an already-bonded vendor phone may reuse its cached GATT database or demand encryption;
 *  * the real device must be out of range or powered off, otherwise the vendor app connects to it.
 *
 * Every failure releases the advertiser, the server and the client link; nothing is left running.
 */
@SuppressLint("MissingPermission")
class GattRelayCoordinator(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val onEvent: (BleEvent) -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val pendingFailure = AtomicReference<String?>(null)

    @Volatile
    private var runJob: Job? = null

    @Volatile
    private var link: TargetLink? = null

    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    private var advertiser: BluetoothLeAdvertiser? = null

    @Volatile
    private var advertiseCallback: AdvertiseCallback? = null

    @Volatile
    private var vendorDevice: BluetoothDevice? = null

    @Volatile
    private var mirror: Mirror? = null

    @Volatile
    private var requests: Channel<suspend () -> Unit>? = null

    @Volatile
    private var notifications: Channel<OutgoingNotification>? = null

    @Volatile
    private var notificationAck: CompletableDeferred<Int>? = null

    @Volatile
    private var serviceAddedAck: CompletableDeferred<Int>? = null

    @Volatile
    private var restoreAdapterName: String? = null

    /** CCCD state the vendor app asked for, keyed by target characteristic instance id. */
    private val subscriptions = ConcurrentHashMap<Int, Subscription>()

    private val epochBaseMicros = System.currentTimeMillis() * 1_000L
    private val nanoBase = System.nanoTime()

    val isRunning: Boolean get() = runJob?.isActive == true

    /** Starts a relay run. Returns false when one is already in flight. */
    fun start(config: RelayConfig): Boolean {
        if (isRunning) return false
        pendingFailure.set(null)
        _state.value = RelayState(
            phase = RelayPhase.ConnectingTarget,
            targetAddress = config.targetAddress,
            alias = config.alias,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        runJob = scope.launch { runRelay(config) }
        return true
    }

    /** Requests an orderly stop; teardown runs on the relay's own coroutine. */
    fun stop() {
        val job = runJob ?: return
        if (!job.isActive) return
        _state.update { if (it.phase.isActive) it.copy(phase = RelayPhase.Stopping) else it }
        job.cancel(CancellationException("Relay stopped by the user"))
    }

    /** Releases the coordinator for good; the owning singleton calls this before building a new one. */
    fun shutdown() {
        stop()
        scope.cancel(CancellationException("Relay coordinator shut down"))
    }

    private suspend fun runRelay(config: RelayConfig) {
        try {
            val adapter = requireAdapter()
            val advertiserInstance = adapter.bluetoothLeAdvertiser
                ?: throw RelayFailure(
                    "This phone cannot advertise: BluetoothLeAdvertiser is unavailable. Bluetooth may be off, " +
                        "or the controller has no peripheral role.",
                )
            advertiser = advertiserInstance
            if (!adapter.isMultipleAdvertisementSupported) {
                log("Controller reports no multi-advertisement support; a single relay advertisement should still work.")
            }

            val targetLink = TargetLink(
                context = appContext,
                adapter = adapter,
                onNotification = ::onTargetNotification,
                onLinkLost = { status ->
                    failAsync(
                        "The target link dropped (${RelayGattQueue.statusText(status)}). The relay does not " +
                            "reconnect on its own while a vendor app is attached.",
                    )
                },
                onLog = ::log,
            )
            link = targetLink

            log("Connecting to ${config.targetAddress}")
            val connect = targetLink.connect(config.targetAddress)
            _state.update { it.copy(connectAttempts = it.connectAttempts + connect.attempt) }
            connect.error?.let { throw RelayFailure(it) }

            val negotiatedMtu = targetLink.negotiateMtu()
            log("Target connected, ATT MTU $negotiatedMtu")
            targetLink.discoverServices()?.let { throw RelayFailure(it) }

            _state.update {
                it.copy(
                    phase = RelayPhase.Cloning,
                    targetName = targetLink.targetName,
                    targetMtu = negotiatedMtu,
                    gatt = targetLink.snapshotDatabase(),
                )
            }

            val serverInstance = bluetoothManager.openGattServer(appContext, serverCallback)
                ?: throw RelayFailure("The platform refused to open a local GATT server")
            server = serverInstance

            val built = buildMirror(serverInstance, targetLink.services)
            mirror = built
            _state.update {
                it.copy(
                    mirroredServiceUuids = built.mirroredUuids,
                    skippedServiceUuids = built.skippedUuids,
                )
            }

            val effectiveName = applyAlias(adapter, config.alias)
            val plan = planAdvertisement(config, built, effectiveName)

            val requestChannel = Channel<suspend () -> Unit>(REQUEST_PIPELINE_DEPTH)
            val notificationChannel = Channel<OutgoingNotification>(NOTIFICATION_PIPELINE_DEPTH)
            requests = requestChannel
            notifications = notificationChannel

            coroutineScope {
                launch { pumpRequests(requestChannel) }
                launch { pumpNotifications(notificationChannel) }
                startAdvertising(advertiserInstance, plan)
                _state.update { it.copy(phase = RelayPhase.Advertising, advertiseSummary = plan.summary()) }
                log("Advertising: ${plan.summary()}")
                plan.notes.forEach(::log)
                awaitCancellation()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RelayFailure) {
            pendingFailure.compareAndSet(null, failure.reason)
        } catch (unexpected: Throwable) {
            pendingFailure.compareAndSet(null, describe(unexpected))
        } finally {
            teardown()
            val reason = pendingFailure.getAndSet(null)
            _state.update {
                it.copy(
                    phase = if (reason != null) RelayPhase.Failed(reason) else RelayPhase.Idle,
                    vendorAddress = null,
                )
            }
            log(if (reason != null) "Relay failed: $reason" else "Relay stopped; advertiser, server and target link released")
        }
    }

    private fun requireAdapter(): BluetoothAdapter {
        for (permission in REQUIRED_PERMISSIONS) {
            if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                throw RelayFailure("${permission.substringAfterLast('.')} has not been granted")
            }
        }
        val adapter = bluetoothManager.adapter ?: throw RelayFailure("This device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw RelayFailure("Bluetooth is switched off")
        return adapter
    }

    // ---------------------------------------------------------------- mirroring

    private class Mirror(
        val mirroredUuids: List<String>,
        val skippedUuids: List<String>,
        val advertisableUuids: List<UUID>,
        private val charToTarget: IdentityHashMap<BluetoothGattCharacteristic, BluetoothGattCharacteristic>,
        private val descriptorToTarget: IdentityHashMap<BluetoothGattDescriptor, BluetoothGattDescriptor>,
        private val targetToLocalChar: IdentityHashMap<BluetoothGattCharacteristic, BluetoothGattCharacteristic>,
    ) {
        // Built once, published through a volatile field, never mutated afterwards, so concurrent
        // lookups from the server's binder threads are safe.
        fun targetOf(local: BluetoothGattCharacteristic): BluetoothGattCharacteristic? = charToTarget[local]
        fun targetOf(local: BluetoothGattDescriptor): BluetoothGattDescriptor? = descriptorToTarget[local]
        fun localOf(target: BluetoothGattCharacteristic): BluetoothGattCharacteristic? = targetToLocalChar[target]
    }

    private suspend fun buildMirror(
        serverInstance: BluetoothGattServer,
        targetServices: List<BluetoothGattService>,
    ): Mirror {
        val charToTarget = IdentityHashMap<BluetoothGattCharacteristic, BluetoothGattCharacteristic>()
        val descriptorToTarget = IdentityHashMap<BluetoothGattDescriptor, BluetoothGattDescriptor>()
        val targetToLocal = IdentityHashMap<BluetoothGattCharacteristic, BluetoothGattCharacteristic>()
        val skipped = ArrayList<String>(2)
        val mirroredUuids = ArrayList<String>(targetServices.size)
        val advertisable = ArrayList<UUID>(targetServices.size)
        val clones = LinkedHashMap<String, BluetoothGattService>()
        var derivedPermissions = 0

        val mirrorable = targetServices.filter { service ->
            val owned = service.uuid == GAP_SERVICE_UUID || service.uuid == GATT_SERVICE_UUID
            if (owned) skipped += service.uuid.toString().uppercase()
            !owned
        }
        if (skipped.isNotEmpty()) {
            log("Not mirrored: ${skipped.joinToString()} - Generic Access/Generic Attribute are published by the local stack itself.")
        }
        if (mirrorable.isEmpty()) throw RelayFailure("The target only exposes Generic Access/Generic Attribute; there is nothing to relay")

        // Secondary services first: a primary service can only include one that already exists.
        val ordered = mirrorable.sortedBy { if (it.type == BluetoothGattService.SERVICE_TYPE_SECONDARY) 0 else 1 }
        for (service in ordered) {
            val clone = BluetoothGattService(service.uuid, service.type)
            for (characteristic in service.characteristics.orEmpty()) {
                val permissions = clonedCharacteristicPermissions(characteristic)
                if (characteristic.permissions == 0) derivedPermissions++
                val localCharacteristic = BluetoothGattCharacteristic(
                    characteristic.uuid,
                    characteristic.properties,
                    permissions,
                )
                for (descriptor in characteristic.descriptors.orEmpty()) {
                    if (descriptor.permissions == 0) derivedPermissions++
                    val localDescriptor = BluetoothGattDescriptor(
                        descriptor.uuid,
                        clonedDescriptorPermissions(descriptor),
                    )
                    if (!localCharacteristic.addDescriptor(localDescriptor)) {
                        throw RelayFailure("Could not mirror descriptor ${descriptor.uuid} of ${characteristic.uuid}")
                    }
                    descriptorToTarget[localDescriptor] = descriptor
                }
                if (!clone.addCharacteristic(localCharacteristic)) {
                    throw RelayFailure("Could not mirror characteristic ${characteristic.uuid} of ${service.uuid}")
                }
                charToTarget[localCharacteristic] = characteristic
                targetToLocal[characteristic] = localCharacteristic
            }
            clones[serviceKey(service.uuid, service.instanceId)] = clone
            mirroredUuids += service.uuid.toString().uppercase()
            if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) advertisable += service.uuid
        }

        for (service in mirrorable) {
            val clone = clones.getValue(serviceKey(service.uuid, service.instanceId))
            for (included in service.includedServices.orEmpty()) {
                val localIncluded = clones[serviceKey(included.uuid, included.instanceId)]
                if (localIncluded == null) {
                    log("Include declaration ${included.uuid} inside ${service.uuid} was dropped; that service is not mirrored.")
                } else if (!clone.addService(localIncluded)) {
                    log("The local stack refused the include declaration ${included.uuid} inside ${service.uuid}.")
                }
            }
        }

        if (derivedPermissions > 0) {
            log(
                "Derived ATT permissions for $derivedPermissions attribute(s) from their properties: a remote " +
                    "GATT database never reports permissions, they are not discoverable over the air.",
            )
        }

        for (clone in clones.values) {
            addServiceAndWait(serverInstance, clone)
        }
        log("Mirrored ${clones.size} service(s), ${charToTarget.size} characteristic(s), ${descriptorToTarget.size} descriptor(s)")

        return Mirror(
            mirroredUuids = mirroredUuids,
            skippedUuids = skipped,
            advertisableUuids = advertisable,
            charToTarget = charToTarget,
            descriptorToTarget = descriptorToTarget,
            targetToLocalChar = targetToLocal,
        )
    }

    private suspend fun addServiceAndWait(serverInstance: BluetoothGattServer, service: BluetoothGattService) {
        val ack = CompletableDeferred<Int>()
        serviceAddedAck = ack
        try {
            if (!serverInstance.addService(service)) {
                throw RelayFailure("The local GATT server rejected service ${service.uuid}")
            }
            val status = withTimeoutOrNull(SERVICE_ADD_TIMEOUT_MS) { ack.await() }
                ?: throw RelayFailure("The local GATT server never confirmed service ${service.uuid} (${SERVICE_ADD_TIMEOUT_MS}ms)")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw RelayFailure("Adding service ${service.uuid} failed: ${RelayGattQueue.statusText(status)}")
            }
        } finally {
            serviceAddedAck = null
        }
    }

    private fun clonedCharacteristicPermissions(source: BluetoothGattCharacteristic): Int {
        val reported = source.permissions
        if (reported != 0) return reported
        val properties = source.properties
        var derived = 0
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            derived = derived or BluetoothGattCharacteristic.PERMISSION_READ
        }
        val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        if (properties and writable != 0) {
            derived = derived or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) {
            derived = derived or BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED
        }
        return derived
    }

    private fun clonedDescriptorPermissions(source: BluetoothGattDescriptor): Int {
        val reported = source.permissions
        if (reported != 0) return reported
        return if (source.uuid == CCCD_UUID || source.uuid == SCCD_UUID) {
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        } else {
            BluetoothGattDescriptor.PERMISSION_READ
        }
    }

    // ---------------------------------------------------------------- advertising

    /** Renaming the adapter is the only way to broadcast a different local name; it is restored on stop. */
    private suspend fun applyAlias(adapter: BluetoothAdapter, alias: String): String? {
        val current = runCatching { adapter.name }.getOrNull()
        if (alias.isBlank() || alias == current) return current
        if (!runCatching { adapter.setName(alias) }.getOrDefault(false)) {
            log("The Bluetooth adapter refused the alias \"$alias\"; advertising the adapter name \"$current\" instead.")
            return current
        }
        restoreAdapterName = current
        repeat(ALIAS_POLL_ATTEMPTS) {
            delay(ALIAS_POLL_INTERVAL_MS)
            val applied = runCatching { adapter.name }.getOrNull()
            if (applied == alias) {
                log("Renamed this phone's Bluetooth adapter to \"$alias\" (restored on stop).")
                return alias
            }
        }
        val settled = runCatching { adapter.name }.getOrNull()
        log("The adapter name is still \"$settled\" after ${ALIAS_POLL_ATTEMPTS * ALIAS_POLL_INTERVAL_MS}ms; planning the payload with that name.")
        return settled
    }

    private fun planAdvertisement(config: RelayConfig, built: Mirror, effectiveName: String?): AdvertisePlan {
        val requested = config.advertiseServiceUuids
            .mapNotNull { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        val selected = if (requested.isEmpty()) {
            built.advertisableUuids
        } else {
            val unknown = requested.filterNot { it in built.advertisableUuids }
            if (unknown.isNotEmpty()) {
                log("Ignoring advertise request for non-mirrored service(s): ${unknown.joinToString()}")
            }
            requested.filter { it in built.advertisableUuids }
        }
        return when (val result = AdvertisePlanner.plan(effectiveName, selected)) {
            is AdvertisePlanResult.Planned -> result.plan
            is AdvertisePlanResult.Rejected -> throw RelayFailure(result.reason)
        }
    }

    private suspend fun startAdvertising(advertiserInstance: BluetoothLeAdvertiser, plan: AdvertisePlan) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertisement = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertisement)
            .setIncludeTxPowerLevel(false)
            .apply { plan.advertisementServiceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
            .build()
        val scanResponse = if (!plan.usesScanResponse) {
            null
        } else {
            AdvertiseData.Builder()
                .setIncludeDeviceName(plan.includeNameInScanResponse)
                .setIncludeTxPowerLevel(false)
                .apply { plan.scanResponseServiceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
                .build()
        }

        val ack = CompletableDeferred<Int>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                ack.complete(ADVERTISE_STARTED)
            }

            override fun onStartFailure(errorCode: Int) {
                ack.complete(errorCode)
            }
        }
        advertiseCallback = callback
        try {
            if (scanResponse == null) {
                advertiserInstance.startAdvertising(settings, advertisement, callback)
            } else {
                advertiserInstance.startAdvertising(settings, advertisement, scanResponse, callback)
            }
        } catch (security: SecurityException) {
            advertiseCallback = null
            throw RelayFailure("BLUETOOTH_ADVERTISE was refused: ${security.message}")
        }
        val code = withTimeoutOrNull(ADVERTISE_START_TIMEOUT_MS) { ack.await() }
        if (code == null) {
            throw RelayFailure("The advertiser did not report back within ${ADVERTISE_START_TIMEOUT_MS}ms")
        }
        if (code != ADVERTISE_STARTED) {
            throw RelayFailure("Advertising could not start: ${advertiseErrorText(code)}")
        }
    }

    private fun advertiseErrorText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "the payload exceeds 31 bytes (ADVERTISE_FAILED_DATA_TOO_LARGE)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "no advertising instance is free (ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "this advertiser is already running"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal stack error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "the controller does not support advertising"
        else -> "advertise error code $code"
    }

    // ---------------------------------------------------------------- server side

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            serviceAddedAck?.complete(status)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onVendorConnected(device)
                BluetoothProfile.STATE_DISCONNECTED -> onVendorDisconnected(device, status)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            _state.update { it.copy(vendorMtu = mtu) }
            log("Vendor app negotiated ATT MTU $mtu (target link runs at ${_state.value.targetMtu})")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationAck?.complete(status)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val responder = Responder(device, requestId, true, "read ${characteristic.uuid}")
            submit(responder) { relayCharacteristicRead(responder, offset, characteristic) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val responder = Responder(device, requestId, responseNeeded, "write ${characteristic.uuid}")
            if (preparedWrite) {
                refusePreparedWrite(responder, characteristic.uuid, value)
                return
            }
            submit(responder) {
                relayCharacteristicWrite(responder, characteristic, responseNeeded, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val responder = Responder(device, requestId, true, "read descriptor ${descriptor.uuid}")
            submit(responder) { relayDescriptorRead(responder, offset, descriptor) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val responder = Responder(device, requestId, responseNeeded, "write descriptor ${descriptor.uuid}")
            if (preparedWrite) {
                refusePreparedWrite(responder, descriptor.uuid, value)
                return
            }
            submit(responder) {
                relayDescriptorWrite(responder, descriptor, responseNeeded, offset, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            // Prepared writes are refused above, so an execute can only be a stray request.
            val responder = Responder(device, requestId, true, "execute write")
            responder.send(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            bump(errors = 1)
            log("Refused an execute-write request; the relay does not queue reliable writes.")
        }
    }

    private fun onVendorConnected(device: BluetoothDevice) {
        val current = vendorDevice
        if (current != null && current.address != device.address) {
            log("Refused a second vendor connection from ${device.address}; ${current.address} is already relayed.")
            bump(errors = 1)
            runCatching { server?.cancelConnection(device) }
            return
        }
        vendorDevice = device
        _state.update {
            it.copy(
                phase = RelayPhase.VendorConnected(device.address),
                vendorAddress = device.address,
                vendorMtu = DEFAULT_ATT_MTU,
            )
        }
        log("Vendor app connected from ${device.address}")
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = AttOperation.OTHER,
            serviceUuid = null,
            characteristicUuid = null,
            payload = null,
            status = null,
            note = "vendor device ${device.address} connected to the relay",
        )
    }

    private fun onVendorDisconnected(device: BluetoothDevice, status: Int) {
        if (vendorDevice?.address != device.address) return
        vendorDevice = null
        _state.update {
            val phase = if (it.phase is RelayPhase.VendorConnected) RelayPhase.Advertising else it.phase
            it.copy(phase = phase, vendorAddress = null, vendorMtu = DEFAULT_ATT_MTU)
        }
        log("Vendor app ${device.address} disconnected (${RelayGattQueue.statusText(status)}); still advertising")
        emit(
            direction = EventDirection.DEVICE_TO_PHONE,
            operation = AttOperation.OTHER,
            serviceUuid = null,
            characteristicUuid = null,
            payload = null,
            status = status,
            note = "vendor device ${device.address} disconnected",
        )
        unsubscribeAll()
    }

    /** Mirrors a real disconnect: an unbonded client's subscriptions are cleared on the device too. */
    private fun unsubscribeAll() {
        val open = subscriptions.values.toList()
        subscriptions.clear()
        if (open.isEmpty()) return
        submit(null) {
            val targetLink = link ?: return@submit
            for (subscription in open) {
                targetLink.setNotification(subscription.characteristic, false)
                val outcome = targetLink.writeDescriptor(subscription.descriptor, CCCD_DISABLE)
                if (!outcome.ok) {
                    log("Could not clear the CCCD of ${subscription.characteristic.uuid}: ${RelayGattQueue.statusText(outcome.status)}")
                }
            }
            log("Cleared ${open.size} subscription(s) on the target after the vendor app left")
        }
    }

    private fun refusePreparedWrite(responder: Responder, uuid: UUID, value: ByteArray) {
        bump(errors = 1)
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = AttOperation.ERROR,
            serviceUuid = null,
            characteristicUuid = uuid.toString().uppercase(),
            payload = value,
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
            note = "prepared write refused; the relay forwards single writes only",
        )
        responder.send(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
    }

    // ---------------------------------------------------------------- relay paths

    private suspend fun relayCharacteristicRead(
        responder: Responder,
        offset: Int,
        local: BluetoothGattCharacteristic,
    ) {
        val target = mirror?.targetOf(local)
        if (target == null) {
            reportUnmapped(responder, local.uuid, "characteristic")
            return
        }
        val serviceUuid = target.service?.uuid?.toString()?.uppercase()
        val characteristicUuid = target.uuid.toString().uppercase()
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = AttOperation.READ_REQUEST,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = null,
            status = null,
            note = if (offset > 0) "read blob request, offset $offset" else "",
        )
        val targetLink = link
        if (targetLink == null) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, RelayGattQueue.STATUS_LINK_LOST, offset)
            return
        }
        val outcome = targetLink.readCharacteristic(target)
        if (!outcome.ok) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, outcome.status, offset)
            return
        }
        val value = outcome.value ?: EMPTY_PAYLOAD
        if (offset > value.size) {
            bump(errors = 1)
            emit(
                direction = EventDirection.DEVICE_TO_PHONE,
                operation = AttOperation.ERROR,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                payload = null,
                status = BluetoothGatt.GATT_INVALID_OFFSET,
                note = "offset $offset is past the ${value.size}-byte value",
            )
            responder.send(BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        val slice = if (offset == 0) value else value.copyOfRange(offset, value.size)
        bump(reads = 1)
        emit(
            direction = EventDirection.DEVICE_TO_PHONE,
            operation = AttOperation.READ_RESPONSE,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = slice,
            status = BluetoothGatt.GATT_SUCCESS,
            note = "",
        )
        responder.send(BluetoothGatt.GATT_SUCCESS, offset, slice)
    }

    private suspend fun relayCharacteristicWrite(
        responder: Responder,
        local: BluetoothGattCharacteristic,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        val target = mirror?.targetOf(local)
        if (target == null) {
            reportUnmapped(responder, local.uuid, "characteristic")
            return
        }
        val serviceUuid = target.service?.uuid?.toString()?.uppercase()
        val characteristicUuid = target.uuid.toString().uppercase()
        val operation = if (responseNeeded) AttOperation.WRITE_REQUEST else AttOperation.WRITE_COMMAND
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = operation,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = value,
            status = null,
            note = if (responseNeeded) "" else "write without response",
        )
        if (offset != 0) {
            rejectWrite(responder, serviceUuid, characteristicUuid, offset, BluetoothGatt.GATT_INVALID_OFFSET, "single writes carry no offset, got $offset")
            return
        }
        val limit = _state.value.targetMtu - ATT_WRITE_HEADER_BYTES
        if (value.size > limit) {
            rejectWrite(
                responder,
                serviceUuid,
                characteristicUuid,
                offset,
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                "${value.size} bytes exceed the target's ${limit}-byte single-write limit (MTU ${_state.value.targetMtu} - $ATT_WRITE_HEADER_BYTES)",
            )
            return
        }
        val writeType = if (responseNeeded) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val targetLink = link
        if (targetLink == null) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, RelayGattQueue.STATUS_LINK_LOST, offset)
            return
        }
        val outcome = targetLink.writeCharacteristic(target, value, writeType)
        if (!outcome.ok) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, outcome.status, offset)
            return
        }
        bump(writes = 1)
        if (responseNeeded) {
            emit(
                direction = EventDirection.DEVICE_TO_PHONE,
                operation = AttOperation.WRITE_RESPONSE,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                payload = null,
                status = BluetoothGatt.GATT_SUCCESS,
                note = "",
            )
        }
        responder.send(BluetoothGatt.GATT_SUCCESS, offset, null)
    }

    private suspend fun relayDescriptorRead(
        responder: Responder,
        offset: Int,
        local: BluetoothGattDescriptor,
    ) {
        val target = mirror?.targetOf(local)
        if (target == null) {
            reportUnmapped(responder, local.uuid, "descriptor")
            return
        }
        val owner = target.characteristic
        val serviceUuid = owner?.service?.uuid?.toString()?.uppercase()
        val characteristicUuid = owner?.uuid?.toString()?.uppercase()
        val descriptorNote = "descriptor ${target.uuid.toString().uppercase().shortUuid()}"
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = AttOperation.READ_REQUEST,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = null,
            status = null,
            note = descriptorNote,
        )
        val targetLink = link
        if (targetLink == null) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, RelayGattQueue.STATUS_LINK_LOST, offset)
            return
        }
        val outcome = targetLink.readDescriptor(target)
        if (!outcome.ok) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, outcome.status, offset)
            return
        }
        val value = outcome.value ?: EMPTY_PAYLOAD
        if (offset > value.size) {
            bump(errors = 1)
            responder.send(BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        val slice = if (offset == 0) value else value.copyOfRange(offset, value.size)
        bump(reads = 1)
        emit(
            direction = EventDirection.DEVICE_TO_PHONE,
            operation = AttOperation.READ_RESPONSE,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = slice,
            status = BluetoothGatt.GATT_SUCCESS,
            note = descriptorNote,
        )
        responder.send(BluetoothGatt.GATT_SUCCESS, offset, slice)
    }

    private suspend fun relayDescriptorWrite(
        responder: Responder,
        local: BluetoothGattDescriptor,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        val target = mirror?.targetOf(local)
        if (target == null) {
            reportUnmapped(responder, local.uuid, "descriptor")
            return
        }
        val owner = target.characteristic
        val serviceUuid = owner?.service?.uuid?.toString()?.uppercase()
        val characteristicUuid = owner?.uuid?.toString()?.uppercase()
        val isCccd = target.uuid == CCCD_UUID
        val flags = if (value.isNotEmpty()) value[0].toInt() and 0xFF else 0
        val wantsNotify = isCccd && (flags and 0x01) != 0
        val wantsIndicate = isCccd && (flags and 0x02) != 0
        val note = buildString {
            append("descriptor ")
            append(target.uuid.toString().uppercase().shortUuid())
            if (isCccd) {
                append(
                    when {
                        wantsIndicate -> " subscribe (indications)"
                        wantsNotify -> " subscribe (notifications)"
                        else -> " unsubscribe"
                    },
                )
            }
        }
        emit(
            direction = EventDirection.PHONE_TO_DEVICE,
            operation = if (responseNeeded) AttOperation.WRITE_REQUEST else AttOperation.WRITE_COMMAND,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = value,
            status = null,
            note = note,
        )
        if (offset != 0) {
            rejectWrite(responder, serviceUuid, characteristicUuid, offset, BluetoothGatt.GATT_INVALID_OFFSET, "single writes carry no offset, got $offset")
            return
        }
        val targetLink = link
        if (targetLink == null) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, RelayGattQueue.STATUS_LINK_LOST, offset)
            return
        }
        val enable = wantsNotify || wantsIndicate
        if (isCccd && owner != null && enable && !targetLink.setNotification(owner, true)) {
            log("The local stack refused to route notifications for ${owner.uuid}; the subscription will stay silent.")
        }
        val outcome = targetLink.writeDescriptor(target, value)
        if (isCccd && owner != null) {
            if (outcome.ok && enable) {
                subscriptions[owner.instanceId] = Subscription(owner, target, wantsIndicate)
            } else {
                // Either the target refused the subscription or the vendor unsubscribed: in both
                // cases the local notification route has to go away again.
                subscriptions.remove(owner.instanceId)
                targetLink.setNotification(owner, false)
            }
        }
        if (!outcome.ok) {
            reportTargetFailure(responder, serviceUuid, characteristicUuid, outcome.status, offset)
            return
        }
        bump(writes = 1)
        if (responseNeeded) {
            emit(
                direction = EventDirection.DEVICE_TO_PHONE,
                operation = AttOperation.WRITE_RESPONSE,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                payload = null,
                status = BluetoothGatt.GATT_SUCCESS,
                note = note,
            )
        }
        responder.send(BluetoothGatt.GATT_SUCCESS, offset, null)
    }

    private fun onTargetNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val local = mirror?.localOf(characteristic)
        if (local == null) {
            bump(errors = 1)
            log("Dropped a notification from the unmapped characteristic ${characteristic.uuid}")
            return
        }
        val subscription = subscriptions[characteristic.instanceId]
        val queued = notifications?.trySend(
            OutgoingNotification(
                local = local,
                value = value,
                confirm = subscription?.indicate == true,
                serviceUuid = characteristic.service?.uuid?.toString()?.uppercase(),
                characteristicUuid = characteristic.uuid.toString().uppercase(),
            ),
        )
        if (queued == null || queued.isFailure) {
            bump(errors = 1)
            emit(
                direction = EventDirection.DEVICE_TO_PHONE,
                operation = AttOperation.ERROR,
                serviceUuid = characteristic.service?.uuid?.toString()?.uppercase(),
                characteristicUuid = characteristic.uuid.toString().uppercase(),
                payload = value,
                status = null,
                note = "dropped: the relay has $NOTIFICATION_PIPELINE_DEPTH unsent notifications queued",
            )
        }
    }

    private suspend fun pumpRequests(channel: Channel<suspend () -> Unit>) {
        for (task in channel) {
            try {
                task()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                bump(errors = 1)
                log("An ATT request failed: ${describe(failure)}")
            }
        }
    }

    /**
     * One notification in flight at a time: the stack only accepts the next one after
     * `onNotificationSent`, and an indication additionally waits for the peer's confirmation.
     */
    private suspend fun pumpNotifications(channel: Channel<OutgoingNotification>) {
        for (pending in channel) {
            val device = vendorDevice
            val serverInstance = server
            if (device == null || serverInstance == null) {
                bump(errors = 1)
                emit(
                    direction = EventDirection.DEVICE_TO_PHONE,
                    operation = AttOperation.ERROR,
                    serviceUuid = pending.serviceUuid,
                    characteristicUuid = pending.characteristicUuid,
                    payload = pending.value,
                    status = null,
                    note = "dropped: no vendor device is connected",
                )
                continue
            }
            val ack = CompletableDeferred<Int>()
            notificationAck = ack
            val issued = try {
                notifyVendor(serverInstance, device, pending)
            } catch (security: SecurityException) {
                log("Notification refused by the platform: ${security.message}")
                BluetoothGatt.GATT_FAILURE
            }
            if (issued != PLATFORM_STATUS_SUCCESS) {
                notificationAck = null
                bump(errors = 1)
                emit(
                    direction = EventDirection.DEVICE_TO_PHONE,
                    operation = AttOperation.ERROR,
                    serviceUuid = pending.serviceUuid,
                    characteristicUuid = pending.characteristicUuid,
                    payload = pending.value,
                    status = issued,
                    note = "the local stack rejected the notification",
                )
                continue
            }
            val status = withTimeoutOrNull(NOTIFICATION_ACK_TIMEOUT_MS) { ack.await() }
            notificationAck = null
            when {
                status == null -> {
                    bump(errors = 1)
                    emit(
                        direction = EventDirection.DEVICE_TO_PHONE,
                        operation = AttOperation.ERROR,
                        serviceUuid = pending.serviceUuid,
                        characteristicUuid = pending.characteristicUuid,
                        payload = pending.value,
                        status = null,
                        note = "no send confirmation within ${NOTIFICATION_ACK_TIMEOUT_MS}ms",
                    )
                }
                status != BluetoothGatt.GATT_SUCCESS -> {
                    bump(errors = 1)
                    emit(
                        direction = EventDirection.DEVICE_TO_PHONE,
                        operation = AttOperation.ERROR,
                        serviceUuid = pending.serviceUuid,
                        characteristicUuid = pending.characteristicUuid,
                        payload = pending.value,
                        status = status,
                        note = "the vendor app did not accept the ${if (pending.confirm) "indication" else "notification"}",
                    )
                }
                else -> {
                    bump(notifies = 1)
                    emit(
                        direction = EventDirection.DEVICE_TO_PHONE,
                        operation = if (pending.confirm) AttOperation.INDICATION else AttOperation.NOTIFICATION,
                        serviceUuid = pending.serviceUuid,
                        characteristicUuid = pending.characteristicUuid,
                        payload = pending.value,
                        status = BluetoothGatt.GATT_SUCCESS,
                        note = "",
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyVendor(
        serverInstance: BluetoothGattServer,
        device: BluetoothDevice,
        pending: OutgoingNotification,
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        serverInstance.notifyCharacteristicChanged(device, pending.local, pending.confirm, pending.value)
    } else {
        pending.local.value = pending.value
        if (serverInstance.notifyCharacteristicChanged(device, pending.local, pending.confirm)) {
            PLATFORM_STATUS_SUCCESS
        } else {
            BluetoothGatt.GATT_FAILURE
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun submit(responder: Responder?, block: suspend () -> Unit) {
        val task: suspend () -> Unit = {
            try {
                block()
            } finally {
                responder?.answerIfUnanswered()
            }
        }
        val queued = requests?.trySend(task)
        if (queued == null || queued.isFailure) {
            bump(errors = 1)
            log("Dropped an ATT request: $REQUEST_PIPELINE_DEPTH requests are already queued")
            responder?.send(BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    private fun reportUnmapped(responder: Responder, uuid: UUID, kind: String) {
        bump(errors = 1)
        log("No target $kind is mapped to the local clone $uuid; answered GATT_FAILURE")
        responder.send(BluetoothGatt.GATT_FAILURE, 0, null)
    }

    private fun reportTargetFailure(
        responder: Responder,
        serviceUuid: String?,
        characteristicUuid: String?,
        status: Int,
        offset: Int,
    ) {
        bump(errors = 1)
        emit(
            direction = EventDirection.DEVICE_TO_PHONE,
            operation = AttOperation.ERROR,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = null,
            status = status,
            note = "target: ${RelayGattQueue.statusText(status)}",
        )
        responder.send(vendorStatusFor(status), offset, null)
    }

    private fun rejectWrite(
        responder: Responder,
        serviceUuid: String?,
        characteristicUuid: String?,
        offset: Int,
        status: Int,
        reason: String,
    ) {
        bump(errors = 1)
        emit(
            direction = EventDirection.DEVICE_TO_PHONE,
            operation = AttOperation.ERROR,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            payload = null,
            status = status,
            note = reason,
        )
        responder.send(status, offset, null)
    }

    /**
     * A genuine ATT error from the target (0x01-0xFF) is forwarded verbatim so the vendor app sees
     * what the real device said; local sentinels (timeout, link lost, refused) become GATT_FAILURE.
     */
    private fun vendorStatusFor(targetStatus: Int): Int = when {
        targetStatus == BluetoothGatt.GATT_SUCCESS -> BluetoothGatt.GATT_SUCCESS
        targetStatus in 0x01..0xFF -> targetStatus
        else -> BluetoothGatt.GATT_FAILURE
    }

    private fun failAsync(reason: String) {
        pendingFailure.compareAndSet(null, reason)
        runJob?.cancel(CancellationException(reason))
    }

    private fun teardown() {
        requests?.cancel()
        requests = null
        notifications?.cancel()
        notifications = null
        notificationAck = null
        serviceAddedAck = null

        val advertiserInstance = advertiser
        val callback = advertiseCallback
        if (advertiserInstance != null && callback != null) {
            runCatching { advertiserInstance.stopAdvertising(callback) }
        }
        advertiseCallback = null
        advertiser = null

        val serverInstance = server
        server = null
        if (serverInstance != null) {
            vendorDevice?.let { device -> runCatching { serverInstance.cancelConnection(device) } }
            runCatching { serverInstance.clearServices() }
            runCatching { serverInstance.close() }
        }
        vendorDevice = null
        mirror = null
        subscriptions.clear()

        link?.close()
        link = null

        val previousName = restoreAdapterName
        restoreAdapterName = null
        if (previousName != null) {
            val adapter = bluetoothManager.adapter
            if (adapter != null && runCatching { adapter.setName(previousName) }.getOrDefault(false)) {
                log("Restored the Bluetooth adapter name to \"$previousName\"")
            } else {
                log("Could not restore the Bluetooth adapter name to \"$previousName\"; change it in Settings.")
            }
        }
    }

    private fun nowMicros(): Long = epochBaseMicros + (System.nanoTime() - nanoBase) / 1_000L

    private fun emit(
        direction: EventDirection,
        operation: AttOperation,
        serviceUuid: String?,
        characteristicUuid: String?,
        payload: ByteArray?,
        status: Int?,
        note: String,
    ) {
        onEvent(
            BleEvent(
                timestampEpochMicros = nowMicros(),
                direction = direction,
                source = EventSource.MITM_RELAY,
                operation = operation,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                attributeHandle = null,
                payloadHex = payload?.toHexFast() ?: "",
                status = status,
                note = note,
            ),
        )
    }

    private fun bump(reads: Int = 0, writes: Int = 0, notifies: Int = 0, errors: Int = 0) {
        _state.update { current ->
            val counters = current.counters
            current.copy(
                counters = RelayCounters(
                    reads = counters.reads + reads,
                    writes = counters.writes + writes,
                    notifies = counters.notifies + notifies,
                    errors = counters.errors + errors,
                ),
            )
        }
    }

    private fun log(line: String) {
        _state.update { current ->
            val entries = current.log
            val trimmed = if (entries.size < LOG_LIMIT) entries else entries.subList(entries.size - LOG_LIMIT + 1, entries.size)
            current.copy(log = trimmed + line)
        }
    }

    private fun describe(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

    /** Guarantees exactly one ATT response per request, from any code path including cancellation. */
    private inner class Responder(
        private val device: BluetoothDevice,
        private val requestId: Int,
        private val responseNeeded: Boolean,
        private val label: String,
    ) {
        private val answered = AtomicBoolean(false)

        fun send(status: Int, offset: Int, value: ByteArray?) {
            if (!responseNeeded) return
            if (!answered.compareAndSet(false, true)) {
                bump(errors = 1)
                log("Suppressed a second ATT response for $label")
                return
            }
            val serverInstance = server
            val delivered = serverInstance != null &&
                runCatching { serverInstance.sendResponse(device, requestId, status, offset, value) }
                    .getOrDefault(false)
            if (!delivered) {
                bump(errors = 1)
                log("The platform rejected the ATT response for $label")
            }
        }

        fun answerIfUnanswered() {
            if (!responseNeeded || answered.get()) return
            log("$label produced no response; answering GATT_FAILURE so the vendor app is not left waiting")
            send(BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    private class OutgoingNotification(
        val local: BluetoothGattCharacteristic,
        val value: ByteArray,
        val confirm: Boolean,
        val serviceUuid: String?,
        val characteristicUuid: String?,
    )

    private class Subscription(
        val characteristic: BluetoothGattCharacteristic,
        val descriptor: BluetoothGattDescriptor,
        val indicate: Boolean,
    )

    private companion object {
        const val REQUEST_PIPELINE_DEPTH = 256
        const val NOTIFICATION_PIPELINE_DEPTH = 512
        const val SERVICE_ADD_TIMEOUT_MS = 5_000L
        const val ADVERTISE_START_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_ACK_TIMEOUT_MS = 3_000L
        const val ALIAS_POLL_ATTEMPTS = 10
        const val ALIAS_POLL_INTERVAL_MS = 100L
        const val ATT_WRITE_HEADER_BYTES = 3
        const val LOG_LIMIT = 120
        const val ADVERTISE_STARTED = -1

        val EMPTY_PAYLOAD = ByteArray(0)
        val CCCD_DISABLE = byteArrayOf(0x00, 0x00)
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        fun serviceKey(uuid: UUID, instanceId: Int): String = "$uuid/$instanceId"
    }
}
