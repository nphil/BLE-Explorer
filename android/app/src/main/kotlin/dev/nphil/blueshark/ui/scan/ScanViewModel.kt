package dev.nphil.blueshark.ui.scan

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.CharacteristicRef
import dev.nphil.blueshark.ble.ConnectionState
import dev.nphil.blueshark.ble.GattClient
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.displayName
import dev.nphil.blueshark.model.AdvertisementSample
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.ConnectionFacts
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.model.toHex
import dev.nphil.blueshark.service.BleForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val EVENT_HISTORY = 2_000
private const val AUTO_EXPAND_LIMIT = 4

enum class ConnectionPhase { IDLE, CONNECTING, CONNECTED, FAILED }

data class ConnectionUiState(
    val address: String? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val failureReason: String? = null,
    val database: GattDatabase? = null,
    val facts: ConnectionFacts = ConnectionFacts(),
    val busyLabel: String? = null,
    val expandedServices: Set<Int> = emptySet(),
    val subscriptions: Set<CharacteristicRef> = emptySet(),
    /** Last value seen per characteristic instance id, whether read or notified. */
    val values: Map<Int, String> = emptyMap(),
    val events: List<BleEvent> = emptyList(),
) {
    val busy: Boolean get() = busyLabel != null
}

data class WriteDialogState(
    val ref: CharacteristicRef,
    val label: String,
    val hex: String = "",
    val withResponse: Boolean = true,
    val supportsWithResponse: Boolean = true,
    val supportsWithoutResponse: Boolean = true,
) {
    /** Live validation: spaces are cosmetic, everything else must be complete hex byte pairs. */
    val compact: String get() = hex.filterNot(Char::isWhitespace)
    val invalidCharacter: Boolean get() = compact.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    val oddLength: Boolean get() = compact.length % 2 != 0
    val valid: Boolean get() = compact.isNotEmpty() && !invalidCharacter && !oddLength
    val byteCount: Int get() = compact.length / 2

    val error: String?
        get() = when {
            invalidCharacter -> "Only 0-9 and A-F are allowed."
            oddLength -> "A byte needs two hex digits."
            else -> null
        }
}

data class SessionOption(val id: String, val name: String, val updatedAtEpochMs: Long)

data class SessionPickerState(
    val sessions: List<SessionOption> = emptyList(),
    val selectedId: String? = null,
    val newName: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
)

data class ScanUiState(
    val scanning: Boolean = false,
    val continuous: Boolean = false,
    val query: String = "",
    val connectableOnly: Boolean = false,
    val hideUnnamed: Boolean = false,
    val visible: List<ScannedDevice> = emptyList(),
    /** Every aggregated device, so the detail pane keeps working when a filter hides its row. */
    val allDevices: Map<String, ScannedDevice> = emptyMap(),
    val totalSeen: Int = 0,
    val selectedAddress: String? = null,
    val connection: ConnectionUiState = ConnectionUiState(),
    val writeDialog: WriteDialogState? = null,
    val sessionPicker: SessionPickerState? = null,
)

/** Emitted once the evidence has been persisted, so the screen can hand over to the session tab. */
data class SavedToSession(val sessionId: String)

class ScanViewModel(private val container: AppContainer) : ViewModel() {

    // Process-scoped: the Signal screen picks its target from this very aggregate, and the link
    // it measures is this very client's.
    private val scanner = container.scanner
    private val gatt = container.gattClient

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _saved = MutableSharedFlow<SavedToSession>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<SavedToSession> = _saved.asSharedFlow()

    /**
     * [GattClient.linkSampleCount] as of the last save, per session id.
     *
     * The client accumulates link-setup timings for as long as the screen lives and caps the list
     * it publishes, so "what is new since the last save" is a difference of arrival counts, never
     * an offset into that list. Read from the store's writer thread, hence concurrent.
     */
    private val mergedReconnectSamples = ConcurrentHashMap<String, Int>()

    /** Whether this screen currently holds [BleForegroundService] in GATT mode. */
    private var foregroundHeld = false

