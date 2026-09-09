package dev.nphil.blueshark.ui.probe

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.CharacteristicRef
import dev.nphil.blueshark.ble.ConnectionState
import dev.nphil.blueshark.ble.LinkExclusivity
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.shortUuid
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.ProbeRecord
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.model.toHex
import dev.nphil.blueshark.probe.CoolLedCodec
import dev.nphil.blueshark.probe.FrameCodec
import dev.nphil.blueshark.probe.FrameCodecs
import dev.nphil.blueshark.probe.ProbeInterpreter
import dev.nphil.blueshark.probe.ProbeOutcome
import dev.nphil.blueshark.probe.ProbePlans
import dev.nphil.blueshark.probe.ProbeStep
import dev.nphil.blueshark.probe.ProbeVerdict
import dev.nphil.blueshark.service.BleForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** How long one step waits for the device to answer before the verdict becomes NO_RESPONSE. */
const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_200L

/** Silence enforced between two steps, so a straggler is recorded as late instead of misfiled. */
const val DEFAULT_INTER_STEP_DELAY_MS = 400L

const val MIN_RESPONSE_TIMEOUT_MS = 200L
const val MAX_RESPONSE_TIMEOUT_MS = 5_000L
const val MIN_INTER_STEP_DELAY_MS = 0L
const val MAX_INTER_STEP_DELAY_MS = 3_000L

/**
 * How a running sweep names itself in [dev.nphil.blueshark.ble.LinkExclusivity.holder], so any
 * other screen can refuse a write and say why in the operator's own terms.
 */
const val SWEEP_HOLDS_THE_LINK = "A Command Prober sweep"

/**
 * How the two momentary link mutations name themselves while they run.
 *
 * Connecting re-establishes the link and disconnecting drops it, so either one lands on another
 * page's sweep harder than an interleaved write would: the sweep's remaining steps stop being
 * evidence about the device at all. They are claims, not writes, but they need the same gate.
 */
const val CONNECT_HOLDS_THE_LINK = "A Command Prober connect"
const val DISCONNECT_HOLDS_THE_LINK = "A Command Prober disconnect"

/**
 * View-model key under which the one shared sweep runner lives.
 *
 * Both the full Probe page and the device-project funnel resolve their [ProbeViewModel] with this
 * key against the activity's store, so navigating between them lands on the same runner rather
 * than a second one with its own response window and its own idea of which session to save into.
 */
const val PROBE_RUNNER_KEY = "probe-runner"

/** A stray frame stream must not grow without bound while the screen is open. */
private const val MAX_LATE_RESPONSES = 64

/** A canary is interleaved after every block of this many probed opcodes. */
private const val CANARY_EVERY = 5

/** Where the link stands, as far as this screen is concerned. */
enum class ProbeLinkPhase { IDLE, CONNECTING, CONNECTED, FAILED }

/**
 * One writable characteristic of the connected device, as a probe target.
 *
 * Properties are the advertised strings from the discovered [GattDatabase] rather than platform
 * bit-masks, so this survives serialisation and needs no live `BluetoothGattCharacteristic`.
 */
@Immutable
data class ProbeTarget(
    val ref: CharacteristicRef,
    val label: String,
    val serviceLabel: String,
    val properties: List<String>,
) {
    val canNotify: Boolean get() = "NOTIFY" in properties || "INDICATE" in properties
    val canWriteNoResponse: Boolean get() = "WRITE_NO_RESPONSE" in properties
    val canWrite: Boolean get() = "WRITE" in properties
    val writable: Boolean get() = canWrite || canWriteNoResponse

    /**
     * WRITE_NO_RESPONSE where the peripheral offers it: the ATT layer then adds no acknowledgement
     * of its own, so the framed status the device sends back is the only feedback in the exchange -
     * which is exactly what the sweep is measuring.
     */
    val writeType: WriteType
        get() = if (canWriteNoResponse) WriteType.WITHOUT_RESPONSE else WriteType.WITH_RESPONSE
}

/**
 * A frame that arrived on a step's response window after that window had already closed.
 *
 * Kept apart from the step's own outcome on purpose - see the correlation contract on
 * [ProbeViewModel].
 *
 * @param stepIndex the step whose window it missed, or `null` for a frame that answered no step at
 *   all (the device talking on its own).
 * @param afterMs how long after that step's frame was written this one turned up.
 */
@Immutable
data class LateResponse(
    val stepIndex: Int?,
    val stepLabel: String,
    val hex: String,
    val afterMs: Long,
)

/** What the operator is being asked to authorise before any byte leaves the phone. */
@Immutable
data class ProbeConfirmation(
    val title: String,
    val deviceLabel: String,
    val characteristicLabel: String,
    val stepCount: Int,
    /** Risk lines for the destructive opcodes this plan contains; empty for a safe plan. */
    val riskNotes: List<String>,
    val plan: List<ProbeStep>,
)

