package dev.nphil.blestudio.ui.sessions

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nphil.blestudio.AppContainer
import dev.nphil.blestudio.data.MissingSessionException
import dev.nphil.blestudio.export.CommandAnalyzer
import dev.nphil.blestudio.export.EligibilityReport
import dev.nphil.blestudio.export.ExportService
import dev.nphil.blestudio.export.HaProfileBuilder
import dev.nphil.blestudio.export.ProfileNotExportableException
import dev.nphil.blestudio.export.SuggestedCommand
import dev.nphil.blestudio.crypto.CipherPreset
import dev.nphil.blestudio.crypto.HandshakeRule
import dev.nphil.blestudio.crypto.KeyTools
import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.CipherScheme
import dev.nphil.blestudio.model.CommandSpec
import dev.nphil.blestudio.model.ConnectionFacts
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EvidenceStage
import dev.nphil.blestudio.model.KeyDerivation
import dev.nphil.blestudio.model.ProtocolModel
import dev.nphil.blestudio.model.WriteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class SessionTab(val label: String) {
    TIMELINE("Timeline"),
    COMMANDS("Commands"),
    COMPARE("Compare"),
    DECRYPT("Decrypt"),
    DEVICE("Device"),
    EXPORT("Export"),
}

/** Home Assistant platforms a catalogued command can plausibly drive. */
val HA_ENTITY_KINDS = listOf("button", "switch", "light", "number", "select", "sensor")

data class SessionSummary(
    val id: String,
    val name: String,
    val deviceLabel: String,
    val eventCount: Int,
    val commandCount: Int,
    val testedCommandCount: Int,
    val updatedAtEpochMs: Long,
)

data class TimelineFilter(
    val directions: Set<EventDirection> = emptySet(),
    val operations: Set<AttOperation> = emptySet(),
    val channels: Set<String> = emptySet(),
    val markerIds: Set<String> = emptySet(),
    val query: String = "",
) {
    val activeCount: Int
        get() = directions.size + operations.size + channels.size + markerIds.size +
            if (query.isBlank()) 0 else 1
}

/** Filter values that actually occur in the selected session, so no chip is a dead end. */
data class TimelineFacets(
    val directions: List<EventDirection> = emptyList(),
    val operations: List<AttOperation> = emptyList(),
    val channels: List<String> = emptyList(),
)

data class SessionsUiState(
    val loading: Boolean = true,
    val sessions: List<SessionSummary> = emptyList(),
    val selected: CaptureSession? = null,
    val tab: SessionTab = SessionTab.TIMELINE,
    val filter: TimelineFilter = TimelineFilter(),
    val facets: TimelineFacets = TimelineFacets(),
    val timeline: List<BleEvent> = emptyList(),
    val analyzing: Boolean = false,
    val suggestions: List<SuggestedCommand> = emptyList(),
    val eligibility: EligibilityReport? = null,
    val inspecting: BleEvent? = null,
    val compareChannel: String? = null,
    val compareCandidates: List<BleEvent> = emptyList(),
    val compareSelection: List<BleEvent> = emptyList(),
    val pendingDelete: SessionSummary? = null,
    val renameTarget: SessionSummary? = null,
    val creating: Boolean = false,
    val exporting: Boolean = false,
    /** Decryption is a view, so which views it reaches is UI state and is never persisted. */
    val applyDecryption: Boolean = true,
    val compareDecrypted: Boolean = false,
    val includeSecretsOnExport: Boolean = false,
    val editingCipherId: String? = null,
    val tryEventId: String? = null,
)

sealed interface SessionEffect {
    data class Share(val intent: Intent) : SessionEffect
    data class Notice(val message: String) : SessionEffect
}

/**
 * One session edit awaiting the writer: what the screen now shows, and how to reproduce the change
 * on whatever is on disk when the write finally happens.
 *
 * The delta matters as much as the snapshot: the scan, capture and relay screens append records to
 * the same file, so publishing this screen's whole object would delete everything they added since
 * it was read. Applying [delta] instead touches only the fields the operator actually edited.
 */