    init {
        viewModelScope.launch {
            scanner.devices.collect { devices -> _state.update { it.withDevices(devices) } }
        }
        viewModelScope.launch {
            scanner.status.collect { status ->
                _state.update { it.copy(scanning = status.scanning, continuous = status.continuous) }
                status.error?.let { error ->
                    _messages.tryEmit(error)
                    scanner.acknowledgeError()
                }
            }
        }
        viewModelScope.launch {
            gatt.state.collect { connection ->
                _state.update { it.copy(connection = it.connection.applyLink(connection)) }
                when (connection) {
                    // The link is up: name it in the notification the service is already showing.
                    is ConnectionState.Connected -> holdForeground(connection.address)
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> releaseForeground()
                    is ConnectionState.Connecting -> Unit
                }
                if (connection is ConnectionState.Failed) _messages.tryEmit(connection.reason)
            }
        }
        viewModelScope.launch {
            gatt.facts.collect { facts -> _state.update { it.copy(connection = it.connection.copy(facts = facts)) } }
        }
        viewModelScope.launch {
            gatt.subscriptions.collect { subscriptions ->
                _state.update { it.copy(connection = it.connection.copy(subscriptions = subscriptions)) }
            }
        }
        viewModelScope.launch {
            gatt.events.collect { event -> _state.update { it.copy(connection = it.connection.withEvent(event)) } }
        }
    }

    override fun onCleared() {
        scanner.stop()
        gatt.close()
        releaseForeground()
    }

    // ---- scanning -------------------------------------------------------------------------

    fun toggleScan() {
        if (_state.value.scanning) scanner.stop() else scanner.start(_state.value.continuous)
    }

    fun setContinuous(continuous: Boolean) {
        val wasScanning = _state.value.scanning
        _state.update { it.copy(continuous = continuous) }
        if (wasScanning) {
            scanner.stop()
            scanner.start(continuous)
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query).withDevices(scanner.devices.value) }

    fun setConnectableOnly(enabled: Boolean) =
        _state.update { it.copy(connectableOnly = enabled).withDevices(scanner.devices.value) }

    fun setHideUnnamed(enabled: Boolean) =
        _state.update { it.copy(hideUnnamed = enabled).withDevices(scanner.devices.value) }

    fun clearDevices() {
        scanner.clear()
        _state.update { it.copy(selectedAddress = null).withDevices(emptyMap()) }
    }

    fun select(address: String?) = _state.update { it.copy(selectedAddress = address) }

    // ---- connection -----------------------------------------------------------------------