@Immutable
data class ProbeSessionRef(val id: String, val name: String)

/** Candidate targets, taken from the Scan tab's live aggregate rather than a scan of our own. */
@Immutable
data class ProbePickerState(
    val query: String = "",
    val scanning: Boolean = false,
    val devices: List<ProbeCandidate> = emptyList(),
    val totalSeen: Int = 0,
)

@Immutable
data class ProbeCandidate(val address: String, val name: String?, val rssi: Int, val connectable: Boolean)

@Immutable
data class ProbeUiState(
    val address: String? = null,
    val name: String? = null,
    val link: ProbeLinkPhase = ProbeLinkPhase.IDLE,
    val failureReason: String? = null,
    val targets: List<ProbeTarget> = emptyList(),
    val selected: CharacteristicRef? = null,
    val codecId: String = CoolLedCodec.id,
    val responseTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    val interStepDelayMs: Long = DEFAULT_INTER_STEP_DELAY_MS,
    /** Off by default: the destructive opcodes are only ever reachable through an explicit opt-in. */
    val includeRisky: Boolean = false,
    val running: Boolean = false,
    val stepNumber: Int = 0,
    val stepTotal: Int = 0,
    val stepLabel: String = "",
    val outcomes: List<ProbeOutcome> = emptyList(),
    val lateResponses: List<LateResponse> = emptyList(),
    val runId: String? = null,
    val runLabel: String = "",
    val valueOpcodeHex: String = "",
    val valueList: String = "0x00,0x40,0x80,0xFF",
    val confirm: ProbeConfirmation? = null,
    /** Mirrors Settings > Debug logging, so "Send to ntfy" only appears when the sink is on. */
    val ntfyEnabled: Boolean = false,
    val sessions: List<ProbeSessionRef> = emptyList(),
    val sessionId: String? = null,
    val savedTo: String? = null,
    val saving: Boolean = false,
    val picker: ProbePickerState = ProbePickerState(),
) {
    val connected: Boolean get() = link == ProbeLinkPhase.CONNECTED
    val target: ProbeTarget? get() = targets.firstOrNull { it.ref == selected }
    val codec: FrameCodec get() = FrameCodecs.byId(codecId) ?: CoolLedCodec

    /** Opcodes the device answered with SUCCESS; the value sweep explores one of these. */
    val acceptedOpcodes: List<Int>
        get() = outcomes.asSequence()
            .filter { it.verdict == ProbeVerdict.ACCEPTED && !it.step.canary }
            .map { it.step.opcode }
            .distinct()
            .toList()

    val canStart: Boolean get() = !running && target?.let { it.writable && it.canNotify } == true
}

/**
 * Drives the Command Prober: write candidate frames to one characteristic, and let the device's own
 * framed status say which opcodes exist.
 *
 * ## One response per step, and never the wrong step
 *
 * This is the single correctness property the whole screen rests on. A device that answers slowly
 * would otherwise have its reply counted as the *next* step's - turning a nonexistent opcode into
 * an "accepted" one and a real one into silence, which is precisely the wrong conclusion the
 * screen exists to prevent.
 *
 * The guarantee is built from four things, in this order:
 *
 * 1. **A step's response slot is tagged with that step's index.** Exactly one [ResponseWindow] is
 *    open at a time, created before the write and carrying the step index it belongs to. The
 *    notification collector never sees a bare "next response" queue it could pull the wrong frame
 *    from.
 * 2. **The window admits exactly one frame, decided by a compare-and-set.** The collector and the
 *    runner race for the same gate: either the collector claims the window for its frame, or the
 *    runner closes it on timeout. Both cannot win, so a frame is never both "the answer" and
 *    "late", and two frames are never both "the answer".
 * 3. **Closing happens before the next window opens.** The runner closes step N's window the
 *    instant its await returns, then serves the inter-step delay, and only then opens step N+1's.
 *    Anything arriving in between is recorded in [ProbeUiState.lateResponses] against step N - it
 *    is never offered to N+1.
 * 4. **Strict serialisation.** One step is in flight at a time; a write is never issued while a
 *    response is still pending. There is no pipelining anywhere in the runner.
 *
 * What this cannot do, and does not pretend to: a frame that leaves the device in answer to step N
 * but only reaches the phone after step N+1 has already been written is physically
 * indistinguishable from N+1's answer. The window in which that is possible is exactly
 * `responseTimeoutMs + interStepDelayMs` after the write, which is why both are operator-tunable
 * and why the delay defaults to a full 400 ms of enforced silence.
 */
class ProbeViewModel(private val container: AppContainer) : ViewModel() {

    private val gatt = container.gattClient
    private val scanner = container.scanner

    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * The one open response window, or the most recently closed one.
     *
     * Written by the sweep coroutine and read by the notification collector, hence volatile. It is
     * deliberately *not* cleared on close: a late frame still has to be attributed to the step
     * whose window it missed, and that step's index lives here.
     */
    @Volatile
    private var window: ResponseWindow? = null

