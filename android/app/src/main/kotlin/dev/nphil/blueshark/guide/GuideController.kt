package dev.nphil.blueshark.guide

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Two deliveries of the same tap arrive milliseconds apart; a real second tap does not. */
const val CLICK_DEBOUNCE_MS = 300L

/** A dragged slider fires continuously; only the value it settles on is worth a marker. */
const val RANGE_SETTLE_MS = 500L

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
    val autoMark: Boolean = true,
    val overlay: Boolean = true,
    val highlights: Boolean = true,
    val progress: GuideProgress = GuideProgress(),
    val lastInteraction: ControlRef? = null,
    val lastInteractionAt: Long = 0L,
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

/** The marker text for one observed interaction: the control's label, plus the value it now holds. */
fun markerLabel(label: String, rangeValue: Float?): String =
    label + (rangeValue?.let { " = $it" } ?: "")

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

    /** Points the guide at one package. A different package starts a fresh checklist. */
    fun setTarget(packageName: String?) {
        val previous = _state.value.targetPackage
        if (previous == packageName) return
        inventory.clear()
        debounce.clear()
        _state.update {
            it.copy(
                targetPackage = packageName,
                progress = GuideProgress(),
                lastInteraction = null,
                lastInteractionAt = 0L,
            )
        }
        debug.log("guide", "target is now ${packageName ?: "(none)"}")
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
     * Records that [control] was driven. The control is marked off the checklist immediately; the
     * marker is emitted only when auto-marking is on and this identity is outside its debounce
     * window. Returns true when a marker was emitted.
     */
    fun recordInteraction(control: ControlRef, label: String, windowMs: Long = CLICK_DEBOUNCE_MS): Boolean {
        markTouched(control)
        if (!_state.value.autoMark) return false
        if (!debounce.accept(labeler.key(control), windowMs)) return false
        _autoMarkers.tryEmit(
            CaptureMarker(
                timestampEpochMicros = clock() * 1_000,
                label = markerLabel(label, control.rangeValue).ifBlank { labeler.describe(control) },
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
}