private class PendingEdit(
    val snapshot: CaptureSession,
    val delta: (CaptureSession) -> CaptureSession,
)

/**
 * Owns the session catalogue and every edit made to the selected session.
 *
 * Persistence is fire-and-forget but lossless: an edit updates the in-memory session
 * immediately, parks the newest snapshot and its delta per session id and wakes a single writer.
 * Bursts of keystrokes therefore collapse into as many writes as the disk can absorb, never more,
 * and the write applies the delta to the current file rather than replacing it. Derived work
 * (filtering, analysis, eligibility) runs on [Dispatchers.Default] and is cancelled whenever its
 * inputs change.
 */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {
    private val exports = ExportService(container.appContext, container.sessions)
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<SessionEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val unsaved = ConcurrentHashMap<String, PendingEdit>()
    private val deletedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastWriteAtMs = 0L
    private var deriveJob: Job? = null

    init {
        viewModelScope.launch {
            for (signal in saveSignal) {
                // The first edit is written at once; a burst of keystrokes is spaced out so a
                // large session is not re-encoded on every character.
                val sinceLastWrite = System.currentTimeMillis() - lastWriteAtMs
                if (sinceLastWrite < MIN_WRITE_SPACING_MS) delay(MIN_WRITE_SPACING_MS - sinceLastWrite)
                drainUnsaved()
                lastWriteAtMs = System.currentTimeMillis()
            }
        }
        viewModelScope.launch { runCatching { exports.pruneOldExports() } }
    }

    /**
     * Re-reads the catalogue, and the selected session, from disk. Called whenever the screen
     * resumes: another screen may have appended traffic to the same session while this one was
     * off-screen. In-memory edits that have not been flushed yet always win.
     */
    fun reload() {
        refresh()
        val selectedId = _state.value.selected?.id ?: return
        if (unsaved.containsKey(selectedId)) return
        viewModelScope.launch {
            val fresh = runCatching { container.sessions.load(selectedId) }.getOrNull() ?: return@launch
            if (_state.value.selected?.id != fresh.id || unsaved.containsKey(fresh.id)) return@launch
            _state.update { current ->
                if (current.selected?.id == fresh.id) current.copy(selected = fresh) else current
            }
            derive(fresh)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val summaries = runCatching { container.sessions.list() }
                .getOrElse {
                    notice("Could not read sessions: ${it.message}")
                    emptyList()
                }
                .map(::summarize)
            _state.update { current ->
                current.copy(
                    loading = false,
                    sessions = summaries,
                    selected = current.selected?.takeIf { session -> summaries.any { it.id == session.id } },
                )
            }
        }
    }

    fun select(sessionId: String?) {
        if (sessionId == null) {
            _state.update { it.copy(selected = null, inspecting = null) }
            derive(null)
            return
        }
        if (_state.value.selected?.id == sessionId) return
        viewModelScope.launch {
            val session = unsaved[sessionId]?.snapshot
                ?: runCatching { container.sessions.load(sessionId) }.getOrNull()
            if (session == null) {
                notice("That session is no longer on disk")
                refresh()
                return@launch
            }
            _state.update {
                it.copy(
                    selected = session,
                    inspecting = null,
                    filter = TimelineFilter(),
                    compareChannel = null,
                    compareCandidates = emptyList(),
                    compareSelection = emptyList(),
                    editingCipherId = null,
                    tryEventId = null,
                )
            }
            derive(session)
        }
    }

    fun setTab(tab: SessionTab) = _state.update { it.copy(tab = tab) }

    fun startCreate() = _state.update { it.copy(creating = true) }

    fun cancelCreate() = _state.update { it.copy(creating = false) }

    fun create(name: String) {
        val trimmed = name.trim().ifEmpty { "Capture ${formatDateTime(System.currentTimeMillis())}" }
        viewModelScope.launch {
            _state.update { it.copy(creating = false) }
            val created = runCatching { container.sessions.save(CaptureSession(name = trimmed)) }
                .getOrElse {
                    notice("Could not create the session: ${it.message}")
                    return@launch
                }
            _state.update { current ->
                current.copy(
                    sessions = (listOf(summarize(created)) + current.sessions).sortedByDescending { it.updatedAtEpochMs },
                    selected = created,
                    tab = SessionTab.TIMELINE,
                    filter = TimelineFilter(),
                    compareChannel = null,
                    compareCandidates = emptyList(),
                    compareSelection = emptyList(),
                    inspecting = null,
                )
            }
            derive(created)
        }
    }

    fun startRename(summary: SessionSummary) = _state.update { it.copy(renameTarget = summary) }

    fun cancelRename() = _state.update { it.copy(renameTarget = null) }

    fun rename(sessionId: String, name: String) {
        val trimmed = name.trim()
        _state.update { it.copy(renameTarget = null) }
        if (trimmed.isEmpty()) {
            notice("A session needs a name")
            return
        }
        val selected = _state.value.selected
        if (selected?.id == sessionId) {
            mutate { it.copy(name = trimmed) }
            return
        }
        // An edit may still be queued for this session; patch that too, or the queued write would
        // put the old name straight back.
        val queued = unsaved[sessionId]
        if (queued != null) {
            val renamed = queue(sessionId, queued.snapshot.copy(name = trimmed)) { it.copy(name = trimmed) }
            _state.update { current ->
                current.copy(
                    sessions = current.sessions.map { if (it.id == sessionId) summarize(renamed) else it },
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching { container.sessions.update(sessionId) { it.copy(name = trimmed) } }
                .onSuccess { saved -> replaceSummary(saved) }
                .onFailure { failure ->
                    if (failure is MissingSessionException) {
                        notice("That session is no longer on disk")
                        refresh()
                    } else {
                        notice("Could not rename \"$trimmed\": ${failure.message}")
                    }
                }
        }
    }

    fun requestDelete(summary: SessionSummary) = _state.update { it.copy(pendingDelete = summary) }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            _state.update { it.copy(pendingDelete = null) }
            // Latch the id before deleting: a write already draining must not resurrect the file.
            deletedIds.add(target.id)
            unsaved.remove(target.id)
            val deleted = runCatching { container.sessions.delete(target.id) }.getOrDefault(false)
            if (!deleted) notice("Could not delete \"${target.name}\"")
            val wasSelected = _state.value.selected?.id == target.id
            _state.update { current ->
                current.copy(
                    sessions = current.sessions.filterNot { it.id == target.id },
                    selected = current.selected?.takeIf { it.id != target.id },
                    inspecting = if (wasSelected) null else current.inspecting,
                )
            }
            if (wasSelected) derive(null)
            notice("Deleted \"${target.name}\"")
        }
    }

    fun updateFilter(filter: TimelineFilter) {
        _state.update { it.copy(filter = filter) }
        derive(_state.value.selected)
    }

    fun clearFilter() = updateFilter(TimelineFilter())

    fun inspect(event: BleEvent?) = _state.update { it.copy(inspecting = event) }

    fun setCompareChannel(channel: String?) {
        _state.update { it.copy(compareChannel = channel, compareSelection = emptyList()) }
        derive(_state.value.selected)
    }

    fun toggleCompareSelection(event: BleEvent) {
        var rejected = false
        _state.update { current ->
            val selection = current.compareSelection
            when {
                selection.any { it.id == event.id } -> {
                    rejected = false
                    current.copy(compareSelection = selection.filterNot { it.id == event.id })
                }

                selection.size >= MAX_COMPARE -> {
                    rejected = true
                    current
                }

                else -> {
                    rejected = false
                    current.copy(compareSelection = selection + event)
                }
            }
        }
        if (rejected) notice("Compare holds at most $MAX_COMPARE payloads")
    }

    fun clearCompareSelection() = _state.update { it.copy(compareSelection = emptyList()) }

    fun promote(suggestion: SuggestedCommand) {
        val session = _state.value.selected ?: return
        val service = suggestion.serviceUuid
            ?: CommandAnalyzer.resolveServiceUuid(session, suggestion.characteristicUuid)
            ?: ""
        val evidence = buildString {
            append("Observed ${suggestion.count}× from ${formatClockMicros(suggestion.firstSeenEpochMicros)}")
            if (suggestion.lastSeenEpochMicros != suggestion.firstSeenEpochMicros) {
                append(" to ${formatClockMicros(suggestion.lastSeenEpochMicros)}")
            }
            suggestion.nearestMarkerLabel?.let { append("; marker \"$it\"") }
            suggestion.observedLatenciesMs.minOrNull()?.let { fastest ->
                append("; response in ${fastest}–${suggestion.observedLatenciesMs.max()} ms")
            }
        }
        val command = CommandSpec(
            name = suggestion.suggestedName,
            serviceUuid = service,
            characteristicUuid = suggestion.characteristicUuid.orEmpty(),
            payloadHex = suggestion.payloadHex,
            writeType = suggestion.writeType,
            stage = EvidenceStage.OBSERVED,
            observedCount = suggestion.count,
            response = suggestion.response,
            notes = evidence,
        )
        mutate { it.copy(commands = it.commands + command) }
        _state.update { it.copy(tab = SessionTab.COMMANDS) }
    }

    fun createCommandFromEvent(event: BleEvent) {
        val session = _state.value.selected ?: return
        val marker = event.markerId?.let { id -> session.markers.firstOrNull { it.id == id } }
        val command = CommandSpec(
            name = marker?.label ?: "Command ${session.commands.size + 1}",
            serviceUuid = CommandAnalyzer.groupingUuid(event.serviceUuid)
                ?: CommandAnalyzer.resolveServiceUuid(session, event.characteristicUuid)
                ?: "",
            characteristicUuid = CommandAnalyzer.groupingUuid(event.characteristicUuid).orEmpty(),
            payloadHex = HaProfileBuilder.normalizeHex(event.payloadHex) ?: event.payloadHex,
            writeType = if (event.operation == AttOperation.WRITE_COMMAND) {
                WriteType.WITHOUT_RESPONSE
            } else {
                WriteType.WITH_RESPONSE
            },
            stage = EvidenceStage.OBSERVED,
            notes = "Created from the ${formatClockMicros(event.timestampEpochMicros)} event",
        )
        mutate { it.copy(commands = it.commands + command) }
        _state.update { it.copy(inspecting = null, tab = SessionTab.COMMANDS) }
    }

    fun editCommand(commandId: String, transform: (CommandSpec) -> CommandSpec) {
        mutate { session ->
            session.copy(commands = session.commands.map { if (it.id == commandId) transform(it) else it })
        }
    }

    fun setStage(commandId: String, stage: EvidenceStage, verifiedOnDevice: Boolean) {
        if (stage == EvidenceStage.DEVICE_TESTED && !verifiedOnDevice) {
            notice("Confirm you verified this on the device first")
            return
        }
        editCommand(commandId) { it.copy(stage = stage) }
    }

    fun incrementReplayCount(commandId: String) =
        editCommand(commandId) { it.copy(successfulReplayCount = it.successfulReplayCount + 1) }

    fun deleteCommand(commandId: String) {
        mutate { session -> session.copy(commands = session.commands.filterNot { it.id == commandId }) }
    }

    // -- Application-layer decryption ---------------------------------------------------------

    /**
     * Adds [preset]'s template as a new scheme and opens it for editing.
     *
     * The template carries no key on purpose: a preset describes a scheme, and the secret is the
     * one thing the operator has to bring.
     */
    fun addCipherFromPreset(preset: CipherPreset) {
        val scheme = preset.template.copy(id = UUID.randomUUID().toString())
        mutate { session ->
            session.copy(ciphers = session.ciphers + scheme.copy(name = uniqueCipherName(session, scheme.name)))
        }
        _state.update { it.copy(tab = SessionTab.DECRYPT, editingCipherId = scheme.id, tryEventId = null) }
    }

    fun editCipher(schemeId: String?) =
        _state.update { it.copy(editingCipherId = schemeId, tryEventId = null) }

    fun updateCipher(schemeId: String, transform: (CipherScheme) -> CipherScheme) {
        mutate { session ->
            session.copy(ciphers = session.ciphers.map { if (it.id == schemeId) transform(it) else it })
        }
    }

    fun setCipherEnabled(schemeId: String, enabled: Boolean) =
        updateCipher(schemeId) { it.copy(enabled = enabled) }

    fun deleteCipher(schemeId: String) {
        mutate { session -> session.copy(ciphers = session.ciphers.filterNot { it.id == schemeId }) }
        _state.update { if (it.editingCipherId == schemeId) it.copy(editingCipherId = null) else it }
    }

    /** Duplicates a scheme, key included: the usual reason to duplicate is to vary one field. */
    fun duplicateCipher(schemeId: String) {
        val source = _state.value.selected?.ciphers?.firstOrNull { it.id == schemeId } ?: return
        val copy = source.copy(id = UUID.randomUUID().toString())
        mutate { session ->
            session.copy(ciphers = session.ciphers + copy.copy(name = uniqueCipherName(session, source.name)))
        }
        _state.update { it.copy(editingCipherId = copy.id, tryEventId = null) }
    }

    /**
     * Derives a scheme's key from two captured frames and stores the result raw.
     *
     * The derivation is recorded in the scheme's notes rather than kept live: re-deriving on every
     * decrypt would tie the key to frames the operator may later delete, and the session key is
     * the same for the whole capture anyway.
     */
    fun deriveCipherKey(schemeId: String, rule: HandshakeRule) {
        val session = _state.value.selected ?: return
        KeyTools.deriveFromHandshake(session.events, rule)
            .onSuccess { keyHex ->
                updateCipher(schemeId) { scheme ->
                    scheme.copy(
                        keyHex = keyHex,
                        keyDerivation = KeyDerivation.RAW,
                        keySaltHex = null,
                        keyConstantHex = null,
                        notes = appendDerivationNote(scheme.notes, rule),
                    )
                }
                notice("Derived a ${keyHex.length / 2}-byte key from the handshake")
            }
            .onFailure { notice("Could not derive a key: ${it.message}") }
    }

    fun setApplyDecryption(apply: Boolean) = _state.update { it.copy(applyDecryption = apply) }

    fun setCompareDecrypted(useDecrypted: Boolean) =
        _state.update { it.copy(compareDecrypted = useDecrypted) }

    fun setTryEvent(eventId: String?) = _state.update { it.copy(tryEventId = eventId) }

    private fun uniqueCipherName(session: CaptureSession, base: String): String {
        if (session.ciphers.none { it.name == base }) return base
        var suffix = 2
        while (session.ciphers.any { it.name == "$base ($suffix)" }) suffix++
        return "$base ($suffix)"
    }

    private fun appendDerivationNote(notes: String, rule: HandshakeRule): String {
        val recipe = rule.parts.joinToString(" + ") { it.label }
        val line = "Key derived from the handshake: ${rule.derivation.name}($recipe)."
        return if (notes.isBlank()) line else "$notes\n$line"
    }

    fun updateConnection(transform: (ConnectionFacts) -> ConnectionFacts) =
        mutate { it.copy(connection = transform(it.connection)) }

    fun updateProtocol(transform: (ProtocolModel) -> ProtocolModel) =
        mutate { it.copy(protocol = transform(it.protocol)) }

    fun updateDeviceAlias(alias: String) {
        val trimmed = alias.trim()
        mutate { it.copy(device = it.device.copy(alias = trimmed.ifEmpty { null })) }
    }

    fun updateSessionNotes(notes: String) = mutate { it.copy(notes = notes) }

    fun setIncludeSecretsOnExport(include: Boolean) =
        _state.update { it.copy(includeSecretsOnExport = include) }

    fun shareEvidenceBundle() {
        val session = _state.value.selected ?: return
        val includeSecrets = _state.value.includeSecretsOnExport
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            runCatching { exports.exportEvidenceBundle(session, includeSecrets) }
                .onSuccess { export ->
                    _effects.emit(SessionEffect.Share(exports.shareIntent(export)))
                }
                .onFailure { notice("Could not write the bundle: ${it.message}") }
            _state.update { it.copy(exporting = false) }
        }
    }

    fun shareHaProfile() {
        val session = _state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            exports.exportHaProfile(session)
                .onSuccess { export -> _effects.emit(SessionEffect.Share(exports.shareIntent(export))) }
                .onFailure { notice(exportFailureMessage(it)) }
            _state.update { it.copy(exporting = false) }
        }
    }

    fun copyHaProfile() {
        val session = _state.value.selected ?: return
        viewModelScope.launch {
            exports.copyHaProfile(session)
                .onSuccess { characters ->
                    if (!exports.showsSystemClipboardConfirmation) {
                        notice("Copied $characters characters to the clipboard")
                    }
                }
                .onFailure { notice(exportFailureMessage(it)) }
        }
    }

    private fun exportFailureMessage(error: Throwable): String = when (error) {
        is ProfileNotExportableException -> error.report.headline
        else -> "Export failed: ${error.message}"
    }

    /** Applies [transform] to the selected session, updates the UI at once and schedules a save. */
    private fun mutate(transform: (CaptureSession) -> CaptureSession) {
        val current = _state.value.selected ?: return
        val updated = transform(current).copy(updatedAtEpochMs = System.currentTimeMillis())
        _state.update { state ->
            if (state.selected?.id != updated.id) {
                state
            } else {
                state.copy(
                    selected = updated,
                    sessions = state.sessions.map { if (it.id == updated.id) summarize(updated) else it },
                )
            }
        }
        queue(updated.id, updated, transform)
        derive(updated)
    }

    /**
     * Parks [snapshot] as the newest in-memory view of [id] and [delta] as the change the writer
     * must reproduce on disk, then wakes the writer.
     *
     * Deltas compose in edit order, so a burst of keystrokes still collapses into one write that
     * ends with the operator's latest value - applied to the current file, never replacing it.
     */
    private fun queue(
        id: String,
        snapshot: CaptureSession,
        delta: (CaptureSession) -> CaptureSession,
    ): CaptureSession {
        unsaved.compute(id) { _, queued ->
            if (queued == null) {
                PendingEdit(snapshot, delta)
            } else {
                val earlier = queued.delta
                PendingEdit(snapshot) { stored -> delta(earlier(stored)) }
            }
        }
        saveSignal.trySend(Unit)
        return snapshot
    }

    private suspend fun drainUnsaved() {
        for (id in unsaved.keys.toList()) {
            val pending = unsaved.remove(id) ?: continue
            if (id in deletedIds) continue
            runCatching { container.sessions.update(id, pending.delta) }
                .onFailure { failure ->
                    notice("Could not save \"${pending.snapshot.name}\": ${failure.message}")
                }
        }
    }

    /** Re-summarises a session written outside [mutate], so the list shows the new values. */
    private fun replaceSummary(saved: CaptureSession) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions
                    .map { if (it.id == saved.id) summarize(saved) else it }
                    .sortedByDescending { it.updatedAtEpochMs },
            )
        }
    }

    private fun derive(session: CaptureSession?) {
        deriveJob?.cancel()
        if (session == null) {
            _state.update {
                it.copy(
                    analyzing = false,
                    timeline = emptyList(),
                    facets = TimelineFacets(),
                    suggestions = emptyList(),
                    eligibility = null,
                    compareCandidates = emptyList(),
                    compareSelection = emptyList(),
                )
            }
            return
        }
        val filter = _state.value.filter
        val compareChannel = _state.value.compareChannel
        _state.update { it.copy(analyzing = true) }
        deriveJob = viewModelScope.launch {
            val derived = withContext(Dispatchers.Default) {
                Derived(
                    facets = facetsOf(session),
                    timeline = filterEvents(session, filter),
                    suggestions = CommandAnalyzer.suggest(session),
                    eligibility = HaProfileBuilder.evaluate(session),
                    compareCandidates = compareChannel?.let { channel ->
                        session.events.filter { eventChannelKey(it) == channel }
                    }.orEmpty(),
                )
            }
            _state.update { current ->
                if (current.selected?.id != session.id) {
                    current
                } else {
                    current.copy(
                        analyzing = false,
                        facets = derived.facets,
                        timeline = derived.timeline,
                        suggestions = derived.suggestions,
                        eligibility = derived.eligibility,
                        compareCandidates = derived.compareCandidates,
                        compareSelection = current.compareSelection.filter { selected ->
                            derived.compareCandidates.any { it.id == selected.id }
                        },
                    )
                }
            }
        }
    }

    private fun notice(message: String) {
        viewModelScope.launch { _effects.emit(SessionEffect.Notice(message)) }
    }

    private fun summarize(session: CaptureSession) = SessionSummary(
        id = session.id,
        name = session.name,
        deviceLabel = listOfNotNull(
            session.device.alias?.takeIf { it.isNotBlank() } ?: session.device.name?.takeIf { it.isNotBlank() },
            session.device.address.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { "No device yet" },
        eventCount = session.events.size,
        commandCount = session.commands.size,
        testedCommandCount = session.commands.count { it.stage == EvidenceStage.DEVICE_TESTED },
        updatedAtEpochMs = session.updatedAtEpochMs,
    )

    private class Derived(
        val facets: TimelineFacets,
        val timeline: List<BleEvent>,
        val suggestions: List<SuggestedCommand>,
        val eligibility: EligibilityReport,
        val compareCandidates: List<BleEvent>,
    )

    private fun facetsOf(session: CaptureSession): TimelineFacets {
        val directions = LinkedHashSet<EventDirection>(8)
        val operations = LinkedHashSet<AttOperation>(16)
        val channels = LinkedHashSet<String>(16)
        for (event in session.events) {
            directions.add(event.direction)
            operations.add(event.operation)
            channels.add(eventChannelKey(event))
        }
        return TimelineFacets(
            directions = directions.sortedBy { it.ordinal },
            operations = operations.sortedBy { it.ordinal },
            channels = channels.sorted(),
        )
    }

    private fun filterEvents(session: CaptureSession, filter: TimelineFilter): List<BleEvent> {
        val needle = filter.query.trim()
        // "CAFE" is both a plausible payload and a plausible note, so a hex-looking query is
        // matched against the bytes *and* the text rather than one or the other.
        val hexNeedle = hexNeedleOf(needle)
        val textNeedle = needle.lowercase().takeIf { it.isNotEmpty() }
        val markers = filter.markerIds.mapNotNull { id -> session.markers.firstOrNull { it.id == id } }
        return session.events.filter { event ->
            (filter.directions.isEmpty() || event.direction in filter.directions) &&
                (filter.operations.isEmpty() || event.operation in filter.operations) &&
                (filter.channels.isEmpty() || eventChannelKey(event) in filter.channels) &&
                (
                    markers.isEmpty() || markers.any { marker ->
                        event.markerId == marker.id ||
                            abs(event.timestampEpochMicros - marker.timestampEpochMicros) <=
                            CommandAnalyzer.DEFAULT_MARKER_WINDOW_MICROS
                    }
                    ) &&
                (
                    textNeedle == null ||
                        (hexNeedle != null && event.payloadHex.contains(hexNeedle, ignoreCase = true)) ||
                        event.note.contains(textNeedle, ignoreCase = true) ||
                        event.characteristicUuid?.contains(textNeedle, ignoreCase = true) == true ||
                        operationLabel(event.operation).contains(textNeedle, ignoreCase = true)
                    )
        }
    }

    /** Hex search accepts separators and an odd number of nibbles so prefixes match while typing. */
    private fun hexNeedleOf(query: String): String? {
        if (query.isEmpty()) return null
        val compact = StringBuilder(query.length)
        for (character in query.removePrefix("0x").removePrefix("0X")) {
            when (character) {
                ' ', ':', '-', '_' -> Unit
                in '0'..'9', in 'a'..'f', in 'A'..'F' -> compact.append(character.uppercaseChar())
                else -> return null
            }
        }
        return compact.toString().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_COMPARE = 6
        const val MIN_WRITE_SPACING_MS = 400L
    }
}