    fun connect(address: String) {
        if (_state.value.connection.phase == ConnectionPhase.CONNECTING) return
        // Claimed before the connect, while the screen is certainly visible: from here on the link
        // survives the user leaving the app, which is the whole point of a live capture.
        holdForeground(null)
        viewModelScope.launch {
            try {
                gatt.connect(address)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "Connection failed.")
            }
        }
    }

    fun disconnect() = gatt.disconnect()

    fun toggleService(instanceId: Int) = _state.update { current ->
        val expanded = current.connection.expandedServices
        current.copy(
            connection = current.connection.copy(
                expandedServices = if (instanceId in expanded) expanded - instanceId else expanded + instanceId,
            ),
        )
    }

    fun read(ref: CharacteristicRef) = runOperation("Reading ${displayName(ref.uuid)}") {
        val value = gatt.readCharacteristic(ref)
        _state.update { current ->
            current.copy(connection = current.connection.copy(values = current.connection.values + (ref.instanceId to value.toHex())))
        }
    }

    fun setSubscribed(ref: CharacteristicRef, enabled: Boolean) =
        runOperation(if (enabled) "Subscribing to ${displayName(ref.uuid)}" else "Unsubscribing") {
            val indication = gatt.setNotificationsEnabled(ref, enabled)
            if (enabled) {
                _messages.tryEmit(
                    "Subscribed to ${displayName(ref.uuid)} " + if (indication) "(indications)" else "(notifications)",
                )
            }
        }

    // ---- write dialog ---------------------------------------------------------------------

    fun openWriteDialog(ref: CharacteristicRef, properties: List<String>) {
        val withResponse = "WRITE" in properties
        val withoutResponse = "WRITE_NO_RESPONSE" in properties
        _state.update {
            it.copy(
                writeDialog = WriteDialogState(
                    ref = ref,
                    label = displayName(ref.uuid),
                    withResponse = withResponse || !withoutResponse,
                    supportsWithResponse = withResponse,
                    supportsWithoutResponse = withoutResponse,
                ),
            )
        }
    }

    fun updateWriteHex(hex: String) = _state.update { current ->
        current.copy(writeDialog = current.writeDialog?.copy(hex = hex.uppercase(Locale.ROOT)))
    }

    fun updateWriteResponse(withResponse: Boolean) = _state.update { current ->
        current.copy(writeDialog = current.writeDialog?.copy(withResponse = withResponse))
    }

    fun dismissWriteDialog() = _state.update { it.copy(writeDialog = null) }

    fun submitWrite() {
        val dialog = _state.value.writeDialog ?: return
        if (!dialog.valid) return
        val bytes = runCatching { dialog.hex.hexToBytes() }.getOrElse { error ->
            _messages.tryEmit(error.message ?: "That is not valid hexadecimal.")
            return
        }
        val writeType = if (dialog.withResponse) WriteType.WITH_RESPONSE else WriteType.WITHOUT_RESPONSE
        _state.update { it.copy(writeDialog = null) }
        runOperation("Writing ${bytes.size} bytes to ${dialog.label}") {
            gatt.writeCharacteristic(dialog.ref, bytes, writeType)
            _messages.tryEmit("Wrote ${bytes.size} bytes to ${dialog.label}")
        }
    }

    // ---- persistence ----------------------------------------------------------------------

    fun openSessionPicker() {
        val address = _state.value.connection.address ?: _state.value.selectedAddress
        if (address == null) {
            _messages.tryEmit("Select a device first.")
            return
        }
        val suggested = scanner.device(address)?.name?.takeIf { it.isNotBlank() } ?: address
        _state.update { it.copy(sessionPicker = SessionPickerState(newName = suggested, loading = true)) }
        viewModelScope.launch {
            val sessions = container.sessions.list().map { SessionOption(it.id, it.name, it.updatedAtEpochMs) }
            _state.update { current ->
                current.copy(sessionPicker = current.sessionPicker?.copy(sessions = sessions, loading = false))
            }
        }
    }

    fun dismissSessionPicker() = _state.update { it.copy(sessionPicker = null) }

    fun selectSessionTarget(id: String?) = _state.update { current ->
        current.copy(sessionPicker = current.sessionPicker?.copy(selectedId = id))
    }

    fun updateNewSessionName(name: String) = _state.update { current ->
        current.copy(sessionPicker = current.sessionPicker?.copy(newName = name))
    }

    fun saveToSession() {
        val picker = _state.value.sessionPicker ?: return
        // Counted before the facts are read, so the count can only lag the list, never lead it:
        // an undercount re-offers a sample on the next save, an overcount would drop one.
        val linkSamples = gatt.linkSampleCount
        val live = _state.value.connection.copy(facts = gatt.facts.value)
        val address = live.address ?: _state.value.selectedAddress ?: return
        if (picker.selectedId == null && picker.newName.isBlank()) {
            _messages.tryEmit("Give the session a name.")
            return
        }
        _state.update { current -> current.copy(sessionPicker = current.sessionPicker?.copy(saving = true)) }
        val name = picker.newName.trim()
        viewModelScope.launch {
            try {
                val target = picker.selectedId
                val stored = if (target == null) {
                    container.sessions.save(composeSession(null, live, linkSamples, name, address))
                } else {
                    // Patched inside the store's lock: the capture and relay screens append to the
                    // same file, and none of their records may be lost to this save.
                    container.sessions.update(target) { base ->
                        composeSession(base, live, linkSamples, name, address)
                    }
                }
                mergedReconnectSamples[stored.id] = linkSamples
                _state.update { it.copy(sessionPicker = null) }
                _messages.tryEmit("Saved to \"${stored.name}\"")
                _saved.tryEmit(SavedToSession(stored.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { current -> current.copy(sessionPicker = current.sessionPicker?.copy(saving = false)) }
                _messages.tryEmit("Could not save the session: ${error.message ?: "unknown error"}")
            }
        }
    }

    /**
     * Folds the live link's evidence into [existing], or into a new session when there is none.
     *
     * Nothing here is cleared after a save, so every list is merged by identity rather than
     * appended: saving the same connection twice must not double every record.
     */
    private fun composeSession(
        existing: CaptureSession?,
        live: ConnectionUiState,
        linkSamples: Int,
        newName: String,
        address: String,
    ): CaptureSession {
        val advertised = scanner.device(address)
        val identity = buildIdentity(address, advertised)
        val sample = advertised?.rawRecord?.let { raw ->
            AdvertisementSample(
                timestampEpochMs = advertised.lastSeenEpochMs,
                rssi = advertised.rssi,
                txPower = advertised.txPower,
                connectable = advertised.connectable,
                primaryPhy = advertised.primaryPhy,
                secondaryPhy = advertised.secondaryPhy,
                bytesHex = raw.toHex(),
            )
        }
        val base = existing ?: CaptureSession(name = newName.ifBlank { identity.name ?: address })
        // The live buffer is not cleared after a save, so saving twice would append the same
        // events again — duplicate ids break the keyed timeline in the Sessions tab.
        val known = base.events.mapTo(HashSet(base.events.size), BleEvent::id)
        return base.copy(
            device = identity,
            advertisements = mergeAdvertisements(base.advertisements, sample),
            gatt = live.database ?: base.gatt,
            connection = mergeLiveFacts(
                base.connection,
                live.facts,
                newSamples = linkSamples - (mergedReconnectSamples[base.id] ?: 0),
            ),
            events = base.events + live.events.filterNot { it.id in known },
        )
    }

    private fun buildIdentity(address: String, advertised: ScannedDevice?) = DeviceIdentity(
        address = address,
        name = advertised?.name,
        advertisedServiceUuids = advertised?.serviceUuids.orEmpty(),
        manufacturerData = advertised?.manufacturerData?.mapValues { (_, bytes) -> bytes.toHex() }.orEmpty(),
        serviceData = advertised?.serviceData?.mapValues { (_, bytes) -> bytes.toHex() }.orEmpty(),
        bonded = isBonded(address),
    )

    private fun isBonded(address: String): Boolean {
        if (!gatt.hasConnectPermission()) return false
        val adapter = container.bluetoothManager.adapter ?: return false
        return runCatching { adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }
            .getOrDefault(false)
    }

    // ---- plumbing -------------------------------------------------------------------------

    /**
     * Keeps the process in the foreground for the duration of the link.
     *
     * A relay run may already own the same service, so the mode is claimed rather than the service
     * started outright: [releaseForeground] then drops this screen's claim without ever pulling the
     * service - and the relay - down with it.
     */
    private fun holdForeground(address: String?) {
        val started = runCatching {
            BleForegroundService.start(container.appContext, BleForegroundService.MODE_GATT, address)
        }
        val failure = started.exceptionOrNull()
        if (failure == null) {
            foregroundHeld = true
        } else if (!foregroundHeld) {
            _messages.tryEmit(
                "The connection will drop if you leave the app: the foreground service could not " +
                    "start (${failure.message ?: failure.javaClass.simpleName}).",
            )
        }
    }

    private fun releaseForeground() {
        if (!foregroundHeld) return
        foregroundHeld = false
        runCatching { BleForegroundService.release(container.appContext, BleForegroundService.MODE_GATT) }
    }

    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (_state.value.connection.busy) {
            _messages.tryEmit("Another GATT operation is still running.")
            return
        }
        _state.update { it.copy(connection = it.connection.copy(busyLabel = label)) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "The GATT operation failed.")
            } finally {
                _state.update { it.copy(connection = it.connection.copy(busyLabel = null)) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScanViewModel(container) }
        }
    }
}

