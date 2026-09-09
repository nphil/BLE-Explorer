package dev.nphil.blueshark.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import dev.nphil.blueshark.signal.SignalAnalyzer
import dev.nphil.blueshark.signal.SignalSample
import dev.nphil.blueshark.signal.SignalSource
import dev.nphil.blueshark.signal.SignalStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * PHYs involved in one target's traffic: what its advertising set uses, and — once linked — the
 * PHY the connection negotiated. `null` fields simply mean "not observed yet".
 */
@Immutable
data class SignalPhy(
    val primary: Int? = null,
    val secondary: Int? = null,
    val legacy: Boolean? = null,
    val link: PhyPair? = null,
) {
    /** Chip labels, advertising first then the link; allocates, so call it behind a `remember`. */
    fun labels(): List<String> = buildList(4) {
        primary?.let { add("Adv ${GattClient.phyName(it)}") }
        secondary?.takeIf { it != ScanResult.PHY_UNUSED }?.let { add("Aux ${GattClient.phyName(it)}") }
        legacy?.let { add(if (it) "Legacy adv" else "Extended adv") }
        link?.let { add("Link ${GattClient.phyName(it.tx)}/${GattClient.phyName(it.rx)}") }
    }
}

/** Everything the Signal screen renders, recomputed on one ticker rather than per packet. */
@Immutable
data class SignalSnapshot(
    val stats: SignalStats,
    val history: List<SignalSample> = emptyList(),
    val presence: List<Boolean> = emptyList(),
    val connectable: Boolean? = null,
    val name: String? = null,
    val phy: SignalPhy? = null,
    val connected: Boolean = false,
    val connectedRssi: Int? = null,
    val error: String? = null,
)

/**
 * Placement diagnostics for exactly one address.
 *
 * Registers its own address-filtered, low-latency scan — deliberately independent of
 * [ScannerRepository], whose balanced-mode aggregate is tuned for discovering a room rather than
 * timing one peripheral's advertisements. Packets, the 100 ms publish tick and the connected-RSSI
 * poll are merged into a single stream, so the [SignalAnalyzer] is only ever touched by one
 * coroutine and needs no locking.
 *
 * Both clocks used here are `CLOCK_BOOTTIME`: [ScanResult.getTimestampNanos] is documented as
 * "timestamp since boot", i.e. [SystemClock.elapsedRealtimeNanos], so connection samples and the
 * `now` handed to the analyzer use the same base. `System.nanoTime()` is `CLOCK_MONOTONIC` and
 * would sit at a different offset, which would make gaps, presence and packet rate meaningless
 * across a connect.
 *
 * While a central holds a connection the peripheral usually stops advertising altogether: the
 * advertisement statistics freeze at their last values, `sinceLastMs` climbs, the presence strip
 * goes empty and [SignalSnapshot.connectedRssi] — sampled from the link itself — takes over as the
 * live number. That is the whole point of the presence timeline: a silent-but-connected device
 * looks nothing like a device that is simply too far away.
 */
