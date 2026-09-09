package dev.nphil.blueshark.ui.project

import dev.nphil.blueshark.data.SessionStore
import dev.nphil.blueshark.debug.DebugLog
import dev.nphil.blueshark.guide.GuideController
import dev.nphil.blueshark.learn.Attribution
import dev.nphil.blueshark.learn.CommandMapBuilder
import dev.nphil.blueshark.learn.TapCorrelator
import dev.nphil.blueshark.learn.TrafficSource
import dev.nphil.blueshark.model.LearningRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a learning session stands, as far as the whole process is concerned. */
enum class LearnPhase {
    IDLE,

    /** The operator is in the vendor app; taps are arriving as markers. */
    RUNNING,

    /** The session is closed and its traffic is being collected or imported. */
    COLLECTING,

    /** Collected: markers and writes are being correlated into the command map. */
    MERGING,
}

/**
 * What Finish has to do next, decided once and by the facts rather than by whichever screen asked.
 *
 * The split exists because collection is not this class's job: the privileged pull and the file
 * import both live in [dev.nphil.blueshark.ui.capture.CaptureViewModel], which already knows how
 * to parse a btsnoop, merge rotations and ingest without losing another writer's records. This
 * decides *which* of those the operator is owed and hands the caller a session id to run it on.
 */
sealed interface FinishPlan {
    /** Shizuku is ready: run `CaptureViewModel.collect()` and merge when it reports DONE. */
    data class Collect(val sessionId: String) : FinishPlan

    /** No privileged shell: the operator takes a bug report and imports the zip. */
    data class Manual(val sessionId: String) : FinishPlan

    /** A relay run was already captured live; there is nothing to pull. */
    data class AlreadyCaptured(val sessionId: String) : FinishPlan

    data object NotRunning : FinishPlan
}

/** One write the correlator could not blame on any interaction. */
data class UnattributedWrite(
    val payloadHex: String,
    val characteristicUuid: String?,
    val count: Int,
)

/** What one correlate-and-merge pass produced. */
data class LearnMergeResult(
    /** Commands in the session's map afterwards. */
    val commands: Int,

    /** Commands this pass learned from taps. */
    val learned: Int,

    /** Interactions the correlator could blame writes on. */
    val attributions: Int,
    val unattributed: List<UnattributedWrite>,
    val note: String,
)

/**
 * Live state of the one learning session this process can have.
 *
 * @param finishRequestedFor the project the overlay's Finish button asked us to close. Set from
 *   the intent extra, consumed by whichever screen routes to that project - so the request
 *   survives the navigation it triggers.
 */
data class LearnSessionState(
    val sessionId: String? = null,
    val source: TrafficSource = TrafficSource.HCI_SNOOP,
    val startedAtEpochMs: Long = 0L,
    val vendorPackage: String? = null,
    val vendorLabel: String? = null,
    val phase: LearnPhase = LearnPhase.IDLE,
    val finishRequestedFor: String? = null,
) {
    val running: Boolean get() = phase == LearnPhase.RUNNING
}

/**
 * Owns the learning session across screens, activities and cold starts.
 *
 * It lives in [dev.nphil.blueshark.AppContainer] rather than in a ViewModel for one reason: the
 * operator leaves BlueShark entirely for the duration. The overlay's Finish button brings the app
 * back with [GuideController.EXTRA_FINISH_LEARNING] on the intent, and by then the ViewModel that
 * started the session may be long gone - or the process itself may have been killed and restarted.
 * So the session's identity is kept both here and, durably, in the project's own
 * [LearningRecord]: an unfinished record on disk is what lets [requestFinish] find the right
 * project after a cold start instead of asking the operator which one they meant.
 */