/** One and the same advertisement: the identical raw record, seen at the identical instant. */
private fun AdvertisementSample.matches(other: AdvertisementSample): Boolean =
    timestampEpochMs == other.timestampEpochMs && bytesHex == other.bytesHex

/**
 * Appends [sample] unless the session already holds that very advertisement.
 *
 * The scanner keeps the last record per device, so every save offers the same sample again until a
 * fresh advertisement arrives.
 */
internal fun mergeAdvertisements(
    base: List<AdvertisementSample>,
    sample: AdvertisementSample?,
): List<AdvertisementSample> =
    if (sample == null || base.any { it.matches(sample) }) base else base + sample

/**
 * Folds the live link's facts into the stored ones. Live readings win where they exist; lists are
 * merged by identity, because nothing on the screen is cleared once it has been saved.
 *
 * @param newSamples link-setup timings [GattClient] has recorded since this session was last
 *   saved. Only the tail is appended, and only as far as the client's capped list still reaches:
 *   its size stops growing long before the timings stop arriving, so a count of arrivals is the
 *   only thing that says what is new.
 */
internal fun mergeLiveFacts(base: ConnectionFacts, live: ConnectionFacts, newSamples: Int) = base.copy(
    negotiatedMtu = live.negotiatedMtu ?: base.negotiatedMtu,
    txPhy = live.txPhy ?: base.txPhy,
    rxPhy = live.rxPhy ?: base.rxPhy,
    pairingRequired = live.pairingRequired ?: base.pairingRequired,
    reconnectSamplesMs = base.reconnectSamplesMs +
        live.reconnectSamplesMs.takeLast(newSamples.coerceIn(0, live.reconnectSamplesMs.size)),
    writeWithoutResponseVerified = base.writeWithoutResponseVerified || live.writeWithoutResponseVerified,
    maxObservedResponseMs = maxOf(base.maxObservedResponseMs ?: 0L, live.maxObservedResponseMs ?: 0L)
        .takeIf { it > 0L },
    // One attempt per start instant; a repeated save re-offers the ones already stored.
    connectAttempts = (base.connectAttempts + live.connectAttempts).distinctBy { it.startedEpochMs },
)