@SuppressLint("MissingPermission")
class SignalMonitor(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val gatt: GattClient,
    private val scope: CoroutineScope,
) {
    private val analyzer = SignalAnalyzer()

    private val _snapshot = MutableStateFlow(SignalSnapshot(analyzer.stats(nowNanos())))
    val snapshot: StateFlow<SignalSnapshot> = _snapshot.asStateFlow()

    /**
     * One emission per received packet, for the gauge's ping animation. Deliberately lossy: a
     * dropped ping costs a ring, and nothing reads this for measurement.
     */
    private val _pulses = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = PULSE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pulses: SharedFlow<Unit> = _pulses.asSharedFlow()

    /** Address being monitored, or null when idle. */
    @Volatile
    var address: String? = null
        private set

    /** Applied by the collector on the next tick; the slider runs on the main thread. */
    @Volatile
    private var marginDb: Int = analyzer.proxyMarginDb

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var connectable: Boolean? = null

    @Volatile
    private var name: String? = null

    @Volatile
    private var primaryPhy: Int? = null

    @Volatile
    private var secondaryPhy: Int? = null

    @Volatile
    private var legacy: Boolean? = null

    @Volatile
    private var linkPhy: PhyPair? = null

    @Volatile
    private var connectedRssi: Int? = null

    /** True when this monitor opened the link, so [close] only drops one it owns. */
    @Volatile
    private var ownsLink = false

    private var job: Job? = null

    /** Bumped by every [start]/[stop]; a cancelled run must not clear the run that replaced it. */
    private var generation = 0

    /** True when BLUETOOTH_SCAN has been granted; measuring advertisements is impossible without it. */
    fun hasScanPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    val monitoring: Boolean get() = job?.isActive == true

    /**
     * Point the monitor at [address] and start measuring. Restarting on the same address is a
     * no-op; a different address resets the window, because two rooms' numbers must never mix.
     */
    fun start(address: String) {
        if (this.address == address && monitoring) return
        val restart = this.address != address
        stop()
        this.address = address
        if (restart) resetEvidence()
        val failure = preflight()
        if (failure != null) {
            note(failure)
            return
        }
        val run = ++generation
        job = scope.launch { runMonitor(address, run) }
    }

    /** Unregister the scan callback. The measured window is kept so the screen still reads. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    /** Stop measuring and drop a link this monitor opened; for `ViewModel.onCleared`. */
    fun close() {
        stop()
        if (ownsLink) {
            ownsLink = false
            gatt.disconnect()
        }
    }

    /** Forget every sample and waypoint-relevant reading for the current address. */
    fun reset() {
        resetEvidence()
        if (!monitoring) publish()
    }

    fun setProxyMargin(db: Int) {
        marginDb = db
        // No collector is running, so nobody else owns the analyzer: apply it here or the screen
        // would keep grading against the old margin until monitoring resumes.
        if (!monitoring) {
            analyzer.proxyMarginDb = db
            publish()
        }
    }

    fun acknowledgeError() {
        if (lastError != null) note(null)
    }

    /**
     * Open a GATT link so the RSSI can be read from the connection itself. The peripheral stops
     * advertising for the duration on most stacks, which is exactly the trade-off the screen
     * explains: a connected reading is a true link measurement, an advertisement is not.
     */
    fun connect() {
        val target = address ?: return
        if (!gatt.hasConnectPermission()) {
            note("Nearby devices permission (BLUETOOTH_CONNECT) has not been granted.")
            return
        }
        ownsLink = true
        scope.launch {
            try {
                gatt.connect(target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                ownsLink = false
                note(error.message ?: "Connecting to $target failed.")
            }
        }
    }

    fun disconnect() {
        ownsLink = false
        linkPhy = null
        connectedRssi = null
        gatt.disconnect()
    }

    private fun resetEvidence() {
        analyzer.reset()
        connectable = null
        name = null
        primaryPhy = null
        secondaryPhy = null
        legacy = null
        linkPhy = null
        connectedRssi = null
        lastError = null
    }

    private fun preflight(): String? = when {
        !hasScanPermission() -> "Nearby devices permission (BLUETOOTH_SCAN) has not been granted."
        bluetoothManager.adapter == null -> "This device has no Bluetooth adapter."
        bluetoothManager.adapter?.isEnabled != true -> "Bluetooth is switched off."
        bluetoothManager.adapter?.bluetoothLeScanner == null -> "The Bluetooth LE scanner is unavailable right now."
        else -> null
    }

    private suspend fun runMonitor(address: String, run: Int) {
        try {
            merge(packets(address), ticks(), links(), linkSamples(address)).collect { signal ->
                when (signal) {
                    is Signal.Packet -> fold(signal.result)
                    is Signal.LinkRssi -> {
                        analyzer.add(SignalSample(nowNanos(), signal.rssi, SignalSource.CONNECTION))
                        connectedRssi = signal.rssi
                        _pulses.tryEmit(Unit)
                    }
                    is Signal.LinkPhy -> linkPhy = signal.pair
                    is Signal.Link -> if (!linked(signal.state, address)) {
                        linkPhy = null
                        connectedRssi = null
                    }
                    is Signal.Failure -> lastError = signal.message
                    Signal.Tick -> publish()
                }
            }
        } finally {
            if (generation == run) {
                job = null
                publish()
            }
        }
    }

    private sealed interface Signal {
        class Packet(val result: ScanResult) : Signal
        class LinkRssi(val rssi: Int) : Signal
        class LinkPhy(val pair: PhyPair) : Signal
        class Link(val state: ConnectionState) : Signal
        class Failure(val message: String) : Signal
        data object Tick : Signal
    }

    private fun packets(address: String): Flow<Signal> = callbackFlow {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(Signal.Failure("The Bluetooth LE scanner is unavailable right now."))
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(Signal.Packet(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) trySend(Signal.Packet(result))
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(Signal.Failure(ScannerRepository.scanFailureMessage(errorCode)))
            }
        }
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings = ScanSettings.Builder()
            // Placement work is a walk around a room: every packet, as soon as the radio hears it.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .setReportDelay(0L)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (error: SecurityException) {
            trySend(Signal.Failure("Scanning was refused: ${error.message ?: "missing permission"}."))
            close()
            return@callbackFlow
        } catch (error: IllegalStateException) {
            trySend(Signal.Failure("Scanning could not start: ${error.message ?: "Bluetooth is off"}."))
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }.buffer(PACKET_BUFFER, BufferOverflow.DROP_OLDEST)

    private fun ticks(): Flow<Signal> = flow {
        while (true) {
            delay(TICK_MS)
            emit(Signal.Tick)
        }
    }

    private fun links(): Flow<Signal> = gatt.state.map { Signal.Link(it) }

    /**
     * Connected RSSI, paced by the read itself: the GATT queue allows one outstanding request, so
     * a slow peripheral stretches the interval instead of piling requests up behind it.
     */
    private fun linkSamples(address: String): Flow<Signal> = flow {
        var describedLink = false
        var reportedFailure = false
        while (true) {
            delay(RSSI_POLL_MS)
            if (!linked(gatt.state.value, address)) {
                describedLink = false
                reportedFailure = false
                continue
            }
            if (!describedLink) {
                describedLink = true
                val phy = runCatching { gatt.readPhy() }
                phy.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                phy.getOrNull()?.let { emit(Signal.LinkPhy(it)) }
            }
            val read = runCatching { gatt.readRemoteRssi() }
            val failure = read.exceptionOrNull()
            when {
                failure is CancellationException -> throw failure
                // Once per link: a peripheral that refuses the read refuses it four times a second,
                // and the operator only needs telling the first time.
                failure != null -> if (!reportedFailure) {
                    reportedFailure = true
                    emit(Signal.Failure("Connected RSSI read failed: ${failure.message ?: failure.javaClass.simpleName}."))
                }
                else -> read.getOrNull()?.let { emit(Signal.LinkRssi(it)) }
            }
        }
    }

    private fun fold(result: ScanResult) {
        val record = result.scanRecord
        analyzer.add(
            SignalSample(
                atNanos = result.timestampNanos,
                rssi = result.rssi,
                source = SignalSource.ADVERTISEMENT,
                txPower = ScannerRepository.txPowerOf(result, record),
            ),
        )
        connectable = result.isConnectable
        primaryPhy = result.primaryPhy
        secondaryPhy = result.secondaryPhy
        legacy = result.isLegacy
        record?.deviceName?.takeIf { it.isNotBlank() }?.let { name = it }
        _pulses.tryEmit(Unit)
    }

    private fun publish() {
        if (analyzer.proxyMarginDb != marginDb) analyzer.proxyMarginDb = marginDb
        val now = nowNanos()
        val target = address
        val connected = target != null && linked(gatt.state.value, target)
        _snapshot.value = SignalSnapshot(
            stats = analyzer.stats(now),
            history = analyzer.history(now),
            presence = analyzer.presence(now),
            connectable = connectable,
            name = name,
            phy = SignalPhy(primaryPhy, secondaryPhy, legacy, linkPhy.takeIf { connected }),
            connected = connected,
            connectedRssi = connectedRssi.takeIf { connected },
            error = lastError,
        )
    }

    /** Records [message] and, when no ticker is running, publishes it immediately. */
    private fun note(message: String?) {
        lastError = message
        if (!monitoring) publish()
    }

    private fun linked(state: ConnectionState, address: String): Boolean =
        state is ConnectionState.Connected && state.address.equals(address, ignoreCase = true)

    private companion object {
        /** 10 Hz: fast enough to feel live, slow enough that one map of stats per frame is free. */
        const val TICK_MS = 100L
        const val RSSI_POLL_MS = 250L
        const val PULSE_BUFFER = 64
        const val PACKET_BUFFER = 256

        fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
    }
}
