package dev.nphil.blestudio.relay

import android.bluetooth.BluetoothManager
import android.content.Context
import dev.nphil.blestudio.model.BleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide home of the running relay.
 *
 * A relay outlives the screen that started it: [dev.nphil.blestudio.service.BleForegroundService]
 * owns the run, while the Activity may be gone. Everything the UI needs - phase, counters, log and
 * the captured timeline - therefore lives here, not in a ViewModel, and a freshly created ViewModel
 * simply re-collects [state] and [timeline].
 */
object RelaySession {

    private const val MAX_RECORDED_EVENTS = 20_000
    private const val TIMELINE_WINDOW = 400
    private const val PUBLISH_INTERVAL_MS = 150L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = 1_024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live packet stream; [timeline] is the retained, UI-friendly view of the same events. */
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    private val _timeline = MutableStateFlow<List<BleEvent>>(emptyList())
    val timeline: StateFlow<List<BleEvent>> = _timeline.asStateFlow()

    private val recorded = ArrayList<BleEvent>(512)
    private val dirty = AtomicBoolean(false)

    private var coordinator: GattRelayCoordinator? = null
    private var mirrorJob: Job? = null
    private var publishJob: Job? = null
    private var pendingConfig: RelayConfig? = null
    private var droppedEvents = 0

    /** Last configuration the user asked for; used to repopulate the screen. */
    @Volatile
    var lastConfig: RelayConfig? = null
        private set

    /** Last "Preview target" result, kept so it survives navigation away from the relay screen. */
    @Volatile
    internal var preview: TargetSnapshot? = null

    val isRunning: Boolean get() = synchronized(lock) { coordinator?.isRunning == true }

    val droppedEventCount: Int get() = synchronized(lock) { droppedEvents }

    /** Stores the configuration the foreground service will pick up in [attach]. */
    fun requestStart(config: RelayConfig) {
        synchronized(lock) { pendingConfig = config }
        lastConfig = config
    }

    /** Called by the foreground service once it is actually in the foreground. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (coordinator?.isRunning == true) return
            val config = pendingConfig ?: lastConfig
            if (config == null) {
                _state.value = RelayState(
                    phase = RelayPhase.Failed("The relay service started without a target configuration"),
                )
                return
            }
            val manager = context.getSystemService(BluetoothManager::class.java)
            if (manager == null) {
                _state.value = RelayState(phase = RelayPhase.Failed("This device has no Bluetooth service"))
                return
            }
            coordinator?.shutdown()
            mirrorJob?.cancel()
            publishJob?.cancel()

            val instance = GattRelayCoordinator(context.applicationContext, manager, ::record)
            coordinator = instance
            pendingConfig = null
            // Started before the state is mirrored, so the collector's first value is already the
            // new run's ConnectingTarget rather than a stale Idle.
            instance.start(config)
            mirrorJob = scope.launch { mirrorState(instance) }
            publishJob = scope.launch { publishLoop() }
        }
    }

    /** Requests an orderly stop; safe to call when nothing is running. */
    fun stop() {
        val instance = synchronized(lock) { coordinator }
        instance?.stop()
        publish()
    }

    /** Surfaces a hosting failure (no foreground service, no permission) on the relay screen. */
    fun reportServiceFailure(reason: String) {
        stop()
        _state.value = RelayState(phase = RelayPhase.Failed(reason))
    }

    fun clearTimeline() {
        synchronized(lock) {
            recorded.clear()
            droppedEvents = 0
        }
        _timeline.value = emptyList()
    }

    /** Every event of the current recording, for persisting into a [dev.nphil.blestudio.model.CaptureSession]. */
    fun recordedEvents(): List<BleEvent> = synchronized(lock) { ArrayList(recorded) }

    /** Forces a timeline publish; used when a screen is opened while nothing is streaming. */
    fun refreshTimeline() = publish()

    /**
     * Mirrors one run's state into [state] and stops collecting when that run ends.
     *
     * The coordinator's flow never completes, so the mirror has to end itself: leaving it collecting
     * would keep this run's coordinator - server, advertiser and target link - reachable for as long
     * as the process lives, and stack another collector on top with every restart.
     */
    private suspend fun mirrorState(instance: GattRelayCoordinator) {
        val self = currentCoroutineContext()[Job]
        var sawActive = false
        instance.state.collect { relayState ->
            _state.value = relayState
            if (relayState.phase.isActive) {
                sawActive = true
            } else if (sawActive) {
                var mine = false
                synchronized(lock) {
                    // A restart may already have installed a replacement mirror and publisher;
                    // this run's teardown must not touch the new run's jobs.
                    mine = mirrorJob === self
                    if (mine) {
                        publishJob?.cancel()
                        publishJob = null
                        mirrorJob = null
                    }
                }
                if (mine) {
                    publish()
                    self?.cancel()
                }
            }
        }
    }

    private suspend fun publishLoop() {
        while (true) {
            delay(PUBLISH_INTERVAL_MS)
            if (dirty.compareAndSet(true, false)) publish()
        }
    }

    /**
     * Batched on purpose: a busy relay produces hundreds of packets per second and copying the
     * window on every one of them would dominate the relay's own cost.
     */
    private fun publish() {
        _timeline.value = synchronized(lock) {
            if (recorded.size <= TIMELINE_WINDOW) {
                ArrayList(recorded)
            } else {
                ArrayList(recorded.subList(recorded.size - TIMELINE_WINDOW, recorded.size))
            }
        }
    }

    private fun record(event: BleEvent) {
        synchronized(lock) {
            if (recorded.size < MAX_RECORDED_EVENTS) recorded.add(event) else droppedEvents++
        }
        _events.tryEmit(event)
        dirty.set(true)
    }
}