class LearnSessionCoordinator(
    private val sessions: SessionStore,
    private val guide: GuideController,
    private val debug: DebugLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(LearnSessionState())
    val state: StateFlow<LearnSessionState> = _state.asStateFlow()

    /**
     * Points the guide at the vendor app and the device, and records the session on the project.
     *
     * The guide is wired synchronously, before the caller launches the app: a marker that arrives
     * while the target is still unset belongs to nobody. Passing null first is deliberate -
     * `setTarget` short-circuits when the package has not changed, and re-learning the same app
     * has to reset the tap counter and the session clock rather than continue the previous run.
     */
    fun start(
        sessionId: String,
        source: TrafficSource,
        vendorPackage: String?,
        vendorLabel: String?,
        deviceAddress: String,
        deviceName: String?,
    ) {
        val startedAt = clock()
        guide.setTarget(null)
        if (vendorPackage != null) guide.setTarget(vendorPackage, vendorLabel)
        guide.setTargetDevice(deviceAddress.takeIf { it.isNotBlank() }, deviceName)
        _state.value = LearnSessionState(
            sessionId = sessionId,
            source = source,
            startedAtEpochMs = startedAt,
            vendorPackage = vendorPackage,
            vendorLabel = vendorLabel,
            phase = LearnPhase.RUNNING,
        )
        val record = LearningRecord(
            source = source.name,
            startedAtEpochMs = startedAt,
            vendorPackage = vendorPackage,
            vendorLabel = vendorLabel,
        )
        scope.launch {
            runCatching { sessions.update(sessionId) { it.copy(learning = record) } }
                .onFailure { debug.log("learn", "could not record the session start: ${it.message}") }
        }
        debug.log("learn", "session started on $sessionId via ${source.name} (${vendorPackage ?: "no app"})")
    }

    /**
     * The overlay pressed Finish, or the operator did from the Learn card.
     *
     * Only a project with an *open* [LearningRecord] can be finished. That is the whole guard: an
     * intent extra is an untrusted trigger - it can arrive twice, arrive after the session was
     * already closed from the card, or arrive on a cold start with nothing running at all - and
     * the answer to all three is the same, because a record with a finish stamp is not open. In
     * particular nothing here invents a record: a Finish with no session behind it is ignored and
     * logged, never turned into a session that never ran.
     */
    fun requestFinish() {
        scope.launch {
            val known = _state.value.sessionId
            val target = if (known != null && isOpen(known)) known else newestOpenProject()
            if (target == null) {
                debug.log("learn", "Finish ignored: no project has an open learning session")
                return@launch
            }
            val record = runCatching { sessions.load(target) }.getOrNull()?.learning ?: return@launch
            _state.value = LearnSessionState(
                sessionId = target,
                source = trafficSourceOf(record.source),
                startedAtEpochMs = record.startedAtEpochMs,
                vendorPackage = record.vendorPackage,
                vendorLabel = record.vendorLabel,
                phase = LearnPhase.RUNNING,
                finishRequestedFor = target,
            )
            debug.log("learn", "Finish accepted for project $target")
        }
    }

    /** Taken by the screen that routed to the project, so the request fires exactly once. */
    fun consumeFinishRequest() = _state.update { it.copy(finishRequestedFor = null) }

    /**
     * Closes the session and says how its traffic will be read.
     *
     * Refused unless the project's [LearningRecord] is open, for the reasons on [requestFinish] -
     * and it will not write one either, so a Finish for a project that never started a session
     * cannot fabricate the evidence that it did.
     *
     * The guide is stood down first: [GuideController.finishSession] drops the final
     * "session finished" marker, which is what bounds the last tap's attribution window. When the
     * overlay already pressed Finish that call is a no-op, so this is safe from either entry point.
     *
     * [LearnSessionState.sessionId] deliberately survives: a checkable or slider tap settles
     * 150-500 ms after the touch, so the observer's marker for the last control the operator drove
     * arrives *after* Finish, and it still has to find the session it belongs to. The phase and
     * the record's finish stamp are what say the session is closed - never a null id.
     */
    suspend fun finish(sessionId: String, shizukuReady: Boolean): FinishPlan {
        val record = runCatching { sessions.load(sessionId) }.getOrNull()?.learning
        if (record == null || record.finishedAtEpochMs != null) {
            debug.log(
                "learn",
                "Finish ignored for $sessionId: " +
                    if (record == null) "no learning record" else "already finished",
            )
            _state.update { if (it.sessionId == sessionId) it.copy(finishRequestedFor = null) else it }
            return FinishPlan.NotRunning
        }
        val source = trafficSourceOf(record.source)
        guide.finishSession()
        guide.setTargetDevice(null)
        val finishedAt = clock()
        runCatching {
            sessions.update(sessionId) { base ->
                val open = base.learning ?: return@update base
                base.copy(learning = open.copy(finishedAtEpochMs = open.finishedAtEpochMs ?: finishedAt))
            }
        }.onFailure { debug.log("learn", "could not record the session end: ${it.message}") }
        _state.update {
            if (it.sessionId == sessionId) it.copy(phase = LearnPhase.COLLECTING, finishRequestedFor = null) else it
        }
        return when {
            source == TrafficSource.RELAY -> FinishPlan.AlreadyCaptured(sessionId)
            shizukuReady -> FinishPlan.Collect(sessionId)
            else -> FinishPlan.Manual(sessionId)
        }
    }

    private suspend fun isOpen(sessionId: String): Boolean {
        val record = runCatching { sessions.load(sessionId) }.getOrNull()?.learning ?: return false
        return record.finishedAtEpochMs == null
    }

    private suspend fun newestOpenProject(): String? = runCatching { sessions.list() }
        .getOrDefault(emptyList())
        .filter { it.learning != null && it.learning.finishedAtEpochMs == null }
        .maxByOrNull { it.learning?.startedAtEpochMs ?: 0L }
        ?.id

    /**
     * Blames the session's writes on the taps that preceded them and folds the result into the
     * project's command map.
     *
     * Everything here is a fold over what is already on disk, so it is idempotent: running it
     * again after a second import re-derives the same attributions and merges to the same map,
     * because a command's identity is its bytes on its characteristic.
     */
    suspend fun merge(sessionId: String): LearnMergeResult {
        _state.update { if (it.sessionId == sessionId) it.copy(phase = LearnPhase.MERGING) else it }
        try {
            val session = sessions.load(sessionId)
                ?: return LearnMergeResult(0, 0, 0, emptyList(), "That project is no longer on disk.")
            // An HCI log holds every link the phone had open, so without the target address a
            // write to the operator's watch could be blamed on a tap in the vendor app.
            val attributions = TapCorrelator.correlate(
                markers = session.markers,
                events = session.events,
                targetAddress = session.device.address,
            )
            val attributed = attributions.filter { it.markerId.isNotEmpty() }
            val orphans = attributions.filter { it.markerId.isEmpty() }
            val fresh = CommandMapBuilder.fromAttributions(
                deviceAddress = session.device.address,
                attributions = attributed,
                familyId = session.family?.familyId,
                codecId = session.family?.codecId,
            )
            val saved = sessions.update(sessionId) { base ->
                base.copy(
                    commandMap = mergeCommandMap(
                        session = base,
                        fresh = fresh,
                        confidences = confidenceByCommand(attributed),
                    ),
                )
            }
            val note = when {
                session.events.isEmpty() ->
                    "No traffic in this project yet: the writes are in the capture, so collect or import it first."

                attributed.isEmpty() ->
                    "${session.events.size} events, but no write follows any marked interaction - " +
                        "either the observer was off, or the app was already connected and wrote before you tapped."

                else -> "${attributed.size} interactions explained ${fresh.commands.size} frames."
            }
            return LearnMergeResult(
                commands = saved.commandMap.size,
                learned = fresh.commands.size,
                attributions = attributed.size,
                unattributed = unattributedWrites(orphans),
                note = note,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return LearnMergeResult(0, 0, 0, emptyList(), error.message ?: "Correlation failed.")
        } finally {
            _state.update { if (it.sessionId == sessionId) it.copy(phase = LearnPhase.IDLE) else it }
        }
    }
}

/** Names on disk are strings so the file survives a rename; an unreadable one falls back to snoop. */
internal fun trafficSourceOf(name: String?): TrafficSource =
    TrafficSource.entries.firstOrNull { it.name == name } ?: TrafficSource.HCI_SNOOP

/**
 * The strongest confidence any interaction claimed for each frame.
 *
 * A frame two taps both wrote is worth the better of the two attributions, not the last one seen:
 * merging never lowers the evidence behind a command.
 */
internal fun confidenceByCommand(attributions: List<Attribution>): Map<String, Double> {
    val out = HashMap<String, Double>(attributions.size * 2)
    for (attribution in attributions) {
        for (payload in attribution.payloads) {
            val key = commandKey(payload, attribution.characteristicUuid)
            val previous = out[key]
            if (previous == null || attribution.confidence > previous) out[key] = attribution.confidence
        }
    }
    return out
}

/** The unattributed bucket, folded per payload so a frame the app repeats reads as one row. */
internal fun unattributedWrites(orphans: List<Attribution>): List<UnattributedWrite> {
    val counts = LinkedHashMap<Pair<String, String?>, Int>()
    for (orphan in orphans) {
        for (payload in orphan.payloads) {
            val key = payload to orphan.characteristicUuid
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts.map { (key, count) -> UnattributedWrite(key.first, key.second, count) }
}
