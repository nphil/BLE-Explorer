package dev.nphil.blueshark.ui.project

import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.CharacteristicRef
import dev.nphil.blueshark.ble.ConnectionState
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.releaseWhenComplete
import dev.nphil.blueshark.ble.shortUuid
import dev.nphil.blueshark.export.HaProfileBuilder
import dev.nphil.blueshark.identify.DeviceFingerprint
import dev.nphil.blueshark.identify.FamilyConfidence
import dev.nphil.blueshark.identify.FamilyMatch
import dev.nphil.blueshark.identify.FingerprintInput
import dev.nphil.blueshark.learn.CommandMap
import dev.nphil.blueshark.learn.CommandMapBuilder
import dev.nphil.blueshark.learn.MappedCommand
import dev.nphil.blueshark.learn.ProbeEvidence
import dev.nphil.blueshark.learn.ProfileDraft
import dev.nphil.blueshark.learn.ProfileDraftResult
import dev.nphil.blueshark.learn.TrafficSource
import dev.nphil.blueshark.model.AdvertisementSample
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.FamilyMatchRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.LearningRecord
import dev.nphil.blueshark.model.MappedCommandRecord
import dev.nphil.blueshark.model.ProbeRecord
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.model.toHex
import dev.nphil.blueshark.probe.FrameCodecs
import dev.nphil.blueshark.probe.ProbeVerdict
import dev.nphil.blueshark.service.BleForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Pure types <-> evidence on disk
// ---------------------------------------------------------------------------

/**
 * Identity of one command: its bytes on its characteristic.
 *
 * Delegates to `learn/` rather than re-deriving the normalisation, so a confidence or a stage
 * looked up by this key always lands on the record [CommandMapBuilder.merge] actually kept and
 * never on a differently-spelled duplicate of it.
 */
internal fun commandKey(payloadHex: String, characteristicUuid: String?): String =
    CommandMapBuilder.commandKey(payloadHex, characteristicUuid)

internal fun FamilyMatch.toRecord(): FamilyMatchRecord = FamilyMatchRecord(
    familyId = familyId,
    name = name,
    confidence = confidence.name,
    evidence = evidence,
    publicDriverUrl = publicDriverUrl,
    codecId = codecId,
    commandCharacteristicHints = commandCharacteristicHints,
)

/** How sure the fingerprint was, in words rather than an enum name shouted at the operator. */
internal fun confidenceWord(confidence: String?): String = when (confidence) {
    FamilyConfidence.CERTAIN.name -> "certain"
    FamilyConfidence.LIKELY.name -> "likely"
    FamilyConfidence.POSSIBLE.name -> "possible"
    else -> "unranked"
}

internal fun MappedCommandRecord.toMapped(): MappedCommand = MappedCommand(
    id = id,
    name = name,
    characteristicUuid = characteristicUuid,
    payloadHex = payloadHex,
    decodedHex = decodedHex,
    source = source,
    stage = stage,
    evidence = evidence,
    // Null on disk means nobody recorded it; the pure type has no "unknown", and the renderer
    // overrides whatever it is told from the characteristic's own properties anyway.
    writeType = writeType ?: WriteType.WITHOUT_RESPONSE,
)

/** The project's map as the pure type, so `learn/` owns every merge decision. */
internal fun CaptureSession.commandMapOf(): CommandMap = CommandMap(
    deviceAddress = device.address.trim().uppercase(),
    familyId = family?.familyId,
    codecId = family?.codecId,
    commands = commandMap.map { it.toMapped() },
)

/**
 * The payload inside the family's framing, or null when there is no codec or the bytes are not a
 * frame it can read.
 *
 * This is a decoding and never a guess: `010204020608FF03` becomes `08FF` because the CoolLED
 * codec says so, and a frame it rejects stays undecoded rather than being shown stripped by eye.
 */
internal fun decodedPayload(codecId: String?, payloadHex: String): String? {
    val codec = FrameCodecs.byId(codecId ?: return null) ?: return null
    val bytes = runCatching { payloadHex.hexToBytes() }.getOrNull() ?: return null
    val decoded = runCatching { codec.decode(bytes) }.getOrNull() ?: return null
    if (decoded.isEmpty() || decoded.contentEquals(bytes)) return null
    return decoded.toHex()
}

/**
 * Folds [fresh] into the project's stored map.
 *
 * The merge itself is [CommandMapBuilder.merge] - which stage wins, how evidence unions and which
 * write type survives are not this layer's judgements. What this layer adds is the one thing the
 * pure type has no room for: the attribution confidence, kept at the strongest value any pass
 * claimed, plus the codec's decoding of the frame.
 */
internal fun mergeCommandMap(
    session: CaptureSession,
    fresh: CommandMap,
    confidences: Map<String, Double> = emptyMap(),
): List<MappedCommandRecord> {
    val previous = session.commandMap.associateBy { commandKey(it.payloadHex, it.characteristicUuid) }
    val merged = CommandMapBuilder.merge(listOf(session.commandMapOf(), fresh))
    val codecId = merged.codecId ?: session.family?.codecId
    return merged.commands.map { command ->
        val key = commandKey(command.payloadHex, command.characteristicUuid)
        val known = previous[key]?.confidence
        val incoming = confidences[key]
        MappedCommandRecord(
            id = command.id,
            name = command.name,
            characteristicUuid = command.characteristicUuid,
            payloadHex = command.payloadHex,
            decodedHex = command.decodedHex ?: decodedPayload(codecId, command.payloadHex),
            source = command.source,
            stage = command.stage,
            evidence = command.evidence,
            confidence = when {
                known == null -> incoming
                incoming == null -> known
                else -> maxOf(known, incoming)
            },
            writeType = command.writeType,
        )
    }
}