    private var sweepJob: Job? = null

    /** Whether this screen currently holds [BleForegroundService] in GATT mode. */
    private var foregroundHeld = false

    /** True when this screen turned notifications on and therefore owes a restore. */
    private var subscribedByUs = false

    init {
        viewModelScope.launch {
            gatt.state.collect { link ->
                _state.update { it.applyLink(link) }
                when (link) {
                    // Name this link in the notification the service is already showing.
                    is ConnectionState.Connected ->
                        if (link.address == _state.value.address) holdForeground(link.address)
                    // The link this screen was holding the process up for is gone. Without this the
                    // MODE_GATT claim outlives the link and the service never stops.
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> releaseForeground()
                    is ConnectionState.Connecting -> Unit
                }
            }
        }
        viewModelScope.launch {
            scanner.devices.collect { devices -> _state.update { it.withCandidates(devices.values) } }
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
        refreshSessions()
    }

    /**
     * Cancels, and deliberately does not release the link claim.
     *
     * Releasing here would hand the link to the next claimant while the cancelled sweep is still
     * unwinding - and that unwind is what restores the CCCD, so the new owner would have its
     * notifications switched off underneath it moments after starting. The release is bound to the
     * sweep job's completion instead, which by construction happens after that teardown.
     */
    override fun onCleared() {
        sweepJob?.cancel()
        window?.close()
        releaseForeground()
    }

    // ---- target ---------------------------------------------------------------------------

    /**
     * Refuses any state change that would cut a running sweep loose from the run it is recording.
     *
     * @return false when a sweep is running, in which case nothing changed and the operator has
     *   been told which device is still being probed.
     */
    private fun refuseWhileRunning(): Boolean {
        val current = _state.value
        if (!current.running) return true
        _messages.tryEmit("A sweep is running on ${current.address ?: "this device"} — stop it first.")
        return false
    }

    /**
     * Points the runner at a device, discarding whatever the previous one proved.
     *
     * Refused outright while a sweep is running. This view model is shared with the device-project
     * funnel, so "navigate to another project" reaches straight into a live run: the outcomes and
     * the run id would be wiped while the sweep kept writing against the old device's
     * characteristic, so its remaining steps would render under the new device's name and the
     * finished run would never be saved - persistRun needs the run id this would have cleared.
     *
     * @return false when a sweep is running and the target was therefore left alone.
     */
    fun setTarget(address: String, name: String?): Boolean {
        if (_state.value.address == address) return true
        if (!refuseWhileRunning()) return false
        val known = name ?: scanner.device(address)?.name
        // A different device's opcode map must never blend into this one's.
        _state.update {
            it.copy(
                address = address,
                name = known,
                targets = emptyList(),
                selected = null,
                outcomes = emptyList(),
                lateResponses = emptyList(),
                runId = null,
                runLabel = "",
                savedTo = null,
                failureReason = null,
            ).applyLink(gatt.state.value)
        }
        return true
    }

    /** @return false when a sweep is running and the target was therefore left alone. */
    fun clearTarget(): Boolean {
        if (!refuseWhileRunning()) return false
        _state.update {
            ProbeUiState(
                codecId = it.codecId,
                responseTimeoutMs = it.responseTimeoutMs,
                interStepDelayMs = it.interStepDelayMs,
                ntfyEnabled = it.ntfyEnabled,
                sessions = it.sessions,
                picker = it.picker,
            )
        }
        return true
    }

    /** Keep the picker's list fresh without a second radio registration of our own. */
    fun ensureScanning() {
        if (!scanner.status.value.scanning) scanner.start(continuous = true)
    }

    fun setQuery(query: String) = _state.update {
        it.copy(picker = it.picker.copy(query = query)).withCandidates(scanner.devices.value.values)
    }

    fun connect() {
        val address = _state.value.address ?: return
        if (_state.value.link == ProbeLinkPhase.CONNECTING) return
        // Held across the whole connect rather than just its launch: re-establishing the link is
        // not instantaneous, and another page's sweep must not start inside it.
        val claim = claimLinkOrRefuse(CONNECT_HOLDS_THE_LINK) ?: return
        holdForeground(address)
        viewModelScope.launch {
            try {
                gatt.connect(address)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "Connection failed.")
            } finally {
                container.linkExclusivity.release(claim)
            }
        }
    }

    fun disconnect() {
        if (_state.value.running) {
            _messages.tryEmit("Stop the sweep before dropping the link.")
            return
        }
        // The check above only knows about *this* screen's sweep. Another page may be running one
        // on the same link, and dropping it underneath them is the worst interference available.
        val claim = claimLinkOrRefuse(DISCONNECT_HOLDS_THE_LINK) ?: return
        try {
            gatt.disconnect()
        } finally {
            container.linkExclusivity.release(claim)
        }
    }

    fun selectCharacteristic(ref: CharacteristicRef) {
        if (_state.value.running) return
        _state.update { it.copy(selected = ref) }
    }