private fun ConnectionUiState.applyLink(link: ConnectionState): ConnectionUiState = when (link) {
    is ConnectionState.Disconnected -> copy(
        phase = ConnectionPhase.IDLE,
        failureReason = null,
        database = null,
        busyLabel = null,
        subscriptions = emptySet(),
    )

    is ConnectionState.Connecting -> copy(
        address = link.address,
        phase = ConnectionPhase.CONNECTING,
        failureReason = null,
        database = null,
        values = emptyMap(),
        events = emptyList(),
    )

    is ConnectionState.Connected -> copy(
        address = link.address,
        phase = ConnectionPhase.CONNECTED,
        failureReason = null,
        database = link.services,
        expandedServices = if (link.services.services.size <= AUTO_EXPAND_LIMIT) {
            link.services.services.mapTo(mutableSetOf()) { it.instanceId }
        } else {
            emptySet()
        },
    )

    is ConnectionState.Failed -> copy(
        address = link.address,
        phase = ConnectionPhase.FAILED,
        failureReason = link.reason,
        database = null,
        busyLabel = null,
        subscriptions = emptySet(),
    )
}

private fun ConnectionUiState.withEvent(event: BleEvent): ConnectionUiState {
    val appended = if (events.size >= EVENT_HISTORY) {
        events.subList(events.size - EVENT_HISTORY + 1, events.size) + event
    } else {
        events + event
    }
    val handle = event.attributeHandle
    val inbound = event.direction == EventDirection.DEVICE_TO_LOCAL || event.direction == EventDirection.DEVICE_TO_PHONE
    return if (handle != null && inbound && event.payloadHex.isNotEmpty()) {
        copy(events = appended, values = values + (handle to event.payloadHex))
    } else {
        copy(events = appended)
    }
}

private fun ScanUiState.withDevices(devices: Map<String, ScannedDevice>): ScanUiState {
    val needle = query.trim().lowercase(Locale.ROOT).replace(":", "")
    val visible = devices.values
        .asSequence()
        .filter { !connectableOnly || it.connectable }
        .filter { !hideUnnamed || !it.name.isNullOrBlank() }
        .filter { device ->
            needle.isEmpty() ||
                device.name?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                device.address.lowercase(Locale.ROOT).replace(":", "").contains(needle)
        }
        .sortedByDescending { it.rssi }
        .toList()
    return copy(visible = visible, allDevices = devices, totalSeen = devices.size)
}
