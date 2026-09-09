package dev.nphil.blueshark.ui.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.data.MissingSessionException
import dev.nphil.blueshark.hci.BtsnoopParser
import dev.nphil.blueshark.hci.CollectAttempt
import dev.nphil.blueshark.hci.CollectProgress
import dev.nphil.blueshark.hci.CollectStage
import dev.nphil.blueshark.hci.ConnectionSummary
import dev.nphil.blueshark.hci.DissectionContext
import dev.nphil.blueshark.hci.DissectionNode
import dev.nphil.blueshark.hci.HciDissector
import dev.nphil.blueshark.hci.ParseSummary
import dev.nphil.blueshark.hci.SnoopCapabilities
import dev.nphil.blueshark.hci.SnoopController
import dev.nphil.blueshark.hci.SnoopMode
import dev.nphil.blueshark.hci.dissectionContextOf
import dev.nphil.blueshark.hci.enrich
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.shell.SHIZUKU_PACKAGE
import dev.nphil.blueshark.shell.ShizukuGateway
import dev.nphil.blueshark.shell.ShizukuState
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Upper bound on events kept from one collection; also the parser's event cap. */
private const val MAX_SESSION_EVENTS = 50_000

/** Rows rendered at once. A 50k-event timeline would make scrolling useless anyway. */
private const val TIMELINE_LIMIT = 600

/** Raw packets kept for the inspector; the timeline can only ever show a fraction of them. */
private const val MAX_INSPECTED_PACKETS = 2_000
private const val RECENT_LABELS = 5

/** The guided capture procedure. Steps are advisory: an operator may repeat or skip any of them. */
enum class CaptureStep(val title: String, val detail: String) {
    LOGGING("Enable full HCI logging", "Sets persist.bluetooth.btsnooplogmode to full"),
    RESTART("Restart Bluetooth", "Required before a new snoop mode takes effect"),
    VENDOR_APP("Launch the vendor app", "Then drive the gadget from that app"),
    MARKERS("Mark what you do", "Every label is stamped with the same clock as the packets"),
    COLLECT("Stop and collect", "Pulls the HCI log and decodes ATT traffic"),
    CLEANUP("Turn logging back off", "Leaves the device as you found it"),
}

data class VendorApp(val packageName: String, val label: String, val versionName: String?)

data class SessionOption(val id: String, val name: String, val eventCount: Int)

/** One row of the capture timeline: a connection header, an operator marker, or an ATT event. */
sealed interface TimelineRow {
    val key: String

    data class Connection(val handle: Int?, val events: Int) : TimelineRow {
        override val key: String get() = "connection-${handle ?: -1}-$events"
    }

    data class Marker(val marker: CaptureMarker) : TimelineRow {
        override val key: String get() = "marker-${marker.id}"
    }

    data class Event(val event: BleEvent) : TimelineRow {
        override val key: String get() = "event-${event.id}"
    }
}

data class CaptureUiState(
    val shizuku: ShizukuState = ShizukuState.NotRunning("Looking for Shizuku"),
    val capabilities: SnoopCapabilities = SnoopCapabilities(),
    val probing: Boolean = false,
    val busy: String? = null,
    val progress: CollectProgress? = null,
    val completed: Set<CaptureStep> = emptySet(),
    val apps: List<VendorApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appQuery: String = "",
    val vendorApp: VendorApp? = null,
    val markerLabel: String = "",
    val recentLabels: List<String> = emptyList(),
    val sessions: List<SessionOption> = emptyList(),
    val sessionId: String? = null,
    val sessionName: String = "",
    val events: List<BleEvent> = emptyList(),
    val markers: List<CaptureMarker> = emptyList(),
    val handleFilter: Int? = null,
    val connectionHandles: List<Int> = emptyList(),
    val timeline: List<TimelineRow> = emptyList(),
    val timelineTotal: Int = 0,
    val timelineShown: Int = 0,
    val summary: ParseSummary? = null,
    val attempts: List<CollectAttempt> = emptyList(),
    val warnings: List<String> = emptyList(),
    val collectedFrom: String? = null,
    val artifacts: List<String> = emptyList(),
    val snoopModeDetail: String? = null,
    /** Shell may not write the snoop property; the user must flip the Developer options toggle. */
    val snoopDenied: Boolean = false,
    /** Mirrors Settings > Debug logging so the capability card can offer "Send to ntfy". */
    val ntfyEnabled: Boolean = false,
    val restartSteps: List<String> = emptyList(),
    val logcatRunning: Boolean = false,
    val logcatFile: String? = null,
    val logcatLines: Int = 0,
    val peerChoices: List<ConnectionSummary> = emptyList(),
    val selectedPeer: String? = null,
    val selectedEventId: String? = null,
    val dissection: DissectionNode? = null,
    val error: String? = null,
) {
    val shellReady: Boolean get() = shizuku.ready
    val working: Boolean get() = busy != null
    val timelineTrimmed: Boolean get() = timelineTotal > timelineShown
}

