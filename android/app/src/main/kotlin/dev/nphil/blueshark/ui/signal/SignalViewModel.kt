package dev.nphil.blueshark.ui.signal

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.SignalSnapshot
import dev.nphil.blueshark.signal.DEFAULT_PROXY_MARGIN_DB
import dev.nphil.blueshark.signal.Hint
import dev.nphil.blueshark.signal.SignalDiagnosis
import dev.nphil.blueshark.signal.SignalReport
import dev.nphil.blueshark.signal.Waypoint
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
import java.util.UUID

/** Slider bounds for the proxy margin, in dB. */
const val MIN_PROXY_MARGIN_DB = 0
const val MAX_PROXY_MARGIN_DB = 15

/** Candidate targets, taken from the Scan tab's live aggregate. */
@Immutable
data class SignalPickerState(
    val query: String = "",
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val totalSeen: Int = 0,
)

@Immutable
data class SignalUiState(
    val snapshot: SignalSnapshot,
    val address: String? = null,
    val name: String? = null,
    val monitoring: Boolean = false,
    val hints: List<Hint> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val proxyMarginDb: Int = DEFAULT_PROXY_MARGIN_DB,
    val label: String = "",
    /** Mirrors Settings > Debug logging, so "Send to ntfy" only appears when the sink is on. */
    val ntfyEnabled: Boolean = false,
    val picker: SignalPickerState = SignalPickerState(),
)

/**
 * Drives the placement screen: one [dev.nphil.blueshark.ble.SignalMonitor] target, the waypoints
 * the operator marked while walking, and the report they hand to whoever moves the proxy.
 *
 * Waypoints live here only. They are notes taken during one walk-around, worthless the moment the
 * furniture moves, and persisting them would invite the operator to trust stale numbers.
 */
class SignalViewModel(private val container: AppContainer) : ViewModel() {

    private val monitor = container.signalMonitor
    private val scanner = container.scanner

    private val _state = MutableStateFlow(
        SignalUiState(snapshot = monitor.snapshot.value, address = monitor.address, monitoring = monitor.monitoring),
    )
    val state: StateFlow<SignalUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** One emission per received packet; the gauge animates a ping ring for each. */
    val pulses: SharedFlow<Unit> = monitor.pulses

