package dev.nphil.blueshark.guide

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.nphil.blueshark.debug.DebugLog
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.ControlRef
import dev.nphil.blueshark.model.MarkerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Two deliveries of the same tap arrive milliseconds apart; a real second tap does not. */
const val CLICK_DEBOUNCE_MS = 300L

/** A dragged slider fires continuously; only the value it settles on is worth a marker. */
const val RANGE_SETTLE_MS = 500L

/** A switch reports the state it is leaving at the moment its click arrives; the new one lands next. */
const val CHECK_SETTLE_MS = 150L

/** No advertisement for this long, while the presence scan runs, means the device went off the air. */
const val TARGET_SILENCE_MS = 4_000L

/** How often the target's presence is re-derived from the last advertisement seen. */
private const val PRESENCE_TICK_MS = 1_000L

/** Liveness is a coarse signal; one state emission per second is plenty and events arrive in floods. */
private const val EVENT_NOTE_MS = 1_000L

private val Context.guideStore: DataStore<Preferences> by preferencesDataStore("guide")
private val KEY_AUTO_MARK = booleanPreferencesKey("auto_mark")
private val KEY_OVERLAY = booleanPreferencesKey("overlay")
private val KEY_HIGHLIGHTS = booleanPreferencesKey("highlights")

/**
 * Everything the Capture screen and the overlay need to know about the guided take-over.
 *
 * [serviceConnected] is the live binding, not the Settings toggle: an OEM task killer can revoke a
 * running accessibility service, and the UI has to be able to say so.
 */
data class GuideState(
    val serviceConnected: Boolean = false,
    val targetPackage: String? = null,

    /** Human name of the vendor app, when the caller knew one; the overlay reads it. */
    val targetLabel: String? = null,
    val autoMark: Boolean = true,
    val overlay: Boolean = true,
    val highlights: Boolean = true,
    val progress: GuideProgress = GuideProgress(),
    val lastInteraction: ControlRef? = null,
    val lastInteractionAt: Long = 0L,

    /** When the operator was pointed at this app, i.e. when the learning session began. */
    val sessionStartedAtMs: Long = 0L,

    /** Interactions counted this session, after debouncing: what the overlay shows as "taps N". */
    val taps: Int = 0,

    /**
     * When the platform last delivered anything for the target app. An OEM task killer revokes a
     * running service silently, so "quiet for a while" is the only symptom the UI can show.
     */
    val lastEventAtMs: Long = 0L,
)

/**
 * True when an interaction is far enough from the previous one with the same identity to count as
 * a new interaction. A clock that jumped backwards (NTP, manual change) never blocks an event.
 */
fun outsideDebounce(lastAtMs: Long?, nowMs: Long, windowMs: Long): Boolean =
    lastAtMs == null || nowMs < lastAtMs || nowMs - lastAtMs >= windowMs

/**
 * Leading-edge throttle keyed by control identity: the first event of a burst is kept, its repeats
 * inside the window are dropped, and a different control is never affected by another's burst.
 *
 * The clock is injected so the behaviour is testable without sleeping.
 */
