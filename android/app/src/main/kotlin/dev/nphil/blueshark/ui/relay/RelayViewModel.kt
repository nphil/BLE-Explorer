package dev.nphil.blueshark.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.ConnectAttempt
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.relay.AdvertisePlanResult
import dev.nphil.blueshark.relay.AdvertisePlanner
import dev.nphil.blueshark.relay.DEFAULT_ATT_MTU
import dev.nphil.blueshark.relay.RelayConfig
import dev.nphil.blueshark.relay.RelaySession
import dev.nphil.blueshark.relay.RelayState
import dev.nphil.blueshark.relay.previewTarget
import dev.nphil.blueshark.service.BleForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private val ADDRESS_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

data class SessionRef(val id: String, val name: String)

data class RelayUiState(
    val relay: RelayState = RelayState(),
    val timeline: List<BleEvent> = emptyList(),
    val address: String = "",
    val alias: String = "",
    val adapterName: String? = null,
    val acknowledged: Boolean = false,
    val previewing: Boolean = false,
    val previewName: String? = null,
    val previewGatt: GattDatabase? = null,
    val previewMtu: Int? = null,
    val previewMirrorable: List<String> = emptyList(),
    val previewSkipped: List<String> = emptyList(),
    val advertiseSelection: Set<String> = emptySet(),
    val connectAttempts: List<ConnectAttempt> = emptyList(),
    val sessions: List<SessionRef> = emptyList(),
    val newSessionName: String = "",
    val saving: Boolean = false,
    val message: String? = null,
) {
    val addressValid: Boolean get() = ADDRESS_REGEX.matches(address)
    val running: Boolean get() = relay.phase.isActive
    val canStart: Boolean get() = addressValid && acknowledged && !running && !previewing
    val gatt: GattDatabase? get() = relay.gatt ?: previewGatt
    val mirroredServiceUuids: List<String>
        get() = relay.mirroredServiceUuids.ifEmpty { previewMirrorable }

    /** Service UUIDs that will go on air: the explicit selection, or everything mirrored. */
    val advertisedServiceUuids: List<String>
        get() = if (advertiseSelection.isEmpty()) mirroredServiceUuids else mirroredServiceUuids.filter { it in advertiseSelection }

    /**
     * Live payload arithmetic, so an impossible alias/service combination is visible before Start
     * instead of surfacing as a relay failure ten seconds later.
     */
    val advertisePlanText: String?
        get() {
            val uuids = advertisedServiceUuids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            val name = alias.ifBlank { adapterName ?: "" }.takeIf { it.isNotEmpty() }
            if (uuids.isEmpty() && name == null) return null
            return when (val result = AdvertisePlanner.plan(name, uuids)) {
                is AdvertisePlanResult.Planned -> buildString {
                    append(result.plan.summary())
                    result.plan.notes.forEach { note ->
                        append('\n')
                        append(note)
                    }
                }
                is AdvertisePlanResult.Rejected -> "Will be refused: ${result.reason}"
            }
        }
}

class RelayViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(
        RelayUiState(
            address = RelaySession.lastConfig?.targetAddress.orEmpty(),
            alias = RelaySession.lastConfig?.alias.orEmpty(),
            advertiseSelection = RelaySession.lastConfig?.advertiseServiceUuids?.toSet().orEmpty(),
            adapterName = runCatching { container.bluetoothManager.adapter?.name }.getOrNull(),
        ),
    )
    val ui: StateFlow<RelayUiState> = _ui.asStateFlow()

    private var previewJob: Job? = null

    init {
        RelaySession.preview?.let { snapshot ->
            _ui.update {
                it.copy(
                    previewName = snapshot.name,
                    previewGatt = snapshot.gatt,
                    previewMtu = snapshot.mtu,
                    previewMirrorable = snapshot.mirrorableServiceUuids,
                    previewSkipped = snapshot.skippedServiceUuids,
                    address = it.address.ifBlank { snapshot.address },
                )
            }
        }
        viewModelScope.launch {
            RelaySession.state.collect { relay -> _ui.update { it.copy(relay = relay) } }
        }
        viewModelScope.launch {
            RelaySession.timeline.collect { events -> _ui.update { it.copy(timeline = events) } }
        }
        RelaySession.refreshTimeline()
        refreshSessions()
    }

    fun onAddressChange(value: String) {
        val cleaned = value.uppercase().filter { it.isLetterOrDigit() || it == ':' }.take(17)
        _ui.update { it.copy(address = cleaned) }
    }

    fun onAliasChange(value: String) = _ui.update { it.copy(alias = value.take(29)) }

    fun onAcknowledgedChange(value: Boolean) = _ui.update { it.copy(acknowledged = value) }

    fun onNewSessionNameChange(value: String) = _ui.update { it.copy(newSessionName = value.take(80)) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun toggleAdvertised(serviceUuid: String) {
        _ui.update { state ->
            val current = state.advertiseSelection.ifEmpty { state.mirroredServiceUuids.toSet() }
            val next = if (serviceUuid in current) current - serviceUuid else current + serviceUuid
            state.copy(advertiseSelection = next)
        }
    }

    /** Connects, negotiates the MTU, lists the database and disconnects again. */
    fun previewTargetDevice() {
        if (previewJob?.isActive == true) return
        val state = _ui.value
        if (!state.addressValid) {
            message("Enter the target address as XX:XX:XX:XX:XX:XX first")
            return
        }
        if (RelaySession.isRunning) {
            message("Stop the relay before previewing; the target accepts one connection at a time")
            return
        }
        val adapter = container.bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            message("Switch Bluetooth on first")
            return
        }
        previewJob = viewModelScope.launch {
            _ui.update { it.copy(previewing = true, message = null) }
            val result = previewTarget(
                context = container.appContext,
                adapter = adapter,
                address = state.address,
                onAttempt = { attempt -> record(attempt) },
            )
            result.fold(
                onSuccess = { snapshot ->
                    RelaySession.preview = snapshot
                    _ui.update {
                        it.copy(
                            previewing = false,
                            previewName = snapshot.name,
                            previewGatt = snapshot.gatt,
                            previewMtu = snapshot.mtu,
                            previewMirrorable = snapshot.mirrorableServiceUuids,
                            previewSkipped = snapshot.skippedServiceUuids,
                            advertiseSelection = emptySet(),
                            message = "Read ${snapshot.gatt.services.size} service(s) from ${snapshot.address}",
                        )
                    }
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            previewing = false,
                            message = failure.message ?: "The preview failed",
                        )
                    }
                },
            )
        }
    }

    fun start() {
        val state = _ui.value
        if (!state.addressValid) {
            message("Enter the target address as XX:XX:XX:XX:XX:XX")
            return
        }
        if (!state.acknowledged) {
            message("Acknowledge the relay's limitations before starting")
            return
        }
        if (RelaySession.isRunning) {
            message("A relay is already running")
            return
        }
        val selection = state.advertiseSelection
            .takeIf { it.isNotEmpty() && it.size != state.mirroredServiceUuids.size }
            ?.toList()
            .orEmpty()
        RelaySession.requestStart(
            RelayConfig(
                targetAddress = state.address,
                alias = state.alias.trim(),
                advertiseServiceUuids = selection,
            ),
        )
        // Starting a foreground service is only allowed while the app is visible; report the
        // refusal instead of crashing if the screen was backgrounded between tap and dispatch.
        val launched = runCatching {
            BleForegroundService.start(container.appContext, BleForegroundService.MODE_RELAY)
        }
        val failure = launched.exceptionOrNull()
        if (failure != null) {
            message("Could not start the relay service: ${failure.message ?: failure.javaClass.simpleName}")
        } else {
            _ui.update { it.copy(message = null) }
        }
    }

    fun stop() {
        // Ends the run, then drops the relay's claim on the service: a live GATT capture on the
        // scan screen holds the same service and must keep it.
        RelaySession.stop()
        runCatching { BleForegroundService.release(container.appContext, BleForegroundService.MODE_RELAY) }
            .exceptionOrNull()
            ?.let { failure -> message("Could not stop the relay service: ${failure.message}") }
    }

    fun clearTimeline() {
        RelaySession.clearTimeline()
        message("Timeline cleared")
    }

    fun saveToNewSession() {
        val state = _ui.value
        val name = state.newSessionName.trim().ifBlank {
            "Relay ${state.address.ifBlank { "capture" }}"
        }
        persist(existingId = null, name = name)
    }

    fun appendToSession(sessionId: String) = persist(existingId = sessionId, name = null)

    private fun persist(existingId: String?, name: String?) {
        if (_ui.value.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, message = null) }
            val outcome = runCatching { write(existingId, name) }
            refreshSessions()
            _ui.update {
                it.copy(
                    saving = false,
                    newSessionName = if (outcome.isSuccess && existingId == null) "" else it.newSessionName,
                    message = outcome.fold(
                        onSuccess = { saved -> "Saved ${saved.events.size} event(s) to \"${saved.name}\"" },
                        onFailure = { failure -> failure.message ?: "Could not save the session" },
                    ),
                )
            }
        }
    }

    private suspend fun write(existingId: String?, name: String?): CaptureSession {
        val state = _ui.value
        val relay = state.relay
        val events = RelaySession.recordedEvents()
        val database = state.gatt
        require(events.isNotEmpty() || database != null) {
            "Nothing to save yet: run a preview or a relay session first"
        }
        val address = relay.targetAddress.ifBlank { state.address }
        val attempts = state.connectAttempts + relay.connectAttempts
        val merge = { base: CaptureSession ->
            val known = base.events.mapTo(HashSet(base.events.size)) { it.id }
            val identity = base.device.copy(
                address = address.ifBlank { base.device.address },
                name = relay.targetName ?: state.previewName ?: base.device.name,
                alias = state.alias.trim().ifBlank { base.device.alias },
                advertisedServiceUuids = state.advertisedServiceUuids.ifEmpty { base.device.advertisedServiceUuids },
            )
            val mtu = listOf(relay.targetMtu, state.previewMtu ?: DEFAULT_ATT_MTU).max()
            base.copy(
                device = identity,
                gatt = database ?: base.gatt,
                connection = base.connection.copy(
                    negotiatedMtu = if (mtu > DEFAULT_ATT_MTU) mtu else base.connection.negotiatedMtu,
                    // The relay's own attempt list is not cleared between saves; one per instant.
                    connectAttempts = (base.connection.connectAttempts + attempts).distinctBy { it.startedEpochMs },
                ),
                events = base.events + events.filterNot { it.id in known },
            )
        }
        if (existingId == null) {
            return container.sessions.save(
                merge(
                    CaptureSession(
                        name = name ?: "Relay $address",
                        notes = "Captured with the BlueShark MITM GATT relay. The vendor app talked to this " +
                            "phone, which forwarded every ATT transaction to $address.",
                    ),
                ),
            )
        }
        // Merged inside the store's lock: the scan and capture screens append to the same file
        // while a relay runs, and appending to a stale snapshot would drop their records.
        return container.sessions.update(existingId, merge)
    }

    private fun record(attempt: ConnectAttempt) {
        _ui.update { it.copy(connectAttempts = (it.connectAttempts + attempt).takeLast(MAX_ATTEMPTS)) }
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            val sessions = runCatching { container.sessions.list() }.getOrDefault(emptyList())
            _ui.update { state ->
                state.copy(sessions = sessions.map { SessionRef(it.id, it.name) })
            }
        }
    }

    private fun message(text: String) = _ui.update { it.copy(message = text) }

    companion object {
        private const val MAX_ATTEMPTS = 50

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RelayViewModel(container) }
        }
    }
}