    init {
        viewModelScope.launch {
            monitor.snapshot.collect { snapshot ->
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        name = snapshot.name ?: it.name,
                        monitoring = monitor.monitoring,
                        hints = SignalDiagnosis.hints(snapshot.stats, snapshot.connectable, snapshot.presence),
                    )
                }
                snapshot.error?.let { error ->
                    _messages.tryEmit(error)
                    monitor.acknowledgeError()
                }
            }
        }
        viewModelScope.launch {
            scanner.devices.collect { devices -> _state.update { it.withDevices(devices) } }
        }
        viewModelScope.launch {
            scanner.status.collect { status ->
                _state.update { it.copy(picker = it.picker.copy(scanning = status.scanning)) }
            }
        }
        viewModelScope.launch {
            container.debug.settings.collect { settings ->
                _state.update { it.copy(ntfyEnabled = settings.ntfyEnabled) }
            }
        }
    }

    override fun onCleared() {
        monitor.close()
    }

    // ---- target ---------------------------------------------------------------------------

    /** Pick a target and start measuring straight away: nobody opens this screen to not measure. */
    fun setTarget(address: String, name: String?) {
        val known = name ?: scanner.device(address)?.name
        val fresh = _state.value.address != address
        _state.update {
            // A different room's numbers must never blend into this one's.
            if (fresh) it.copy(address = address, name = known, waypoints = emptyList(), label = "")
            else it.copy(name = known ?: it.name)
        }
        startMonitoring()
    }

    /** Drop the target and go back to the picker; the walk's waypoints go with it. */
    fun clearTarget() {
        monitor.close()
        _state.update {
            it.copy(address = null, name = null, monitoring = false, waypoints = emptyList(), label = "", hints = emptyList())
        }
    }

    fun startMonitoring() {
        val address = _state.value.address ?: return
        monitor.setProxyMargin(_state.value.proxyMarginDb)
        monitor.start(address)
        _state.update { it.copy(monitoring = monitor.monitoring) }
    }

    fun stopMonitoring() {
        monitor.stop()
        _state.update { it.copy(monitoring = false) }
    }

    /** Keep the picker's list fresh without a second radio registration of our own. */
    fun ensureScanning() {
        if (!scanner.status.value.scanning) scanner.start(continuous = true)
    }

    fun setQuery(query: String) =
        _state.update { it.copy(picker = it.picker.copy(query = query)).withDevices(scanner.devices.value) }

    // ---- link -----------------------------------------------------------------------------

    fun connect() {
        if (_state.value.address == null) return
        monitor.connect()
    }

    fun disconnect() = monitor.disconnect()

    // ---- waypoints ------------------------------------------------------------------------

    fun setLabel(label: String) = _state.update { it.copy(label = label) }

    /** Freeze the current statistics under [label] so two locations can be compared side by side. */
    fun markSpot(label: String) {
        val current = _state.value
        if (current.address == null) return
        if (current.snapshot.stats.sampleCount == 0) {
            _messages.tryEmit("Nothing measured yet — wait for the first packets.")
            return
        }
        val name = label.trim().ifBlank { defaultLabel(current.waypoints.size) }
        val waypoint = Waypoint(
            id = UUID.randomUUID().toString(),
            label = name,
            capturedAtEpochMs = System.currentTimeMillis(),
            stats = current.snapshot.stats,
        )
        _state.update { it.copy(waypoints = it.waypoints + waypoint, label = "") }
        _messages.tryEmit("Marked \"$name\"")
    }

    fun removeWaypoint(id: String) = _state.update { it.copy(waypoints = it.waypoints.filterNot { spot -> spot.id == id }) }

    fun setProxyMargin(db: Int) {
        val clamped = db.coerceIn(MIN_PROXY_MARGIN_DB, MAX_PROXY_MARGIN_DB)
        if (clamped == _state.value.proxyMarginDb) return
        monitor.setProxyMargin(clamped)
        _state.update { it.copy(proxyMarginDb = clamped) }
    }

    // ---- report ---------------------------------------------------------------------------

    fun copyReport() {
        val text = report() ?: return
        val clipboard = container.appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("BlueShark signal report", text))
        _messages.tryEmit("Report copied (${text.length} chars)")
    }

    fun sendReport() {
        val text = report() ?: return
        if (!_state.value.ntfyEnabled) {
            _messages.tryEmit("The ntfy sink is off — switch it on in Settings.")
            return
        }
        val current = _state.value
        viewModelScope.launch {
            try {
                val title = "BlueShark signal ${current.name ?: current.address}"
                _messages.tryEmit(container.debug.sendNow(title, text))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "Sending the report failed.")
            }
        }
    }

    private fun report(): String? {
        val current = _state.value
        val address = current.address ?: return null
        return SignalReport.render(address, current.name, current.snapshot.stats, current.waypoints)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SignalViewModel(container) }
        }
    }
}

/** `Spot 3` for a walk that already has two marks; the field pre-fills with it, blank accepts it. */
fun defaultLabel(markedSoFar: Int): String = "Spot ${markedSoFar + 1}"

private fun SignalUiState.withDevices(devices: Map<String, ScannedDevice>): SignalUiState {
    val needle = picker.query.trim().lowercase(Locale.ROOT).replace(":", "")
    val visible = devices.values
        .asSequence()
        .filter { device ->
            needle.isEmpty() ||
                device.name?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                device.address.lowercase(Locale.ROOT).replace(":", "").contains(needle)
        }
        .sortedByDescending { it.rssi }
        .toList()
    return copy(picker = picker.copy(devices = visible, totalSeen = devices.size))
}