class InteractionDebounce(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastAccepted = LinkedHashMap<String, Long>()

    fun accept(key: String, windowMs: Long): Boolean {
        val now = clock()
        if (!outsideDebounce(lastAccepted[key], now, windowMs)) return false
        if (lastAccepted.size >= MAX_KEYS) {
            val iterator = lastAccepted.keys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        lastAccepted[key] = now
        return true
    }

    fun clear() = lastAccepted.clear()

    private companion object {
        const val MAX_KEYS = 512
    }
}

/**
 * What one interaction left a control in, read off the node once the touch settled.
 *
 * Every field is optional because most controls have none of them: a plain button is just a button.
 *
 * @param rangeValue the raw reading, kept so [ControlRef.rangeValue] carries the device's own units.
 */
data class ControlOutcome(
    val checked: Boolean? = null,
    val rangeValue: Float? = null,
    val rangePercent: Int? = null,
    val text: String = "",
)

/** Where [current] sits between [min] and [max] in whole percent; null when the span is unusable. */
fun rangePercent(current: Float?, min: Float?, max: Float?): Int? {
    if (current == null || min == null || max == null) return null
    if (!current.isFinite() || !min.isFinite() || !max.isFinite()) return null
    val span = max - min
    if (span <= 0f) return null
    return (((current - min) / span) * 100f).roundToInt().coerceIn(0, 100)
}

/** The state a snapshotted node was in. Only what the node actually models is reported. */
fun outcomeOf(node: NodeView): ControlOutcome = ControlOutcome(
    checked = if (node.checkable) node.checked else null,
    rangeValue = node.rangeValue,
    rangePercent = rangePercent(node.rangeValue, node.rangeMin, node.rangeMax),
    text = if (node.editable) node.text else "",
)

/**
 * The marker text for one observed interaction. Four shapes, and only these four - the learn/ slice
 * parses the suffix back into control state:
 *
 * - a checkable control: `Power switch -> on`, `Power switch -> off`
 * - a range control: `Brightness 62%` (the raw reading stays in [ControlRef.rangeValue])
 * - an editable control: `Device name: 'Kitchen lamp'`, clipped to [MARKER_TEXT_MAX] characters
 * - anything else: `Send`
 *
 * Checked beats range beats text, in the order in which a control is what it is: a checkable slider
 * does not exist, and a switch with a caption beside it is still a switch.
 */
fun markerLabel(label: String, outcome: ControlOutcome = ControlOutcome()): String {
    val name = collapseSpaces(label)
    if (name.isEmpty()) return ""
    outcome.checked?.let { return "$name -> ${if (it) "on" else "off"}" }
    outcome.rangePercent?.let { return "$name $it%" }
    val text = quotable(outcome.text)
    return if (text.isEmpty()) name else "$name: '$text'"
}

/** Whether the target device is on the air right now. */
enum class TargetPresence { ADVERTISING, SILENT, UNKNOWN }

/**
 * The device the session is about, as the address-filtered presence scan sees it.
 *
 * @param lastSeenMs epoch ms of the last advertisement; 0 when it has not been heard at all.
 */
data class TargetStatus(
    val name: String = "",
    val address: String = "",
    val presence: TargetPresence = TargetPresence.UNKNOWN,
    val lastSeenMs: Long = 0L,
)

/**
 * ADVERTISING while an advertisement arrived inside [silenceMs], SILENT once the air goes quiet, and
 * UNKNOWN whenever nothing is listening - no address, no permission, or Bluetooth switched off.
 */
fun targetPresence(
    scanning: Boolean,
    lastSeenMs: Long,
    nowMs: Long,
    silenceMs: Long = TARGET_SILENCE_MS,
): TargetPresence = when {
    !scanning -> TargetPresence.UNKNOWN
    lastSeenMs <= 0L -> TargetPresence.SILENT
    nowMs < lastSeenMs || nowMs - lastSeenMs <= silenceMs -> TargetPresence.ADVERTISING
    else -> TargetPresence.SILENT
}

/**
 * The words the overlay puts on the target line.
 *
 * A connectable device that goes quiet has almost always been taken off the air by a central that
 * connected to it - and during a learning session that central is the vendor app, which is exactly
 * the evidence the operator is after. A device that was never heard at all is a different problem.
 */
fun presenceText(status: TargetStatus, sessionStartedAtMs: Long): String = when (status.presence) {
    TargetPresence.ADVERTISING -> "advertising"
    TargetPresence.SILENT ->
        if (status.lastSeenMs > 0L && status.lastSeenMs >= sessionStartedAtMs) "held by a central (the app?)"
        else "not advertising"
    TargetPresence.UNKNOWN -> "presence unknown"
}

/** How long nothing has been delivered to the observer; 0 while it has yet to see its first event. */
fun quietSeconds(lastEventAtMs: Long, nowMs: Long): Long =
    if (lastEventAtMs <= 0L || nowMs <= lastEventAtMs) 0L else (nowMs - lastEventAtMs) / 1_000L

/** Longer than this in a marker and the operator is reading a novel, not a label. */
const val MARKER_TEXT_MAX = 24

/** One line and one pair of quotes: a field's value may contain neither a newline nor an apostrophe. */
private fun quotable(text: String): String {
    val flat = collapseSpaces(text).replace('\'', '\u2019')
    return if (flat.length <= MARKER_TEXT_MAX) flat else flat.take(MARKER_TEXT_MAX) + "\u2026"
}

private fun collapseSpaces(text: String): String = text.trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

/**
 * Session-scoped state of the guided take-over, shared by the accessibility service (producer) and
 * the Capture screen (consumer).
 *
 * The inventory and the debounce are only ever driven from the main thread - accessibility events
 * are delivered there, and the Capture screen's setters run there too.
 */
class GuideController(
    context: Context,
    private val scope: CoroutineScope,
    private val debug: DebugLog,
    private val labeler: ControlLabeler = ControlLabeler(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val appContext = context.applicationContext
    private val store = appContext.guideStore
    private val inventory = ControlInventory(labeler)
    private val debounce = InteractionDebounce(clock)

    private val bluetooth: BluetoothManager? =
        runCatching { appContext.getSystemService(BluetoothManager::class.java) }.getOrNull()

    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()

    /**
     * Observed interactions, as markers. Buffered rather than blocking: dropping the oldest auto
     * marker is better than stalling the accessibility callback the whole system waits on.
     */
    private val _autoMarkers = MutableSharedFlow<CaptureMarker>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val autoMarkers: SharedFlow<CaptureMarker> = _autoMarkers.asSharedFlow()

    private val _targetStatus = MutableStateFlow(TargetStatus())

    /** The device the session is about: who it is, and whether it is still on the air. */
    val targetStatus: StateFlow<TargetStatus> = _targetStatus.asStateFlow()

    /** Written from the scan callback's binder thread, read by the presence ticker. */
    @Volatile
    private var lastAdvertAtMs = 0L

    private var presenceJob: Job? = null

    /**
     * The registration that owns the presence flow. Read from the binder thread that delivers
     * advertisements, so a stopped scan's last in-flight packet cannot revive a dead session.
     */
    @Volatile
    private var scanCallback: ScanCallback? = null

    /** The scanner the callback was registered on; stopping it on another instance is a leak. */
    private var scanner: BluetoothLeScanner? = null

    init {
        scope.launch {
            store.data.collect { prefs ->
                _state.update {
                    it.copy(
                        autoMark = prefs[KEY_AUTO_MARK] ?: true,
                        overlay = prefs[KEY_OVERLAY] ?: true,
                        highlights = prefs[KEY_HIGHLIGHTS] ?: true,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ operator settings

    /**
     * Points the guide at one package, which is also when the learning session starts: a different
     * package starts a fresh checklist, fresh counters and a fresh session clock. Passing null ends
     * the session without a marker; [finishSession] is the operator-visible end.
     *
     * @param label the vendor app's human name, when the caller knows it.
     */
    fun setTarget(packageName: String?, label: String? = null) {
        if (_state.value.targetPackage == packageName) {
            if (label != null && label != _state.value.targetLabel) _state.update { it.copy(targetLabel = label) }
            return
        }
        inventory.clear()
        debounce.clear()
        _state.update {
            it.copy(
                targetPackage = packageName,
                targetLabel = label,
                progress = GuideProgress(),
                lastInteraction = null,
                lastInteractionAt = 0L,
                sessionStartedAtMs = if (packageName == null) 0L else clock(),
                taps = 0,
                lastEventAtMs = 0L,
            )
        }
        if (packageName == null) stopPresenceScan() else restartPresenceScan()
        debug.log("guide", "target is now ${packageName ?: "(none)"}")
    }

    /**
     * Names the device the session is about. The presence scan follows it: address-filtered, low
     * power, and only while a session is running.
     */
    fun setTargetDevice(address: String?, name: String? = null) {
        val clean = address?.trim()?.uppercase().orEmpty()
        lastAdvertAtMs = 0L
        _targetStatus.value = TargetStatus(name = name?.trim().orEmpty(), address = clean)
        restartPresenceScan()
    }

    /**
     * Ends the session the way the operator does: a final marker so the timeline shows where they
     * stopped driving, then the observer and the presence scan stand down.
     */
    fun finishSession() {
        if (_state.value.targetPackage != null) emitManualMarker(SESSION_FINISHED_MARKER)
        setTarget(null)
    }

    fun setAutoMark(enabled: Boolean) = persist(KEY_AUTO_MARK, enabled) { it.copy(autoMark = enabled) }

    fun setOverlay(enabled: Boolean) = persist(KEY_OVERLAY, enabled) { it.copy(overlay = enabled) }

    fun setHighlights(enabled: Boolean) = persist(KEY_HIGHLIGHTS, enabled) { it.copy(highlights = enabled) }

    private fun persist(key: Preferences.Key<Boolean>, value: Boolean, apply: (GuideState) -> GuideState) {
        _state.update(apply)
        scope.launch { runCatching { store.edit { it[key] = value } } }
    }

    // ------------------------------------------------------------------ system settings

    /**
     * Whether our observer is in the platform's list of enabled accessibility services. This is the
     * operator's toggle; [GuideState.serviceConnected] is whether it is actually running.
     */
    fun accessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context.packageName, ControlObserverService::class.java.name)
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val flat = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { debug.log("guide", "could not open accessibility settings: ${it.message}") }
    }

    // ------------------------------------------------------------------ service facing

    fun onServiceConnected() {
        _state.update { it.copy(serviceConnected = true) }
    }

    fun onServiceDisconnected(reason: String) {
        _state.update { it.copy(serviceConnected = false) }
        debug.log("guide", "service disconnected: $reason")
        // The scan and its ticker live in the app scope, not the service's: without this, turning
        // the observer off while BlueShark stays alive leaves a scanner registered with no overlay
        // to ever stop it.
        stopPresenceScan()
    }

    /**
     * The platform delivered something for the target app, so the observer is alive. Throttled: a
     * vendor app animating a screen produces content changes by the dozen and every state emission
     * re-renders both the overlay and the Capture screen.
     */
    fun noteEvent() {
        val now = clock()
        _state.update {
            if (now > it.lastEventAtMs && now - it.lastEventAtMs < EVENT_NOTE_MS) it else it.copy(lastEventAtMs = now)
        }
    }

    /** Replaces one screen's checklist. Republishes state only when the screen actually changed. */
    fun observeScreen(screen: String, controls: List<ControlRef>) {
        if (!inventory.observe(screen, controls)) return
        _state.update { it.copy(progress = inventory.progress()) }
    }

    /**
     * Marks [control] off the checklist without emitting anything. Used while a slider is still
     * moving: the highlight has to clear under the operator's finger, but the marker waits for the
     * value they settle on.
     */
    fun markTouched(control: ControlRef) {
        val now = clock()
        val fresh = inventory.touch(control)
        _state.update {
            it.copy(
                progress = inventory.progress(),
                lastInteraction = control,
                lastInteractionAt = now,
            )
        }
        if (fresh) debug.log("guide", "mapped ${labeler.key(control)} on ${control.screen}")
    }

    /**
     * Records that [control] was driven, with the state [outcome] it was left in. The control is
     * marked off the checklist immediately, the tap is counted once its identity clears the
     * debounce window, and the marker is emitted only when auto-marking is on.
     *
     * [atMs] is when the operator touched the control, which is *not* when this is called: the
     * state a switch or a slider lands in is only readable once it has settled. Attribution walks
     * forward from a marker to the writes that follow it, so a marker stamped at emit time would
     * hand the writes the tap itself caused to nobody.
     *
     * Returns true when a marker was emitted.
     */
    fun recordInteraction(
        control: ControlRef,
        label: String,
        outcome: ControlOutcome = ControlOutcome(),
        atMs: Long = clock(),
        windowMs: Long = CLICK_DEBOUNCE_MS,
    ): Boolean {
        markTouched(control)
        if (!debounce.accept(labeler.key(control), windowMs)) return false
        _state.update { it.copy(taps = it.taps + 1) }
        if (!_state.value.autoMark) return false
        _autoMarkers.tryEmit(
            CaptureMarker(
                timestampEpochMicros = atMs * 1_000,
                label = markerLabel(label.ifBlank { labeler.describe(control) }, outcome),
                source = MarkerSource.ACCESSIBILITY,
                control = control,
            ),
        )
        return true
    }

    /** A marker the operator typed into the overlay while driving the vendor app. */
    fun emitManualMarker(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        _autoMarkers.tryEmit(
            CaptureMarker(
                timestampEpochMicros = clock() * 1_000,
                label = text,
                source = MarkerSource.MANUAL,
            ),
        )
    }

    fun coverage(screen: String): ScreenCoverage? = _state.value.progress.coverage(screen)

    // ------------------------------------------------------------------ target presence

    /** True when BLUETOOTH_SCAN has been granted; the presence scan is impossible without it. */
    fun hasScanPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /**
     * (Re)points the presence scan. One registration at a time, and none at all without a session,
     * a well-formed address, the permission and a switched-on adapter - every one of which the
     * operator can change while the overlay is up.
     */
    private fun restartPresenceScan() {
        stopPresenceScan()
        val address = _targetStatus.value.address
        if (_state.value.targetPackage == null || !ADDRESS.matches(address)) return
        if (!hasScanPermission()) {
            debug.log("guide", "presence of $address unknown: the nearby-devices permission is not granted")
            return
        }
        val scanner = bluetooth?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            debug.log("guide", "presence of $address unknown: no LE scanner (Bluetooth off?)")
            return
        }
        if (!startPresenceScan(scanner, address)) return
        presenceJob = scope.launch {
            while (true) {
                delay(PRESENCE_TICK_MS)
                publishPresence()
            }
        }
    }

    /**
     * Low power on purpose: this scan answers one question - is the target still advertising - for
     * as long as the session lasts, and the operator's phone is doing real work at the same time.
     */
    @SuppressLint("MissingPermission")
    private fun startPresenceScan(scanner: BluetoothLeScanner, address: String): Boolean {
        val filter = runCatching { ScanFilter.Builder().setDeviceAddress(address).build() }.getOrNull() ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .setReportDelay(0L)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onAdvertisement(this, result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) onAdvertisement(this, result)
            }

            override fun onScanFailed(errorCode: Int) {
                debug.log("guide", "presence scan failed with code $errorCode")
                // A failed registration is no scan at all: drop it so the ticker cannot read
                // "no adverts" off a listener that was never listening and report SILENT.
                val failed = this
                scope.launch { if (scanCallback === failed) stopPresenceScan() }
            }
        }
        val started = runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { debug.log("guide", "presence scan refused: ${it.message}") }
            .isSuccess
        if (!started) return false
        this.scanner = scanner
        scanCallback = callback
        return true
    }

    @SuppressLint("MissingPermission")
    private fun stopPresenceScan() {
        presenceJob?.cancel()
        presenceJob = null
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(callback) }
            .onFailure { debug.log("guide", "presence scan would not stop: ${it.message}") }
        scanner = null
        _targetStatus.update { it.copy(presence = TargetPresence.UNKNOWN) }
    }

    /** Called on a binder thread: nothing here may touch the inventory or the debounce. */
    private fun onAdvertisement(from: ScanCallback, result: ScanResult) {
        if (scanCallback !== from) return
        lastAdvertAtMs = clock()
        val name = runCatching { result.scanRecord?.deviceName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        publishPresence(name)
    }

    private fun publishPresence(name: String? = null) {
        val seenAt = lastAdvertAtMs
        _targetStatus.update { current ->
            current.copy(
                name = name ?: current.name,
                presence = targetPresence(scanCallback != null, seenAt, clock()),
                lastSeenMs = seenAt,
            )
        }
    }

    companion object {
        /**
         * Set on the intent that brings BlueShark forward when the operator pressed Finish in the
         * overlay: the session is complete and the app can collect and merge it without asking.
         */
        const val EXTRA_FINISH_LEARNING = "dev.nphil.blueshark.guide.FINISH_LEARNING"

        /** The marker Finish drops; the learn/ slice reads it as the end of the session. */
        const val SESSION_FINISHED_MARKER = "session finished"

        private val ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