/** Recomputes the derived timeline so composition never walks the whole event list. */
private fun CaptureUiState.withTimeline(): CaptureUiState {
    val handles = events.mapNotNullTo(LinkedHashSet()) { it.connectionHandle }.sorted()
    val filter = handleFilter?.takeIf { it in handles }
    // Sessions can mix live-GATT and HCI events appended at different times, so order by clock.
    val selected = events
        .let { if (filter == null) it else it.filter { event -> event.connectionHandle == filter } }
        .sortedBy { it.timestampEpochMicros }
    val window = if (selected.size > TIMELINE_LIMIT) selected.subList(selected.size - TIMELINE_LIMIT, selected.size) else selected
    val from = window.firstOrNull()?.timestampEpochMicros ?: Long.MIN_VALUE
    val visibleMarkers = markers
        .filter { it.timestampEpochMicros >= from }
        .sortedBy { it.timestampEpochMicros }

    val rows = ArrayList<TimelineRow>(window.size + visibleMarkers.size + 4)
    var markerIndex = 0
    var currentHandle: Int? = null
    var started = false
    var groupCount = 0
    for (event in window) {
        while (markerIndex < visibleMarkers.size &&
            visibleMarkers[markerIndex].timestampEpochMicros <= event.timestampEpochMicros
        ) {
            rows += TimelineRow.Marker(visibleMarkers[markerIndex])
            markerIndex++
        }
        if (!started || event.connectionHandle != currentHandle) {
            currentHandle = event.connectionHandle
            started = true
            groupCount++
            rows += TimelineRow.Connection(currentHandle, groupCount)
        }
        rows += TimelineRow.Event(event)
    }
    while (markerIndex < visibleMarkers.size) {
        rows += TimelineRow.Marker(visibleMarkers[markerIndex])
        markerIndex++
    }
    return copy(
        connectionHandles = handles,
        handleFilter = filter,
        timeline = rows,
        timelineTotal = selected.size,
        timelineShown = window.size,
    )
}

class CaptureViewModel(private val container: AppContainer) : ViewModel() {

    private val appContext: Context = container.appContext
    private val debug = container.debug

    init {
        viewModelScope.launch { debug.settings.collect { s -> _state.update { it.copy(ntfyEnabled = s.ntfyEnabled) } } }
    }
    private val shell = ShizukuGateway.get(appContext)
    private val snoop = SnoopController(appContext, shell, container.bluetoothManager.adapter)

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var shellJob: Job? = null
    private var logcatJob: Job? = null

    /**
     * H4 bytes of the most recently parsed packets, keyed by event id, and the handle-to-address
     * map a dissection uses. Kept out of [CaptureUiState] so composition never diffs them.
     */
    private var rawPackets: Map<String, String> = emptyMap()
    private var dissectionContext: DissectionContext = DissectionContext()