    // ---- knobs ----------------------------------------------------------------------------

    fun setCodec(id: String) {
        if (_state.value.running) return
        if (FrameCodecs.byId(id) == null) return
        _state.update { it.copy(codecId = id) }
    }

    fun setResponseTimeout(ms: Long) = _state.update {
        it.copy(responseTimeoutMs = ms.coerceIn(MIN_RESPONSE_TIMEOUT_MS, MAX_RESPONSE_TIMEOUT_MS))
    }

    fun setInterStepDelay(ms: Long) = _state.update {
        it.copy(interStepDelayMs = ms.coerceIn(MIN_INTER_STEP_DELAY_MS, MAX_INTER_STEP_DELAY_MS))
    }

    fun setIncludeRisky(enabled: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(includeRisky = enabled) }
    }

    fun setValueOpcode(hex: String) = _state.update {
        it.copy(valueOpcodeHex = hex.trim().uppercase(Locale.ROOT))
    }

    fun setValueList(text: String) = _state.update { it.copy(valueList = text) }

    fun setObservedEffect(stepIndex: Int, text: String) = _state.update { current ->
        if (stepIndex !in current.outcomes.indices) {
            current
        } else {
            current.copy(
                outcomes = current.outcomes.mapIndexed { index, outcome ->
                    if (index == stepIndex) outcome.copy(observedEffect = text) else outcome
                },
            )
        }
    }

    // ---- sweeps ---------------------------------------------------------------------------

    /** Opens the confirmation for the built-in opcode sweep. Nothing is written until it is taken. */
    fun requestOpcodeSweep() {
        val plan = ProbePlans.withCanaries(ProbePlans.coolLedOpcodeSweep(), every = CANARY_EVERY)
        askToRun("Sweep the opcode map", plan)
    }

    /**
     * Opens the confirmation for a value sweep of one opcode.
     *
     * This is the only path to a destructive opcode, and it refuses to plan one unless
     * [ProbeUiState.includeRisky] is on.
     */
    fun requestValueSweep() {
        val current = _state.value
        val opcode = parseByte(current.valueOpcodeHex)
        if (opcode == null) {
            _messages.tryEmit("Give the opcode as one hex byte, e.g. 08 or 0x08.")
            return
        }
        val values = parseByteList(current.valueList)
        if (values.isEmpty()) {
            _messages.tryEmit("Give at least one value, e.g. 0x00,0x40,0x80,0xFF.")
            return
        }
        if (opcode in ProbePlans.DESTRUCTIVE_OPCODES && !current.includeRisky) {
            val reason = ProbePlans.destructiveReason(opcode) ?: "it is known to change device state"
            _messages.tryEmit(
                "0x%02X is a risky opcode: %s. Switch on \"Include risky opcodes\" to sweep it."
                    .format(opcode, reason),
            )
            return
        }
        val plan = ProbePlans.withCanaries(ProbePlans.valueSweep(opcode, values), every = CANARY_EVERY)
        askToRun("Sweep 0x%02X across %d values".format(opcode, values.size), plan)
    }

    private fun askToRun(title: String, plan: List<ProbeStep>) {
        val current = _state.value
        if (current.running) {
            _messages.tryEmit("A sweep is already running.")
            return
        }
        // Somebody else is already writing to this device. Refused here, before the operator is
        // asked to authorise a plan that could not have produced trustworthy verdicts anyway.
        container.linkExclusivity.holder.value?.let { holder ->
            _messages.tryEmit("$holder is using the Bluetooth link — a sweep now would misattribute its answers.")
            return
        }
        val target = current.target
        if (target == null) {
            _messages.tryEmit("Connect and pick a characteristic first.")
            return
        }
        if (!target.writable) {
            _messages.tryEmit("${target.label} advertises no write property — nothing can be probed on it.")
            return
        }
        if (!target.canNotify) {
            _messages.tryEmit(
                "${target.label} advertises neither NOTIFY nor INDICATE. Without notifications there " +
                    "is no answer to correlate, so a sweep would prove nothing.",
            )
            return
        }
        if (plan.isEmpty()) {
            _messages.tryEmit("That plan has no steps.")
            return
        }
        val risky = plan.asSequence()
            .map { it.opcode }
            .filter { it in ProbePlans.DESTRUCTIVE_OPCODES }
            .distinct()
            .mapNotNull { opcode ->
                ProbePlans.destructiveReason(opcode)?.let { "0x%02X — %s".format(opcode, it) }
            }
            .toList()
        _state.update {
            it.copy(
                confirm = ProbeConfirmation(
                    title = title,
                    deviceLabel = it.name?.takeIf(String::isNotBlank) ?: it.address.orEmpty(),
                    characteristicLabel = target.label,
                    stepCount = plan.size,
                    riskNotes = risky,
                    plan = plan,
                ),
            )
        }
    }

    fun dismissConfirmation() = _state.update { it.copy(confirm = null) }

    fun confirmSweep() {
        val current = _state.value
        val confirmation = current.confirm ?: return
        val address = current.address ?: return
        val target = current.target ?: return
        val claim = claimLinkOrRefuse(SWEEP_HOLDS_THE_LINK) ?: return
        _state.update {
            it.copy(
                confirm = null,
                running = true,
                stepNumber = 0,
                stepTotal = confirmation.plan.size,
                stepLabel = "",
                outcomes = emptyList(),
                lateResponses = emptyList(),
                runId = UUID.randomUUID().toString(),
                runLabel = confirmation.title,
                savedTo = null,
            )
        }
        holdForeground(address)
        val job = viewModelScope.launch {
            try {
                runSweep(address, target, current.codec, confirmation.plan, current.responseTimeoutMs, current.interStepDelayMs)
                _messages.tryEmit("Sweep finished: ${_state.value.outcomes.size} steps.")
                persistRun(announce = false)
            } catch (cancellation: CancellationException) {
                _messages.tryEmit("Sweep stopped after ${_state.value.outcomes.size} steps.")
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "The sweep failed.")
            } finally {
                _state.update { it.copy(running = false, stepLabel = "") }
            }
        }
        // Bound to completion rather than released in the body's finally, for two reasons. It runs
        // even when the scope was already cancelled and the body therefore never started, which a
        // finally would miss - and a leaked process-wide claim locks the link for every page, with
        // no way back short of restarting the app. And completion is strictly after runSweep's own
        // finally, so the CCCD is already restored before the next claimant can take the link.
        job.invokeOnCompletion { container.linkExclusivity.release(claim) }
        sweepJob = job
    }

    fun stopSweep() {
        if (!_state.value.running) return
        sweepJob?.cancel()
    }

    /**
     * The sweep proper.
     *
     * Notifications go on **first** and loudly: without them a write proves nothing, so a
     * characteristic that cannot notify is refused here rather than probed blindly.
     */
    private suspend fun runSweep(
        address: String,
        target: ProbeTarget,
        codec: FrameCodec,
        plan: List<ProbeStep>,
        timeoutMs: Long,
        delayMs: Long,
    ) {
        if (!target.canNotify) {
            throw IllegalStateException(
                "${target.label} advertises neither NOTIFY nor INDICATE — a sweep without responses " +
                    "cannot tell an accepted opcode from a nonexistent one.",
            )
        }
        // A link that dropped between picking the characteristic and starting is re-established,
        // and the ref re-resolved against the fresh database: instance ids come from ATT handles
        // and a reconnect may hand out different ones.
        val ref = if (linkIsUpFor(address)) {
            target.ref
        } else {
            val database = gatt.connect(address)
            resolveRef(database, target.ref)
                ?: throw IllegalStateException(
                    "${target.label} is not in the attribute database this device just handed us.",
                )
        }

        val alreadySubscribed = ref in gatt.subscriptions.value
        val indication = gatt.setNotificationsEnabled(ref, true)
        subscribedByUs = !alreadySubscribed
        _messages.tryEmit(
            "Listening on ${target.label} (${if (indication) "indications" else "notifications"}); " +
                "${plan.size} steps at ${target.writeType.short()}.",
        )

        // Subscribed before the first byte leaves: `events` replays nothing, so a collector that
        // starts a moment later would miss a fast device's answer to step 1.
        val subscribed = CompletableDeferred<Unit>()
        val collector = viewModelScope.launch {
            gatt.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event -> route(event, ref) }
        }
        try {
            subscribed.await()
            for ((index, step) in plan.withIndex()) {
                // Enforced silence: any straggler from the previous step lands here and is filed
                // against that step, not offered to this one.
                if (index > 0 && delayMs > 0L) delay(delayMs)
                probeOne(index, plan.size, step, ref, target.writeType, codec, timeoutMs)
            }
        } finally {
            collector.cancel()
            window?.close()
            if (subscribedByUs) {
                subscribedByUs = false
                // The sweep may be unwinding under cancellation; restoring the CCCD still has to
                // happen or notifications keep flowing into every other screen's log.
                withContext(NonCancellable) {
                    runCatching { gatt.setNotificationsEnabled(ref, false) }
                }
            }
        }
    }

    private suspend fun probeOne(
        index: Int,
        total: Int,
        step: ProbeStep,
        ref: CharacteristicRef,
        writeType: WriteType,
        codec: FrameCodec,
        timeoutMs: Long,
    ) {
        val frame = codec.encode(step.payload())
        _state.update { it.copy(stepNumber = index + 1, stepTotal = total, stepLabel = step.label) }

        // Opened before the write: a peripheral that answers inside the write callback must not
        // race its own response into existence.
        val open = ResponseWindow(index, step.label)
        window = open
        val startedAtNanos = System.nanoTime()
        val outcome = try {
            gatt.writeCharacteristic(ref, frame, writeType)
            val response = open.await(timeoutMs)
            open.close()
            val (verdict, status) = ProbeInterpreter.verdictFor(response, codec)
            ProbeOutcome(
                step = step,
                sentHex = frame.toHex(),
                responseHex = response?.toHex(),
                verdict = verdict,
                statusByte = status,
                elapsedMs = elapsedMs(startedAtNanos),
            )
        } catch (cancellation: CancellationException) {
            open.close()
            throw cancellation
        } catch (error: Throwable) {
            open.close()
            ProbeOutcome(
                step = step,
                sentHex = frame.toHex(),
                responseHex = null,
                verdict = ProbeVerdict.ERROR,
                statusByte = null,
                elapsedMs = elapsedMs(startedAtNanos),
                note = error.message ?: error.javaClass.simpleName,
            )
        }
        _state.update { it.copy(outcomes = it.outcomes + outcome) }
    }

    /**
     * Offers one notification to the open window, or files it as late.
     *
     * A frame is matched on the pair (attribute handle, UUID) rather than the UUID alone: a
     * peripheral may expose the same UUID more than once, and only one of those instances is the
     * one being probed.
     */
    private fun route(event: BleEvent, ref: CharacteristicRef) {
        if (event.operation != AttOperation.NOTIFICATION && event.operation != AttOperation.INDICATION) return
        if (event.attributeHandle != ref.instanceId) return
        if (!ref.uuid.equals(event.characteristicUuid, ignoreCase = true)) return
        val bytes = runCatching { event.payloadHex.hexToBytes() }.getOrNull() ?: return
        val open = window
        if (open != null && open.offer(bytes)) return
        noteLate(open, event.payloadHex)
    }

    private fun noteLate(closed: ResponseWindow?, hex: String) {
        val late = LateResponse(
            stepIndex = closed?.stepIndex,
            stepLabel = closed?.stepLabel ?: "unsolicited",
            hex = hex,
            afterMs = closed?.let { elapsedMs(it.openedAtNanos) } ?: 0L,
        )
        _state.update { it.copy(lateResponses = (it.lateResponses + late).takeLast(MAX_LATE_RESPONSES)) }
    }

    // ---- report ---------------------------------------------------------------------------

    private fun report(): String? {
        val current = _state.value
        if (current.outcomes.isEmpty()) return null
        val device = buildString {
            append(current.name?.takeIf(String::isNotBlank) ?: "Unnamed device")
            current.address?.let { append(" (").append(it).append(')') }
            current.target?.let { append(" · ").append(it.label) }
        }
        val body = ProbeInterpreter.summarise(current.outcomes, device = device, codec = current.codec)
        if (current.lateResponses.isEmpty()) return body
        return buildString {
            append(body)
            append("\n\nLate responses (never attributed to a step):\n")
            for (late in current.lateResponses) {
                append("  ")
                append(if (late.stepIndex == null) "unsolicited" else "step ${late.stepIndex + 1} (${late.stepLabel})")
                append(" +").append(late.afterMs).append(" ms  ").append(late.hex).append('\n')
            }
        }
    }

    fun copyReport() {
        val text = report()
        if (text == null) {
            _messages.tryEmit("Run a sweep first.")
            return
        }
        val clipboard = container.appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("BlueShark probe report", text))
        _messages.tryEmit("Report copied (${text.length} chars)")
    }

    fun sendReport() {
        val text = report()
        if (text == null) {
            _messages.tryEmit("Run a sweep first.")
            return
        }
        if (!_state.value.ntfyEnabled) {
            _messages.tryEmit("The ntfy sink is off — switch it on in Settings.")
            return
        }
        val current = _state.value
        viewModelScope.launch {
            try {
                val title = "BlueShark probe ${current.name ?: current.address}"
                _messages.tryEmit(container.debug.sendNow(title, text))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _messages.tryEmit(error.message ?: "Sending the report failed.")
            }
        }
    }

    // ---- persistence ----------------------------------------------------------------------

    fun refreshSessions() {
        viewModelScope.launch {
            val sessions = runCatching { container.sessions.list() }.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(sessions = sessions.map { ProbeSessionRef(it.id, it.name) })
            }
        }
    }

    /**
     * Chooses where the next save lands.
     *
     * Refused while a sweep is running: the destination is read at save time, so re-pointing it
     * mid-run would file the device currently being probed under whatever session the operator
     * navigated to next.
     *
     * @return false when a sweep is running and the destination was therefore left alone.
     */
    fun selectSession(id: String?): Boolean {
        if (!refuseWhileRunning()) return false
        _state.update { it.copy(sessionId = id) }
        return true
    }

    fun saveRun() {
        viewModelScope.launch { persistRun(announce = true) }
    }

    /**
     * Writes the last run into the selected session - or a fresh one - so it exports with every
     * other kind of evidence.
     *
     * Records of the same run replace their predecessors rather than being appended to them: the
     * pair (run id, step index) is a record's identity, so saving twice, or saving again after the
     * operator typed an observed effect, updates instead of duplicating.
     */
    private suspend fun persistRun(announce: Boolean) {
        val current = _state.value
        val runId = current.runId ?: return
        if (current.outcomes.isEmpty()) {
            if (announce) _messages.tryEmit("Run a sweep first.")
            return
        }
        val address = current.address ?: return
        val records = current.records(runId)
        _state.update { it.copy(saving = true) }
        try {
            val stored = if (current.sessionId == null) {
                container.sessions.save(
                    CaptureSession(
                        name = current.name?.takeIf(String::isNotBlank)?.let { "Probe $it" } ?: "Probe $address",
                        device = DeviceIdentity(address = address, name = current.name),
                        probes = records,
                    ),
                )
            } else {
                // Patched inside the store's lock: the scan and capture screens append to the same
                // file, and none of their records may be lost to this save.
                container.sessions.update(current.sessionId) { base -> base.withRun(runId, records) }
            }
            _state.update { it.copy(sessionId = stored.id, savedTo = stored.name, saving = false) }
            refreshSessions()
            if (announce) _messages.tryEmit("Saved ${records.size} probe records to \"${stored.name}\"")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _state.update { it.copy(saving = false) }
            _messages.tryEmit("Could not save the run: ${error.message ?: "unknown error"}")
        }
    }

    private fun ProbeUiState.records(runId: String): List<ProbeRecord> {
        val ref = target?.ref
        val lateByStep = lateResponses.groupBy { it.stepIndex }
        return outcomes.mapIndexed { index, outcome ->
            ProbeRecord(
                runId = runId,
                stepIndex = index,
                opcode = outcome.step.opcode,
                label = outcome.step.label,
                argumentHex = outcome.step.argument.toHex(),
                codecId = codecId,
                serviceUuid = ref?.serviceUuid.orEmpty(),
                characteristicUuid = ref?.uuid.orEmpty(),
                writeType = target?.writeType ?: WriteType.WITHOUT_RESPONSE,
                sentHex = outcome.sentHex,
                responseHex = outcome.responseHex,
                verdict = outcome.verdict.name,
                statusByte = outcome.statusByte,
                elapsedMs = outcome.elapsedMs,
                canary = outcome.step.canary,
                lateResponsesHex = lateByStep[index]?.map { it.hex }.orEmpty(),
                note = outcome.note,
                observedEffect = outcome.observedEffect,
            )
        }
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun linkIsUpFor(address: String): Boolean {
        val link = gatt.state.value
        return link is ConnectionState.Connected && link.address == address
    }

    /**
     * Takes exclusive ownership of the shared link, or refuses out loud and returns null.
     *
     * Never queues. An operation that silently waited its turn would land at a moment the operator
     * did not choose - very possibly in the middle of somebody else's sweep, which is the exact
     * interference the claim exists to prevent. Refusing is the honest answer, and the holder is
     * named so the operator knows who to blame.
     */
    private fun claimLinkOrRefuse(reason: String): LinkExclusivity.Claim? {
        val claim = container.linkExclusivity.claim(reason)
        if (claim == null) {
            _messages.tryEmit(
                "${container.linkExclusivity.holder.value ?: "Another page"} is using the Bluetooth " +
                    "link — try again when it finishes.",
            )
        }
        return claim
    }

    /**
     * Keeps the process in the foreground for the duration of the link.
     *
     * A relay run may already own the same service, so the mode is claimed rather than the service
     * started outright; [releaseForeground] then drops this screen's claim without pulling the
     * service - and the relay - down with it.
     *
     * At most one claim is ever outstanding per view model, and that is load-bearing rather than
     * tidiness: the service counts claims per `start` call and [releaseForeground] decrements by
     * one, so the three places that hold the link for a single session - connecting, starting a
     * sweep, and the state collector seeing the link come up - would otherwise leave the count
     * above zero and the notification stranded with nothing left to clear it.
     */
    private fun holdForeground(address: String?) {
        if (foregroundHeld) return
        val started = runCatching {
            BleForegroundService.start(container.appContext, BleForegroundService.MODE_GATT, address)
        }
        val failure = started.exceptionOrNull()
        if (failure == null) {
            foregroundHeld = true
        } else {
            _messages.tryEmit(
                "The sweep will be cut short if you leave the app: the foreground service could not " +
                    "start (${failure.message ?: failure.javaClass.simpleName}).",
            )
        }
    }

    private fun releaseForeground() {
        if (!foregroundHeld) return
        foregroundHeld = false
        runCatching { BleForegroundService.release(container.appContext, BleForegroundService.MODE_GATT) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProbeViewModel(container) }
        }
    }
}