/**
 * Everything a fingerprint can read about this device.
 *
 * The advertisement is taken from [DeviceIdentity] rather than re-parsed from the stored raw
 * record: the scan already split it into uuids, manufacturer and service data, and re-deriving
 * them here would be a second AD parser to keep honest.
 */
internal fun CaptureSession.fingerprintInput(): FingerprintInput = FingerprintInput(
    name = device.name?.takeIf { it.isNotBlank() } ?: device.alias,
    serviceUuids = device.advertisedServiceUuids,
    manufacturerData = device.manufacturerData.mapValues { (_, hex) -> hexOrEmpty(hex) },
    serviceData = device.serviceData.mapValues { (_, hex) -> hexOrEmpty(hex) },
    gatt = gatt,
)

private fun hexOrEmpty(hex: String): ByteArray = runCatching { hex.hexToBytes() }.getOrDefault(EMPTY_BYTES)

private val EMPTY_BYTES = ByteArray(0)

/**
 * Every sweep this project ever recorded, as a command map.
 *
 * Read from the session's own [ProbeRecord]s rather than from a runner's in-memory outcomes, for
 * two reasons that are really one: the records are what survives, and they are what every runner
 * writes. A sweep driven from the full probe page has to reach this project's command map exactly
 * as one driven from the funnel does, and after a process death neither has any memory left.
 *
 * Canaries and unknown-id rejections are dropped for the same reason
 * [CommandMapBuilder.fromProbeOutcomes] drops them - a canary proves the link, and "no such
 * command id" is evidence of absence, which has no business being offered as something to run.
 *
 * Grouped by characteristic *and* write type, because both are properties of the whole batch in
 * the builder's signature and a project may well have probed one characteristic with responses and
 * another without.
 */
internal fun storedProbeMap(session: CaptureSession): CommandMap? {
    val usable = session.probes.filterNot {
        it.canary || it.verdict == ProbeVerdict.REJECTED_UNKNOWN_ID.name
    }
    if (usable.isEmpty()) return null
    val address = session.device.address
    val maps = usable
        .groupBy { it.characteristicUuid.takeIf(String::isNotBlank) to it.writeType }
        .map { (target, records) ->
            val (characteristic, writeType) = target
            val map = CommandMapBuilder.fromProbeEvidence(
                deviceAddress = address,
                evidence = records.map { record ->
                    ProbeEvidence(
                        opcode = record.opcode,
                        label = record.label,
                        sentHex = record.sentHex,
                        responseHex = record.responseHex,
                        accepted = record.verdict == ProbeVerdict.ACCEPTED.name,
                        effect = record.observedEffect,
                    )
                },
                characteristicUuid = characteristic,
                familyId = session.family?.familyId,
                codecId = session.family?.codecId ?: records.firstOrNull { it.codecId.isNotBlank() }?.codecId,
                writeType = writeType,
            )
            withProjectTestRule(map, records, characteristic)
        }
    return CommandMapBuilder.merge(maps)
}

/**
 * The project's stricter reading of a probed frame.
 *
 * [CommandMapBuilder] calls an accepted frame device-tested because the device itself said yes.
 * SUCCESS proves an opcode exists; it says nothing about what it does, and a clear or a reset
 * answers SUCCESS too. So a project only calls a frame tested once the operator has written down
 * what they watched happen - otherwise it stays a hypothesis, which is what keeps a Home Assistant
 * button off it.
 */
private fun withProjectTestRule(
    map: CommandMap,
    records: List<ProbeRecord>,
    characteristic: String?,
): CommandMap {
    val witnessed = records.asSequence()
        .filter { it.verdict == ProbeVerdict.ACCEPTED.name && it.observedEffect.isNotBlank() }
        .map { commandKey(it.sentHex, characteristic) }
        .toSet()
    return map.copy(
        commands = map.commands.map { command ->
            if (command.stage != EvidenceStage.DEVICE_TESTED) {
                command
            } else if (commandKey(command.payloadHex, command.characteristicUuid) in witnessed) {
                command
            } else {
                command.copy(
                    stage = EvidenceStage.HYPOTHESIS,
                    evidence = command.evidence + "no observed effect was recorded, so this stays a hypothesis",
                )
            }
        },
    )
}

/**
 * A rendered profile draft, and whether it would actually install anything.
 *
 * @param exportable false when nothing in the draft could be installed. Offering Copy or Share
 *   then would be a lie: the operator would paste a profile into the integration and get nothing,
 *   with no clue why.
 * @param blockedReason what is missing, in the renderer's own words.
 * @param skipped commands left out of an otherwise installable profile, one line each with why.
 */
internal class Draft(
    val text: String,
    val exportable: Boolean,
    val blockedReason: String?,
    val skipped: List<String> = emptyList(),
)

/**
 * Renders the draft and takes the renderer's own verdict on whether it is installable.
 *
 * Home Assistant looks a characteristic up inside the service it is told, so a command whose
 * service cannot be established is left out rather than shipped as a button that always fails.
 * Which commands those are is [ProfileDraft]'s judgement and not this layer's, which is why the
 * attribute database is handed over and the answer is read back rather than re-derived.
 */