    init {
        viewModelScope.launch {
            shell.state.collect { shizuku ->
                val wasReady = _state.value.shizuku.ready
                _state.update { it.copy(shizuku = shizuku) }
                if (shizuku.ready && !wasReady && !_state.value.capabilities.probed) probe()
            }
        }
        viewModelScope.launch { loadSessions() }
        loadApps()
    }

    override fun onCleared() {
        shellJob?.cancel()
        logcatJob?.cancel()
    }

    // ------------------------------------------------------------------ Shizuku

    fun refreshShizuku() = shell.refresh()

    fun requestShizukuPermission() = shell.requestPermission()

    fun openShizuku() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            openShizukuDownload()
        } else {
            runCatching { appContext.startActivity(intent) }
                .onFailure { report("Could not open Shizuku: ${it.message}") }
        }
    }

    /** Puts the capability facts, diagnostics dump and recent debug log on the clipboard. */
    fun copyDiagnostics() {
        viewModelScope.launch {
            val c = _state.value.capabilities
            val text = buildString {
                append("BlueShark ").append(dev.nphil.blueshark.BuildConfig.VERSION_NAME).append('\n')
                append("effective snoop mode: ").append(c.effectiveSnoopMode.ifBlank { "(unset)" }).append('\n')
                append("service setting at enable: ").append(c.serviceSnoopSetting.ifBlank { "-" }).append('\n')
                append("stack reports: ").append(c.stackSnoopLog.ifBlank { "-" }).append('\n')
                append("btsnooplogmode property: ").append(c.snoopMode.ifBlank { "(unset)" }).append('\n')
                append("shell: ").append(c.shellIdentity).append('\n')
                append("log dir: ").append(c.logDirectory).append('\n')
                append("bugreportz: ").append(c.bugreportz).append('\n')
                append('\n').append(c.diagnostics).append('\n')
                append("## debug log\n").append(debug.snapshot())
            }
            val clipboard = appContext.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("BlueShark diagnostics", text))
            report("Diagnostics copied (${text.length} chars)")
        }
    }

    /** Publishes the same text to the ntfy topic configured in Settings, right now. */
    fun sendDiagnostics() {
        viewModelScope.launch {
            val c = _state.value.capabilities
            val body = ("effective=${c.effectiveSnoopMode.ifBlank { "(unset)" }} service=${c.serviceSnoopSetting} stack=${c.stackSnoopLog}\n" + c.diagnostics)
            report(debug.sendNow("BlueShark diagnostics", body.take(3_800)))
        }
    }

    fun openShizukuDownload() = openUrl("https://shizuku.rikka.app/download/")

    /**
     * Developer options is the only non-root way to change the snoop mode: its
     * "Enable Bluetooth HCI snoop log" toggle runs as system and writes the same property.
     * The screen re-probes on resume, so the step turns green as soon as getprop reads "full".
     */
    fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.onFailure {
            report("Developer options are hidden: Settings > About tablet > tap Build number 7 times, then retry")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.onFailure { report("No app can open $url") }
    }

    // ------------------------------------------------------------------ steps

    fun probe() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true) }
        viewModelScope.launch {
            val capabilities = snoop.probe()
            debug.log(
                "probe",
                "effective=${capabilities.effectiveSnoopMode.ifBlank { "(unset)" }} service=${capabilities.serviceSnoopSetting.ifBlank { "-" }} " +
                    "stack=${capabilities.stackSnoopLog.ifBlank { "-" }} prop=${capabilities.snoopMode.ifBlank { "-" }} " +
                    "bugreportz=${capabilities.bugreportz} logdir=${capabilities.logDirectory.take(80)}",
            )
            _state.update { current ->
                current.copy(
                    probing = false,
                    capabilities = capabilities,
                    snoopDenied = current.snoopDenied && !capabilities.snoopModeIsFull,
                    completed = if (capabilities.snoopModeIsFull) {
                        current.completed + CaptureStep.LOGGING
                    } else {
                        current.completed - CaptureStep.LOGGING
                    },
                    error = capabilities.error ?: current.error,
                )
            }
        }
    }

    fun setSnoopMode(mode: SnoopMode) = runExclusive(
        label = if (mode == SnoopMode.FULL) "Enabling full HCI logging" else "Disabling HCI logging",
    ) {
        val result = snoop.setSnoopMode(mode)
        debug.log("setprop", result.detail)
        val step = if (mode == SnoopMode.FULL) CaptureStep.LOGGING else CaptureStep.CLEANUP
        _state.update { current ->
            current.copy(
                snoopModeDetail = result.detail,
                snoopDenied = result.deniedByPolicy,
                capabilities = current.capabilities.copy(snoopMode = result.observed),
                completed = if (result.applied) current.completed + step else current.completed - step,
            )
        }
        report(
            when {
                result.applied -> "Snoop mode is now ${result.observed}"
                result.deniedByPolicy -> "Shell cannot change the snoop mode here; use the Developer options toggle"
                else -> result.detail
            },
        )
    }

    fun restartBluetooth() = runExclusive("Restarting Bluetooth") {
        val result = snoop.restartBluetooth()
        debug.log("restart", (result.steps + listOfNotNull(result.error)).joinToString(" | "))
        // The service re-reads the snoop setting on enable, so the probe only means something now.
        val capabilities = if (result.ok) snoop.probe() else null
        capabilities?.let {
            debug.log("probe", "after restart: effective=${it.effectiveSnoopMode.ifBlank { "(unset)" }} service=${it.serviceSnoopSetting.ifBlank { "-" }}")
        }
        _state.update { current ->
            current.copy(
                capabilities = capabilities ?: current.capabilities,
                completed = run {
                    val afterRestart = if (result.ok) current.completed + CaptureStep.RESTART else current.completed - CaptureStep.RESTART
                    when {
                        capabilities == null -> afterRestart
                        capabilities.snoopModeIsFull -> afterRestart + CaptureStep.LOGGING
                        else -> afterRestart - CaptureStep.LOGGING
                    }
                },
                snoopDenied = current.snoopDenied && capabilities?.snoopModeIsFull != true,
                restartSteps = result.steps,
                error = result.error ?: current.error,
            )
        }
        report(
            when {
                !result.ok -> result.error ?: "Bluetooth restart failed"
                capabilities?.snoopModeIsFull == true -> "Bluetooth restarted; service reports snoop mode ${capabilities.effectiveSnoopMode}"
                else -> "Bluetooth restarted; snoop mode is ${capabilities?.effectiveSnoopMode?.ifBlank { "(unset)" }}: check Developer options"
            },
        )
    }

    fun loadApps() {
        if (_state.value.appsLoading) return
        _state.update { it.copy(appsLoading = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            _state.update { it.copy(apps = apps, appsLoading = false) }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(): List<VendorApp> {
        val packageManager = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { resolved ->
                val packageName = resolved.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == appContext.packageName) return@mapNotNull null
                VendorApp(
                    packageName = packageName,
                    label = resolved.loadLabel(packageManager).toString(),
                    versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }

    fun setAppQuery(query: String) = _state.update { it.copy(appQuery = query) }

    fun launchApp(app: VendorApp) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            report("${app.label} has no launcher activity")
            return
        }
        runCatching { appContext.startActivity(intent) }
            .onSuccess {
                _state.update { it.copy(vendorApp = app, completed = it.completed + CaptureStep.VENDOR_APP) }
            }
            .onFailure { report("Could not launch ${app.label}: ${it.message}") }
    }

    fun selectApp(app: VendorApp) = _state.update { it.copy(vendorApp = app) }

    // ------------------------------------------------------------------ markers

    fun setMarkerLabel(label: String) = _state.update { it.copy(markerLabel = label) }

    fun addMarker(label: String = _state.value.markerLabel) {
        val text = label.trim()
        if (text.isEmpty()) {
            report("Type what you are about to do first")
            return
        }
        val marker = CaptureMarker(timestampEpochMicros = System.currentTimeMillis() * 1_000, label = text)
        _state.update { current ->
            current.copy(
                markers = current.markers + marker,
                markerLabel = "",
                recentLabels = (listOf(text) + current.recentLabels.filterNot { it == text }).take(RECENT_LABELS),
                completed = current.completed + CaptureStep.MARKERS,
            ).withTimeline()
        }
        viewModelScope.launch {
            runCatching { persist { session -> session.copy(markers = session.markers + marker) } }
                .onFailure { report("Marker not saved: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ sessions

    private suspend fun loadSessions() {
        val sessions = container.sessions.list().map { SessionOption(it.id, it.name, it.events.size) }
        _state.update { it.copy(sessions = sessions) }
    }

    fun chooseSession(id: String) {
        viewModelScope.launch {
            // An unreadable or unparseable bundle throws; an uncaught throw here would take the
            // process down, so report it like any other failed step.
            val session = try {
                container.sessions.load(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                report("Could not open that session: ${error.message ?: "unreadable file"}")
                return@launch
            }
            if (session == null) {
                report("That session no longer exists")
                loadSessions()
                return@launch
            }
            _state.update { current ->
                current.copy(
                    sessionId = session.id,
                    sessionName = session.name,
                    markers = session.markers,
                    events = session.events.takeLast(MAX_SESSION_EVENTS),
                ).withTimeline()
            }
        }
    }

    fun createSession(name: String) {
        val trimmed = name.trim().ifEmpty { defaultSessionName() }
        viewModelScope.launch {
            val session = try {
                container.sessions.save(CaptureSession(name = trimmed))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                report("Could not create the session: ${error.message ?: "write failed"}")
                return@launch
            }
            _state.update {
                it.copy(
                    sessionId = session.id,
                    sessionName = session.name,
                    events = emptyList(),
                    markers = emptyList(),
                ).withTimeline()
            }
            loadSessions()
            report("Capturing into \"$trimmed\"")
        }
    }

    fun setSessionName(name: String) = _state.update { it.copy(sessionName = name) }

    private fun defaultSessionName(): String =
        "HCI capture ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"

    /**
     * Applies [change] to the active session, creating one when the operator has not picked any.
     *
     * The change is applied inside [dev.nphil.blueshark.data.SessionStore.update], so it patches
     * whatever is on disk at that moment: the scan and relay screens write the same file, and this
     * screen must not publish a snapshot that predates their records.
     */
    private suspend fun persist(change: (CaptureSession) -> CaptureSession): CaptureSession {
        val current = _state.value
        val environment = snoop.environment(
            vendorPackage = current.vendorApp?.packageName,
            vendorVersion = current.vendorApp?.versionName,
            snoopMode = current.capabilities.snoopMode.takeIf { it.isNotBlank() },
        )
        val apply = { base: CaptureSession -> change(base).copy(environment = environment) }
        val id = current.sessionId
        val saved = if (id == null) {
            container.sessions.save(apply(CaptureSession(name = current.sessionName.ifBlank { defaultSessionName() })))
        } else {
            try {
                container.sessions.update(id, apply)
            } catch (missing: MissingSessionException) {
                // Deleted in the Sessions tab while this capture was running; keep the evidence.
                container.sessions.save(apply(CaptureSession(name = current.sessionName.ifBlank { defaultSessionName() })))
            }
        }
        _state.update { it.copy(sessionId = saved.id, sessionName = saved.name) }
        loadSessions()
        return saved
    }

    // ------------------------------------------------------------------ collect and import

    fun collect() = runExclusive("Collecting HCI log") {
        val result = snoop.collect { progress -> _state.update { it.copy(progress = progress) } }
        val file = result.btsnoop
        if (file == null) {
            val message = result.error ?: "Collection failed"
            _state.update {
                it.copy(
                    attempts = result.attempts,
                    error = message,
                    progress = CollectProgress(CollectStage.FAILED, message),
                )
            }
            report(message)
            return@runExclusive
        }
        ingest(file, result.source, result.attempts, result.warnings, result.artifacts)
    }

    fun importFile(uri: Uri) = runExclusive("Importing a capture file") {
        // Both the provider round trips (display name, then the stream) belong off the main thread.
        val opened = withContext(Dispatchers.IO) {
            val name = runCatching { displayNameOf(uri) }.getOrNull() ?: FALLBACK_IMPORT_NAME
            name to runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
        }
        val (displayName, stream) = opened
        if (stream == null) {
            report("Could not open $displayName")
            return@runExclusive
        }
        _state.update { it.copy(busy = "Importing $displayName") }
        val result = snoop.importFrom(stream, displayName) { progress -> _state.update { it.copy(progress = progress) } }
        val file = result.btsnoop
        if (file == null) {
            val message = result.error ?: "Import failed"
            _state.update { it.copy(attempts = result.attempts, error = message) }
            report(message)
            return@runExclusive
        }
        ingest(file, result.source, result.attempts, result.warnings, result.artifacts)
    }

    private suspend fun ingest(
        file: File,
        source: String,
        attempts: List<CollectAttempt>,
        collectWarnings: List<String>,
        artifacts: List<File>,
    ) {
        _state.update { it.copy(progress = CollectProgress(CollectStage.DECODE, "Parsing ${file.name}")) }
        // Parsing is a file read from start to finish; it belongs on the IO pool, not on the
        // CPU-bound dispatcher shared with the timeline derivation.
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                BtsnoopParser(maxEvents = MAX_SESSION_EVENTS, rawPackets = true).parse(file)
            }
        }
        val outcome = parsed.getOrElse { error ->
            val message = error.message ?: "Could not parse ${file.name}"
            _state.update {
                it.copy(
                    attempts = attempts,
                    error = message,
                    progress = CollectProgress(CollectStage.FAILED, message),
                )
            }
            report(message)
            return
        }
        // Parsed ids are derived from the record itself, so re-importing the same file (or a longer
        // capture that still contains the earlier records) adds nothing the session already holds.
        var appended = 0
        // Exactly one connected peer needs no chooser; more than one does, because only the
        // operator knows which address is the gadget.
        val onlyPeer = outcome.summary.connectionsByPeer.keys.singleOrNull()
        val saved = persist { session ->
            val known = session.events.mapTo(HashSet(session.events.size), BleEvent::id)
            val fresh = outcome.events.filterNot { it.id in known }
            appended = fresh.size
            val withEvents = session.copy(events = (session.events + fresh).takeLast(MAX_SESSION_EVENTS))
            if (onlyPeer == null) withEvents else enrich(withEvents, outcome.summary, onlyPeer)
        }
        rawPackets = trimRawPackets(outcome.rawHex)
        dissectionContext = dissectionContextOf(outcome.summary)
        _state.update { current ->
            current.copy(
                events = saved.events.takeLast(MAX_SESSION_EVENTS),
                markers = saved.markers,
                summary = outcome.summary,
                peerChoices = outcome.summary.connectionsByPeer.values.toList(),
                selectedPeer = onlyPeer,
                selectedEventId = null,
                dissection = null,
                attempts = attempts,
                warnings = collectWarnings + outcome.summary.warnings,
                collectedFrom = source,
                artifacts = artifacts.map { it.name },
                completed = current.completed + CaptureStep.COLLECT,
                progress = CollectProgress(
                    CollectStage.DONE,
                    "$appended new events from ${outcome.summary.records} records",
                ),
            ).withTimeline()
        }
        report(
            "Parsed ${outcome.summary.attEvents} ATT and ${outcome.summary.systemEvents} link events " +
                "from ${outcome.summary.records} HCI records" +
                if (appended == outcome.events.size) "" else "; $appended were new",
        )
    }

    /**
     * Applies the facts one peer proved to the session. Called for the operator's choice when the
     * capture held more than one connected peer.
     */
    fun choosePeer(address: String) {
        val summary = _state.value.summary ?: return
        viewModelScope.launch {
            val saved = runCatching { persist { session -> enrich(session, summary, address) } }
                .getOrElse { error ->
                    report(error.message ?: "Could not apply the capture's facts")
                    return@launch
                }
            _state.update {
                it.copy(
                    selectedPeer = address,
                    events = saved.events.takeLast(MAX_SESSION_EVENTS),
                    markers = saved.markers,
                ).withTimeline()
            }
            report("Applied what the capture proved about $address")
        }
    }

    /**
     * Selects (or, when already selected, deselects) the event whose dissection the inspector
     * shows. The tree is built on demand from the retained H4 bytes, so nothing is dissected until
     * an operator asks.
     */
    fun selectEvent(id: String) = _state.update { current ->
        if (current.selectedEventId == id) {
            current.copy(selectedEventId = null, dissection = null)
        } else {
            current.copy(selectedEventId = id, dissection = dissectionOf(id))
        }
    }

    private fun dissectionOf(id: String): DissectionNode? {
        val raw = rawPackets[id] ?: return null
        return runCatching { HciDissector.dissect(raw.hexToBytes(), dissectionContext) }.getOrNull()
    }

    /**
     * The inspector only ever dissects a row the timeline can show, so the newest
     * [MAX_INSPECTED_PACKETS] raw packets are all this holds; a full 50k-event capture of long
     * writes would otherwise keep tens of megabytes of hex alive for nothing.
     */
    private fun trimRawPackets(raw: Map<String, String>): Map<String, String> {
        if (raw.size <= MAX_INSPECTED_PACKETS) return raw
        val out = LinkedHashMap<String, String>(MAX_INSPECTED_PACKETS * 2)
        val skip = raw.size - MAX_INSPECTED_PACKETS
        var index = 0
        for ((id, hex) in raw) {
            if (index++ < skip) continue
            out[id] = hex
        }
        return out
    }

    fun setHandleFilter(handle: Int?) = _state.update { it.copy(handleFilter = handle).withTimeline() }

    fun cancelWork() {
        shellJob?.cancel()
        shellJob = null
        _state.update { it.copy(busy = null, progress = null) }
    }

    // ------------------------------------------------------------------ logcat

    fun toggleLogcat() {
        val running = logcatJob
        if (running != null) {
            running.cancel()
            logcatJob = null
            _state.update { it.copy(logcatRunning = false) }
            report("Stopped the Bluetooth log stream")
            return
        }
        if (!_state.value.shellReady) {
            report("Shizuku is not ready")
            return
        }
        _state.update { it.copy(logcatLines = 0, logcatRunning = true) }
        logcatJob = viewModelScope.launch {
            var lines = 0
            val outcome = runCatching {
                snoop.streamLogcat(onLine = {
                    lines++
                    if (lines % 25 == 0) _state.update { it.copy(logcatLines = lines) }
                })
            }
            logcatJob = null
            outcome
                .onSuccess { file ->
                    _state.update { it.copy(logcatFile = file.absolutePath, logcatLines = lines, logcatRunning = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(logcatRunning = false, logcatLines = lines) }
                    if (error !is CancellationException) report("Log stream failed: ${error.message}")
                }
        }
    }

    // ------------------------------------------------------------------ plumbing

    fun clearError() = _state.update { it.copy(error = null) }

    private fun report(message: String) {
        _messages.tryEmit(message)
    }

    /** Human name of a picked document; blocking, so callers keep it on a worker dispatcher. */
    private fun displayNameOf(uri: Uri): String {
        val fromProvider = appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK_IMPORT_NAME
    }

    /** Serialises the long shell operations: exactly one runs at a time, and it stays cancellable. */
    private fun runExclusive(label: String, block: suspend () -> Unit) {
        if (shellJob?.isActive == true) {
            report("Another shell operation is still running")
            return
        }
        _state.update { it.copy(busy = label, error = null, progress = null) }
        shellJob = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
                report(error.message ?: "Failed")
            } finally {
                shellJob = null
                _state.update { it.copy(busy = null) }
            }
        }
    }

    companion object {
        private const val FALLBACK_IMPORT_NAME = "imported file"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CaptureViewModel(container) }
        }
    }
}