private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

private fun WriteType.short(): String = when (this) {
    WriteType.WITH_RESPONSE -> "WRITE"
    WriteType.WITHOUT_RESPONSE -> "WRITE_NO_RESPONSE"
    WriteType.SIGNED -> "SIGNED_WRITE"
}

private fun CaptureSession.withRun(runId: String, records: List<ProbeRecord>): CaptureSession =
    copy(probes = probes.filterNot { it.runId == runId } + records)

/**
 * Re-resolves a characteristic against a freshly discovered database.
 *
 * Instance ids are derived from ATT handles, and a reconnect may hand out different ones, so a ref
 * picked before a dropped link cannot be reused verbatim.
 */
private fun resolveRef(database: GattDatabase, ref: CharacteristicRef): CharacteristicRef? =
    database.services.asSequence()
        .filter { it.uuid.equals(ref.serviceUuid, ignoreCase = true) }
        .flatMap { service ->
            service.characteristics.asSequence()
                .filter { it.uuid.equals(ref.uuid, ignoreCase = true) }
                .map { characteristic ->
                    CharacteristicRef(service.uuid, service.instanceId, characteristic.uuid, characteristic.instanceId)
                }
        }
        .firstOrNull()

private fun ProbeUiState.applyLink(link: ConnectionState): ProbeUiState = when (link) {
    is ConnectionState.Connected -> {
        if (link.address != address) {
            copy(link = ProbeLinkPhase.IDLE, targets = emptyList(), selected = null)
        } else {
            val targets = link.services.probeTargets()
            copy(
                link = ProbeLinkPhase.CONNECTED,
                failureReason = null,
                targets = targets,
                selected = selected?.takeIf { chosen -> targets.any { it.ref == chosen } } ?: targets.preferred(),
            )
        }
    }

    is ConnectionState.Connecting ->
        if (link.address == address) copy(link = ProbeLinkPhase.CONNECTING, failureReason = null) else this

    is ConnectionState.Failed ->
        if (link.address == address) copy(link = ProbeLinkPhase.FAILED, failureReason = link.reason, targets = emptyList()) else this

    ConnectionState.Disconnected ->
        copy(link = ProbeLinkPhase.IDLE, targets = emptyList(), selected = null)
}