internal fun draftOf(session: CaptureSession): Draft {
    val map = session.commandMapOf()
    if (map.commands.isEmpty()) {
        return Draft("", false, "The command map is empty: probe the device, or learn from its app.")
    }
    return when (val rendered = ProfileDraft.render(map, session.device, session.gatt)) {
        is ProfileDraftResult.Ready -> Draft(
            text = rendered.json,
            exportable = true,
            blockedReason = null,
            skipped = rendered.skipped,
        )

        is ProfileDraftResult.Unresolvable -> Draft(
            text = "",
            exportable = false,
            blockedReason = "None of the ${map.commands.size} mapped commands can be installed yet. " +
                "Connect and enumerate the device so the service each characteristic lives in is on " +
                "record.",
            skipped = rendered.reasons,
        )
    }
}

// ---------------------------------------------------------------------------
// Projects list
// ---------------------------------------------------------------------------

/** One project as the list shows it: how far down the funnel it has got. */
@Immutable
data class ProjectRow(
    val id: String,
    val name: String,
    val address: String,
    val deviceName: String?,
    val familyName: String?,
    val familyConfidence: String?,
    val probedFrames: Int,
    val commandCount: Int,
    val testedCount: Int,
    val exported: Boolean,
    val learningOpen: Boolean,
    val eventCount: Int,
    val updatedAtEpochMs: Long,
) {
    val identified: Boolean get() = familyName != null
}

@Immutable
data class ProjectCandidate(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val serviceCount: Int,
)

@Immutable
data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectRow> = emptyList(),
    /** Sessions with no device address: captures from before projects existed, still reachable. */
    val loose: List<ProjectRow> = emptyList(),
    val picking: Boolean = false,
    val query: String = "",
    val candidates: List<ProjectCandidate> = emptyList(),
    val scanning: Boolean = false,
    val totalSeen: Int = 0,
    val creating: Boolean = false,
)

/**
 * The catalogue of device projects.
 *
 * A project is not a new kind of file: it is a [CaptureSession] whose device has an address, which
 * is why the Sessions tab folded into this one. Everything the old list showed is still on disk
 * and still reachable - the sessions that never named a device are simply grouped apart.
 */
class ProjectsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Id of a project that was just created, so the list can hand over to it. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    init {
        viewModelScope.launch {
            container.scanner.devices.collect { devices -> _state.update { it.withCandidates(devices.values) } }
        }
        viewModelScope.launch {
            container.scanner.status.collect { status -> _state.update { it.copy(scanning = status.scanning) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sessions = runCatching { container.sessions.list() }
                .getOrElse {
                    _messages.tryEmit("Could not read the projects: ${it.message}")
                    emptyList()
                }
            val rows = withContext(Dispatchers.Default) { sessions.map(::rowOf) }
            _state.update { current ->
                current.copy(
                    loading = false,
                    projects = rows.filter { it.address.isNotBlank() },
                    loose = rows.filter { it.address.isBlank() },
                )
            }
        }
    }

    fun startPicking() {
        _state.update { it.copy(picking = true, query = "") }
        if (!container.scanner.status.value.scanning) container.scanner.start(continuous = true)
    }

    fun cancelPicking() = _state.update { it.copy(picking = false, query = "") }

    fun setQuery(query: String) = _state.update {
        it.copy(query = query).withCandidates(container.scanner.devices.value.values)
    }

    /**
     * Creates the project from what the scan already knows: identity, one advertisement sample and
     * the family the advertisement alone is enough to name.
     *
     * Identifying here rather than on first open is deliberate - it costs nothing, needs no
     * connection, and it is the answer the operator came for.
     */
    fun createProject(address: String) {
        if (_state.value.creating) return
        val advertised = container.scanner.device(address)
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val identity = identityOf(address, advertised)
            val session = CaptureSession(
                name = identity.name?.takeIf { it.isNotBlank() } ?: address,
                device = identity,
                advertisements = listOfNotNull(sampleOf(advertised)),
            )
            val identified = session.copy(
                family = DeviceFingerprint.identify(session.fingerprintInput()).firstOrNull()?.toRecord(),
            )
            val stored = runCatching { container.sessions.save(identified) }
                .getOrElse { error ->
                    _state.update { it.copy(creating = false) }
                    _messages.tryEmit("Could not create the project: ${error.message ?: "write failed"}")
                    return@launch
                }
            _state.update { it.copy(creating = false, picking = false, query = "") }
            refresh()
            _opened.tryEmit(stored.id)
        }
    }

    private fun identityOf(address: String, advertised: ScannedDevice?) = DeviceIdentity(
        address = address,
        name = advertised?.name,
        advertisedServiceUuids = advertised?.serviceUuids.orEmpty(),
        manufacturerData = advertised?.manufacturerData?.mapValues { (_, bytes) -> bytes.toHex() }.orEmpty(),
        serviceData = advertised?.serviceData?.mapValues { (_, bytes) -> bytes.toHex() }.orEmpty(),
        bonded = isBonded(address),
    )

    private fun isBonded(address: String): Boolean {
        if (!container.gattClient.hasConnectPermission()) return false
        val adapter = container.bluetoothManager.adapter ?: return false
        return runCatching { adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }
            .getOrDefault(false)
    }

    private fun sampleOf(advertised: ScannedDevice?): AdvertisementSample? {
        val raw = advertised?.rawRecord ?: return null
        return AdvertisementSample(
            timestampEpochMs = advertised.lastSeenEpochMs,
            rssi = advertised.rssi,
            txPower = advertised.txPower,
            connectable = advertised.connectable,
            primaryPhy = advertised.primaryPhy,
            secondaryPhy = advertised.secondaryPhy,
            bytesHex = raw.toHex(),
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProjectsViewModel(container) }
        }
    }
}

private fun rowOf(session: CaptureSession) = ProjectRow(
    id = session.id,
    name = session.name,
    address = session.device.address,
    deviceName = session.device.name?.takeIf { it.isNotBlank() },
    familyName = session.family?.name,
    familyConfidence = session.family?.confidence,
    probedFrames = session.probes.count { !it.canary },
    commandCount = session.commandMap.size,
    testedCount = session.commandMap.count { it.stage == EvidenceStage.DEVICE_TESTED },
    exported = session.exportedAtEpochMs != null,
    learningOpen = session.learning?.finishedAtEpochMs == null && session.learning != null,
    eventCount = session.events.size,
    updatedAtEpochMs = session.updatedAtEpochMs,
)

private fun ProjectsUiState.withCandidates(devices: Collection<ScannedDevice>): ProjectsUiState {
    val needle = query.trim().lowercase()
    val matching = devices.asSequence()
        .filter { device ->
            needle.isEmpty() ||
                device.address.lowercase().contains(needle) ||
                device.name?.lowercase()?.contains(needle) == true
        }
        .sortedByDescending { it.rssi }
        .map { ProjectCandidate(it.address, it.name, it.rssi, it.connectable, it.serviceUuids.size) }
        .toList()
    return copy(candidates = matching, totalSeen = devices.size)
}

// ---------------------------------------------------------------------------
// One project
// ---------------------------------------------------------------------------

/** The four stages of a device project, in the order the funnel escalates. */
enum class ProjectStage(val title: String) {
    IDENTIFY("Identify"),
    PROBE("Try known commands"),
    LEARN("Learn from the app"),
    EXPORT("Export for Home Assistant"),
}

/** Where a per-command replay stands. Nothing is written before [CONFIRM] is taken. */
enum class TestPhase { CONFIRM, WRITING, ASK_EFFECT, FAILED }

@Immutable
data class CommandTest(
    val commandId: String,
    val name: String,
    val payloadHex: String,
    val characteristicLabel: String,
    val deviceLabel: String,
    val phase: TestPhase,
    val error: String? = null,
)

sealed interface ProjectEffect {
    data class Share(val intent: Intent) : ProjectEffect
    data class Notice(val message: String) : ProjectEffect

    /** Finish decided the log can be pulled: the screen runs `CaptureViewModel.collect()`. */
    data object CollectNow : ProjectEffect
}

@Immutable
data class ProjectUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val id: String = "",
    val name: String = "",
    val device: DeviceIdentity = DeviceIdentity(),
    val gatt: GattDatabase? = null,
    val family: FamilyMatchRecord? = null,
    /** Every family the current evidence matches, best first; the runner-ups are still evidence. */
    val matches: List<FamilyMatch> = emptyList(),
    val commands: List<MappedCommandRecord> = emptyList(),
    val unattributed: List<UnattributedWrite> = emptyList(),
    val probeFrames: Int = 0,
    val probeRuns: Int = 0,
    val probeAccepted: Int = 0,
    val eventCount: Int = 0,
    val markerCount: Int = 0,
    val learning: LearningRecord? = null,
    val learnSource: TrafficSource = TrafficSource.HCI_SNOOP,
    /** The bug-report path is showing because Finish found no privileged shell. */
    val manualCollection: Boolean = false,
    val mergeNote: String = "",
    val draft: String = "",
    /**
     * False when the rendered draft contains no installable command.
     *
     * A command Home Assistant cannot place inside a service is dropped by the renderer, because
     * the integration looks a characteristic up inside the service it is told and a guessed one
     * would ship a button that always fails. So a draft can be non-empty text and still install
     * nothing - which is a refusal to export, not an export.
     */
    val draftExportable: Boolean = false,
    val draftBlockedReason: String? = null,
    /** Commands the renderer left out of an otherwise installable profile, and why. */
    val draftSkipped: List<String> = emptyList(),
    val exportedAtEpochMs: Long? = null,
    val ntfyEnabled: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    /** The stack's own words for why the link failed; never paraphrased. */
    val connectError: String? = null,
    val busy: String? = null,
    val test: CommandTest? = null,
    /**
     * Who holds the one GATT link exclusively right now, or null when it is free.
     *
     * Read from [dev.nphil.blueshark.ble.LinkExclusivity] rather than from the sweep runner this
     * screen happens to host, because the runner that matters may be another instance entirely -
     * the full probe page keeps its own on the back stack, and a sweep survives navigating away
     * from it. A prober verdict is the correlation between a frame and the notification that
     * follows it, so a write from here during any sweep does not merely interleave: it silently
     * reattributes the device's answer to the wrong step.
     */
    val linkHeldBy: String? = null,
    /** The operator's word that HCI logging is on, on builds where nothing can check. */
    val snoopConfirmed: Boolean = false,
) {
    val deviceLabel: String
        get() = device.name?.takeIf { it.isNotBlank() }
            ?: device.alias?.takeIf { it.isNotBlank() }
            ?: device.address.ifBlank { "Unnamed device" }

    val identified: Boolean get() = family != null
    val enumerated: Boolean get() = gatt != null
    val testedCount: Int get() = commands.count { it.stage == EvidenceStage.DEVICE_TESTED }
    val learnedCount: Int get() = commands.count { it.source == CommandMapBuilder.SOURCE_LEARNED }
    val exported: Boolean get() = exportedAtEpochMs != null
    val learningOpen: Boolean get() = learning != null && learning.finishedAtEpochMs == null

    /**
     * Why a write is refused right now, or null when nothing is in the way.
     *
     * [busy] means this project is the holder, which is not a refusal - it is progress.
     */
    val writeBlockedReason: String?
        get() = if (linkHeldBy != null && busy == null) {
            "$linkHeldBy is using the Bluetooth link. Writing now would misattribute its results, " +
                "so this waits until it finishes."
        } else {
            null
        }

    val canWrite: Boolean get() = writeBlockedReason == null && device.address.isNotBlank()

    fun satisfied(stage: ProjectStage): Boolean = when (stage) {
        ProjectStage.IDENTIFY -> identified && enumerated
        ProjectStage.PROBE -> probeAccepted > 0
        ProjectStage.LEARN -> learnedCount > 0
        ProjectStage.EXPORT -> exported
    }
}