/** Only writable characteristics: probing means writing, so the rest are not candidates. */
private fun GattDatabase.probeTargets(): List<ProbeTarget> = services.flatMap { service ->
    service.characteristics.map { characteristic ->
        ProbeTarget(
            ref = CharacteristicRef(
                serviceUuid = service.uuid,
                serviceInstanceId = service.instanceId,
                uuid = characteristic.uuid,
                instanceId = characteristic.instanceId,
            ),
            label = shortUuid(characteristic.uuid),
            serviceLabel = shortUuid(service.uuid),
            properties = characteristic.properties,
        )
    }
}.filter { it.writable }

/**
 * The `fff1` of a cheap serial module when it is there, because that is where this whole family of
 * gadgets takes its commands; otherwise the first characteristic that can actually answer.
 */
internal fun List<ProbeTarget>.preferred(): CharacteristicRef? =
    (
        firstOrNull { shortUuid(it.ref.uuid).equals("FFF1", ignoreCase = true) && it.canNotify }
            ?: firstOrNull { shortUuid(it.ref.uuid).equals("FFF1", ignoreCase = true) }
            ?: firstOrNull { it.canNotify }
            ?: firstOrNull()
        )?.ref

private fun ProbeUiState.withCandidates(devices: Collection<ScannedDevice>): ProbeUiState {
    val needle = picker.query.trim()
    val matching = devices.asSequence()
        .filter { device ->
            needle.isEmpty() ||
                device.address.contains(needle, ignoreCase = true) ||
                device.name?.contains(needle, ignoreCase = true) == true
        }
        .sortedByDescending { it.rssi }
        .map { ProbeCandidate(it.address, it.name, it.rssi, it.connectable) }
        .toList()
    return copy(picker = picker.copy(devices = matching, totalSeen = devices.size))
}

/** One hex byte, with or without an `0x` prefix. Null when it is not one. */
internal fun parseByte(text: String): Int? {
    val cleaned = text.trim().removePrefix("0x").removePrefix("0X")
    if (cleaned.isEmpty() || cleaned.length > 2) return null
    return cleaned.toIntOrNull(16)?.takeIf { it in 0..0xFF }
}

/**
 * `0x00,0x40,80,FF` -> `[0, 64, 128, 255]`.
 *
 * Every token is read as hexadecimal whether or not it carries an `0x`: this is a byte list in a
 * tool where every payload is hex, and `40` meaning sixty-four here and sixty-four-decimal there
 * would be a trap. A token that is not one byte drops the whole list, so a typo never silently
 * probes a value the operator did not mean to send.
 */
internal fun parseByteList(text: String): List<Int> {
    val tokens = text.split(',', ' ', '\n', '\t').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val values = ArrayList<Int>(tokens.size)
    for (token in tokens) {
        val value = parseByte(token) ?: return emptyList()
        values += value
    }
    return values
}