/**
 * One device project: the funnel's state, and every write that advances it.
 *
 * This composes rather than reimplements. The probe stage is
 * [dev.nphil.blueshark.ui.probe.ProbeViewModel] hosted by the same screen; collection and import
 * are [dev.nphil.blueshark.ui.capture.CaptureViewModel]; the correlation is
 * [LearnSessionCoordinator]. What lives here is the project itself: which stage is satisfied, what
 * the evidence says, and the two writes nothing else owns - enumerating the device and replaying
 * one mapped command.
 */
class ProjectViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState(id = sessionId))
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProjectEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<ProjectEffect> = _effects.asSharedFlow()

    private var work: Job? = null
    private var foregroundHeld = false

    /**
     * Tokens for the effects the screen fires from a state edge rather than from a tap: folding a
     * finished sweep, and correlating a finished collection.
     *
     * Navigating away and back re-enters the composition, which re-runs those `LaunchedEffect`s
     * with unchanged keys. Both operations are idempotent, so the risk is not corruption - it is
     * a disk write and a snackbar every time the operator returns to the project, which reads as
     * the app doing something it was not asked to do.
     */
    private val handledOnce = HashSet<String>()

    init {
        viewModelScope.launch {
            container.debug.settings.collect { settings -> _state.update { it.copy(ntfyEnabled = settings.ntfyEnabled) } }
        }
        viewModelScope.launch {
            container.gattClient.state.collect { link ->
                val address = _state.value.device.address
                _state.update {
                    it.copy(connected = link is ConnectionState.Connected && link.address == address)
                }
                // The service is refcounted per claim, so this drops exactly the one hold this
                // screen took - a link that dropped on its own must not leave the process pinned
                // in the foreground for the rest of the session.
                when (link) {
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> releaseForeground()
                    is ConnectionState.Connected, is ConnectionState.Connecting -> Unit
                }
            }
        }
        viewModelScope.launch {
            container.linkExclusivity.holder.collect { holder ->
                _state.update { it.copy(linkHeldBy = holder) }
            }
        }
        reload()
    }

    override fun onCleared() {
        work?.cancel()
        releaseForeground()
    }

    // ---- loading --------------------------------------------------------------------------

    fun reload() {
        viewModelScope.launch {
            val session = runCatching { container.sessions.load(sessionId) }.getOrNull()
            if (session == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            apply(session)
        }
    }

    private suspend fun apply(session: CaptureSession) {
        val derived = withContext(Dispatchers.Default) {
            Derived(
                matches = DeviceFingerprint.identify(session.fingerprintInput()),
                draft = draftOf(session),
                runs = session.probes.map { it.runId }.distinct().size,
            )
        }
        _state.update { current ->
            current.copy(
                loading = false,
                missing = false,
                id = session.id,
                name = session.name,
                device = session.device,
                gatt = session.gatt,
                family = session.family,
                matches = derived.matches,
                commands = session.commandMap,
                probeFrames = session.probes.count { !it.canary },
                probeRuns = derived.runs,
                probeAccepted = session.probes.count { !it.canary && it.verdict == ProbeVerdict.ACCEPTED.name },
                eventCount = session.events.size,
                markerCount = session.markers.size,
                learning = session.learning,
                learnSource = session.learning?.let { trafficSourceOf(it.source) } ?: current.learnSource,
                draft = derived.draft.text,
                draftExportable = derived.draft.exportable,
                draftBlockedReason = derived.draft.blockedReason,
                draftSkipped = derived.draft.skipped,
                exportedAtEpochMs = session.exportedAtEpochMs,
                snoopConfirmed = session.snoopConfirmedByOperator,
                connected = current.connected,
            )
        }
    }

    private class Derived(val matches: List<FamilyMatch>, val draft: Draft, val runs: Int)

    // ---- identify -------------------------------------------------------------------------

    /** Re-runs the fingerprint over whatever evidence the project now holds, and keeps the best. */
    fun identify() = exclusive("Identifying") {
        val session = container.sessions.load(sessionId) ?: return@exclusive
        val saved = container.sessions.update(sessionId) { base ->
            base.copy(family = DeviceFingerprint.identify(base.fingerprintInput()).firstOrNull()?.toRecord() ?: base.family)
        }
        apply(saved)
        val best = saved.family
        notice(
            if (best == null) {
                "Nothing in ${session.device.address}'s advertisement matches a family BlueShark knows."
            } else {
                "${best.name} — ${confidenceWord(best.confidence)}"
            },
        )
    }

    /**
     * Connects, enumerates and stores the attribute database, then re-identifies with it.
     *
     * A device already held by another central refuses the link, and the stack's message for that
     * is the operator's only clue, so it is surfaced verbatim next to the one hint that actually
     * resolves it.
     *
     * Discovery is not a write, but establishing and holding a link is not free of consequence
     * either: it is what a sweep's response windows are measured through. So this takes the same
     * exclusive claim a sweep does, which is also what stops a sweep starting underneath it.
     */
    fun connectAndEnumerate() {
        val address = _state.value.device.address
        if (address.isBlank()) {
            notice("This project has no device address.")
            return
        }
        exclusiveLink("Connecting to $address", "Connect & enumerate") {
            _state.update { it.copy(connecting = true, connectError = null) }
            holdForeground(address)
            val database = try {
                container.gattClient.connect(address)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(connecting = false, connectError = error.message ?: error.javaClass.simpleName)
                }
                return@exclusiveLink
            }
            // The attribute database is stored as itself and the fingerprint is given it through
            // FingerprintInput.gatt. What must NOT happen is folding those uuids into
            // device.advertisedServiceUuids: that field is what this device broadcast, a nameless
            // fff0/fff1 module would then look like it advertised a CoolLED layout, and a match
            // certified from fabricated advertisement evidence is worse than no match at all.
            val saved = container.sessions.update(sessionId) { base ->
                val enumerated = base.copy(gatt = database)
                enumerated.copy(
                    family = DeviceFingerprint.identify(enumerated.fingerprintInput()).firstOrNull()?.toRecord()
                        ?: base.family,
                )
            }
            _state.update { it.copy(connecting = false, connectError = null) }
            apply(saved)
            notice(
                "Enumerated ${database.services.size} services, " +
                    "${database.services.sumOf { service -> service.characteristics.size }} characteristics.",
            )
        }
    }

    /**
     * Drops the link.
     *
     * Refused outright while somebody else owns it: tearing a sweep's connection down mid-run is
     * the one interference that cannot be recovered from - every remaining step becomes silence
     * that looks exactly like a nonexistent opcode.
     */
    fun disconnect() {
        val holder = container.linkExclusivity.holder.value
        if (holder != null) {
            notice("$holder owns the link right now — stopping it there is the only safe way to drop it.")
            return
        }
        container.gattClient.disconnect()
        releaseForeground()
    }

    fun clearConnectError() = _state.update { it.copy(connectError = null) }

    // ---- probe ----------------------------------------------------------------------------

    /**
     * Folds every sweep this project has recorded into the command map.
     *
     * Read from the session's own [ProbeRecord]s rather than from a runner's in-memory outcomes.
     * That is not a detail: a sweep driven from the full probe page has to reach this project's
     * command map exactly as one driven from the funnel does, and after a process death neither
     * has any memory left. The records are what every runner writes and what survives, so they
     * are the input.
     *
     * @param runId identity of the sweep that triggered this; a run folds automatically once.
     * @param force fold again, which is what "Update from effects" is for.
     */
    fun foldProbes(runId: String? = null, force: Boolean = false) {
        if (!force && runId != null && !firstTime("probe:$runId")) return
        exclusive("Folding the sweeps into the command map") {
            var folded = 0
            val saved = container.sessions.update(sessionId) { base ->
                val fresh = storedProbeMap(base) ?: return@update base
                folded = fresh.commands.size
                base.copy(commandMap = mergeCommandMap(session = base, fresh = fresh))
            }
            apply(saved)
            if (folded == 0) {
                if (force) notice("No probed frame is worth offering as a command yet.")
            } else {
                notice("$folded probed frames are in the command map.")
            }
        }
    }

    // ---- learn ----------------------------------------------------------------------------

    fun setLearnSource(source: TrafficSource) = _state.update { it.copy(learnSource = source) }

    /**
     * Records the operator's word that HCI logging is on.
     *
     * Persisted on the project because it has to survive navigation, and because it is a claim
     * about this capture - the log it produces will either bear it out or not.
     */
    fun setSnoopConfirmed(confirmed: Boolean) {
        if (_state.value.snoopConfirmed == confirmed) return
        _state.update { it.copy(snoopConfirmed = confirmed) }
        viewModelScope.launch {
            runCatching { container.sessions.update(sessionId) { it.copy(snoopConfirmedByOperator = confirmed) } }
                .onFailure { notice("Could not remember that: ${it.message}") }
        }
    }

    /**
     * Starts the learning session: guide target, durable record, and the marker that stamps the
     * beginning of the session on the same clock as the packets.
     *
     * The marker is a real interaction as far as the correlator is concerned, so the frames the
     * vendor app writes while it connects are attributed to it. That is the app's handshake, which
     * is worth having named as such - and being only `OBSERVED`, it can never install a button.
     */
    fun startLearning(vendorPackage: String?, vendorLabel: String?) {
        val current = _state.value
        container.learning.start(
            sessionId = sessionId,
            source = current.learnSource,
            vendorPackage = vendorPackage,
            vendorLabel = vendorLabel,
            deviceAddress = current.device.address,
            deviceName = current.device.name,
        )
        container.guide.emitManualMarker(LEARN_START_MARKER)
        _state.update { it.copy(manualCollection = false, mergeNote = "") }
    }

    /** Closes the session and asks for whichever collection path the facts allow. */
    fun finishLearning(shizukuReady: Boolean) = exclusive("Finishing the learning session") {
        when (container.learning.finish(sessionId, shizukuReady)) {
            is FinishPlan.Collect -> {
                _state.update { it.copy(manualCollection = false) }
                _effects.tryEmit(ProjectEffect.CollectNow)
            }

            is FinishPlan.Manual -> {
                _state.update { it.copy(manualCollection = true) }
                notice("Take a bug report, then import it here.")
            }

            // A relay run was captured as it passed through, so there is nothing to pull and the
            // correlation can run right now - inline, because this is already the exclusive slot.
            is FinishPlan.AlreadyCaptured -> {
                _state.update { it.copy(manualCollection = false) }
                mergeNow()
            }

            FinishPlan.NotRunning -> notice("No learning session is running.")
        }
        reloadNow()
    }

    /**
     * Correlates whatever the project now holds; safe to run again after a second import.
     *
     * @param collectionStamp when set, the correlation runs once for that collection and is
     *   skipped on every later re-entry of the screen. The operator's own "Correlate again" passes
     *   nothing and therefore always runs.
     */
    fun mergeLearning(collectionStamp: Long? = null) {
        if (collectionStamp != null && !firstTime("collect:$collectionStamp")) return
        exclusive("Correlating taps with writes") { mergeNow() }
    }

    private suspend fun mergeNow() {
        val result = container.learning.merge(sessionId)
        _state.update {
            it.copy(
                unattributed = result.unattributed,
                mergeNote = result.note,
                manualCollection = false,
            )
        }
        reloadNow()
        notice(
            if (result.learned > 0) {
                "Learned ${result.learned} frames from ${result.attributions} interactions."
            } else {
                result.note
            },
        )
    }

    // ---- replay one command ---------------------------------------------------------------

    fun requestTest(commandId: String) {
        val current = _state.value
        val command = current.commands.firstOrNull { it.id == commandId } ?: return
        _state.update {
            it.copy(
                test = CommandTest(
                    commandId = commandId,
                    name = command.name,
                    payloadHex = command.payloadHex,
                    characteristicLabel = command.characteristicUuid?.let(::shortUuid) ?: "an unrecorded characteristic",
                    deviceLabel = current.deviceLabel,
                    phase = TestPhase.CONFIRM,
                ),
            )
        }
    }

    fun dismissTest() = _state.update { it.copy(test = null) }

    /**
     * Writes one mapped command to the device.
     *
     * The characteristic is re-resolved against the database this connection just handed us:
     * instance ids come from ATT handles and a reconnect may hand out different ones, so the ref
     * stored with the command cannot be reused verbatim.
     */
    fun confirmTest() {
        val test = _state.value.test ?: return
        val address = _state.value.device.address
        val command = _state.value.commands.firstOrNull { it.id == test.commandId } ?: return
        exclusiveLink("Writing ${command.payloadHex}", "A command replay") {
            _state.update { it.copy(test = test.copy(phase = TestPhase.WRITING, error = null)) }
            holdForeground(address)
            try {
                val database = container.gattClient.connect(address)
                val target = resolveWriteTarget(database, command.characteristicUuid)
                    ?: throw IllegalStateException(
                        "${test.characteristicLabel} is not a writable characteristic of the database this " +
                            "device just handed us.",
                    )
                val bytes = command.payloadHex.hexToBytes()
                container.gattClient.writeCharacteristic(target.first, bytes, target.second)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        test = test.copy(
                            phase = TestPhase.FAILED,
                            error = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
                return@exclusiveLink
            }
            _state.update { it.copy(test = test.copy(phase = TestPhase.ASK_EFFECT, error = null)) }
        }
    }

    /**
     * Records what the operator saw after the replay.
     *
     * A visible effect is the only thing that promotes a command to [EvidenceStage.DEVICE_TESTED]:
     * the write succeeding proves the link worked, not that the frame means anything.
     */
    fun recordEffect(worked: Boolean) {
        val test = _state.value.test ?: return
        _state.update { it.copy(test = null) }
        exclusive("Recording what happened") {
            val line = if (worked) {
                "replayed ${test.payloadHex} from BlueShark; the operator saw the device react"
            } else {
                "replayed ${test.payloadHex} from BlueShark; nothing observable happened"
            }
            val saved = container.sessions.update(sessionId) { base ->
                base.copy(
                    commandMap = base.commandMap.map { record ->
                        if (record.id != test.commandId) {
                            record
                        } else {
                            record.copy(
                                stage = if (worked) EvidenceStage.DEVICE_TESTED else record.stage,
                                evidence = (record.evidence + line).distinct(),
                            )
                        }
                    },
                )
            }
            apply(saved)
            notice(if (worked) "\"${test.name}\" is now device-tested." else "Noted: no visible effect.")
        }
    }

    // ---- export ---------------------------------------------------------------------------

    /**
     * The draft, only when exporting it would actually install something.
     *
     * The one gate every export path goes through, so "exported" can never be stamped on a
     * project whose profile installs nothing.
     */
    private fun exportableDraft(): String? {
        val current = _state.value
        if (current.draftExportable && current.draft.isNotBlank()) return current.draft
        notice(current.draftBlockedReason ?: "There is nothing installable to export yet.")
        return null
    }

    fun copyDraft() {
        val draft = exportableDraft() ?: return
        container.appContext.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(DRAFT_CLIP_LABEL, draft))
        markExported()
        notice("Profile draft copied (${draft.length} chars)")
    }

    fun shareDraft() = exclusive("Writing the profile draft") {
        val draft = exportableDraft() ?: return@exclusive
        val session = container.sessions.load(sessionId) ?: return@exclusive
        runCatching { container.exports.exportProfileDraft(session, draft) }
            .onSuccess { export ->
                markExported()
                _effects.tryEmit(ProjectEffect.Share(container.exports.shareIntent(export)))
            }
            .onFailure { notice("Could not write the draft: ${it.message}") }
    }

    fun sendDraft() = exclusive("Publishing the profile draft") {
        val draft = exportableDraft() ?: return@exclusive
        if (!_state.value.ntfyEnabled) {
            notice("The ntfy sink is off — switch it on in Settings.")
            return@exclusive
        }
        notice(container.debug.sendNow("BlueShark profile ${_state.value.deviceLabel}", draft))
        markExported()
    }

    fun shareEvidence() = exclusive("Writing the evidence bundle") {
        val session = container.sessions.load(sessionId) ?: return@exclusive
        runCatching { container.exports.exportEvidenceBundle(session) }
            .onSuccess { export -> _effects.tryEmit(ProjectEffect.Share(container.exports.shareIntent(export))) }
            .onFailure { notice("Could not write the bundle: ${it.message}") }
    }

    private fun markExported() {
        val now = System.currentTimeMillis()
        _state.update { it.copy(exportedAtEpochMs = now) }
        viewModelScope.launch {
            runCatching { container.sessions.update(sessionId) { it.copy(exportedAtEpochMs = now) } }
        }
    }

    // ---- plumbing -------------------------------------------------------------------------

    private suspend fun reloadNow() {
        val session = runCatching { container.sessions.load(sessionId) }.getOrNull() ?: return
        apply(session)
    }

    private fun resolveWriteTarget(
        database: GattDatabase,
        characteristicUuid: String?,
    ): Pair<CharacteristicRef, WriteType>? {
        val wanted = HaProfileBuilder.canonicalUuid(characteristicUuid) ?: return null
        for (service in database.services) {
            for (characteristic in service.characteristics) {
                if (HaProfileBuilder.canonicalUuid(characteristic.uuid) != wanted) continue
                val noResponse = "WRITE_NO_RESPONSE" in characteristic.properties
                if (!noResponse && "WRITE" !in characteristic.properties) continue
                return CharacteristicRef(
                    serviceUuid = service.uuid,
                    serviceInstanceId = service.instanceId,
                    uuid = characteristic.uuid,
                    instanceId = characteristic.instanceId,
                ) to if (noResponse) WriteType.WITHOUT_RESPONSE else WriteType.WITH_RESPONSE
            }
        }
        return null
    }

    private fun notice(message: String) {
        _effects.tryEmit(ProjectEffect.Notice(message))
    }

    /** True the first time [token] is seen; see [handledOnce]. */
    private fun firstTime(token: String): Boolean = handledOnce.add(token)

    /** One long operation at a time: they all write the same session file. */
    private fun exclusive(label: String, block: suspend () -> Unit) {
        if (work?.isActive == true) {
            notice("Another step is still running.")
            return
        }
        _state.update { it.copy(busy = label) }
        work = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                notice("$label failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                work = null
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * [exclusive], plus exclusive ownership of the shared GATT link for the whole operation.
     *
     * The claim is the guard, not [ProjectUiState.linkHeldBy]: a disabled button is a courtesy to
     * the operator, while a compare-and-set is what actually keeps this project's write out of
     * somebody else's sweep - including a sweep owned by a view model on another back-stack entry
     * that this screen cannot see. Failing the claim is not an error, it is a "not yet".
     *
     * Its lifetime is bound with [releaseWhenComplete] rather than released in the job's own
     * `finally`: both this screen and the sweep runner wrote the `finally` version first and both
     * were wrong the same way, which is why that reasoning lives in one tested function now
     * instead of being an idiom each caller has to get right.
     *
     * @param reason how this operation names itself to whoever it turns away.
     */
    private fun exclusiveLink(label: String, reason: String, block: suspend () -> Unit) {
        if (work?.isActive == true) {
            notice("Another step is still running.")
            return
        }
        val claim = container.linkExclusivity.claim(reason)
        if (claim == null) {
            notice(
                "${container.linkExclusivity.holder.value ?: "Something else"} is using the Bluetooth " +
                    "link. Writing now would misattribute its results — try again when it finishes.",
            )
            return
        }
        _state.update { it.copy(busy = label) }
        val job = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                notice("$label failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        work = job
        container.linkExclusivity.releaseWhenComplete(claim, job)
        // This screen's own bookkeeping, on the same completion for the same reason: a job whose
        // body never ran must not leave the card showing progress forever.
        job.invokeOnCompletion {
            if (work === job) work = null
            _state.update { if (it.busy == label) it.copy(busy = null) else it }
        }
    }

    /**
     * Keeps the process in the foreground for the duration of the link, claiming the mode rather
     * than starting the service outright: a relay run may already own it.
     */
    private fun holdForeground(address: String) {
        val started = runCatching {
            BleForegroundService.start(container.appContext, BleForegroundService.MODE_GATT, address)
        }
        if (started.isSuccess) {
            foregroundHeld = true
        } else if (!foregroundHeld) {
            notice("The link will drop if you leave the app: the foreground service could not start.")
        }
    }

    private fun releaseForeground() {
        if (!foregroundHeld) return
        foregroundHeld = false
        runCatching { BleForegroundService.release(container.appContext, BleForegroundService.MODE_GATT) }
    }

    companion object {
        /**
         * Marker dropped when a learning session begins; the counterpart of
         * [dev.nphil.blueshark.guide.GuideController.SESSION_FINISHED_MARKER].
         */
        const val LEARN_START_MARKER = "session started"

        private const val DRAFT_CLIP_LABEL = "BlueShark Home Assistant profile draft"

        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProjectViewModel(container, sessionId) }
        }
    }
}
