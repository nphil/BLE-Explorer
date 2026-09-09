package dev.nphil.blestudio.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * One address' worth of advertising evidence, folded from every packet seen since the scan
 * started. Deeply immutable: the byte arrays are the per-packet parcels handed over by the stack
 * and are never written to after construction.
 */
@Immutable
class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val connectable: Boolean,
    val primaryPhy: Int,
    val secondaryPhy: Int,
    val legacy: Boolean,
    val serviceUuids: List<String>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<String, ByteArray>,
    val rawRecord: ByteArray?,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val packetCount: Int,
)

/** Whether the scanner is running, why it stopped, and the last stack-reported failure. */
data class ScanStatus(
    val scanning: Boolean = false,
    val continuous: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val error: String? = null,
)

/**
 * Wraps [android.bluetooth.le.BluetoothLeScanner] as a cold [callbackFlow] and folds the packet
 * stream into a per-address map.
 *
 * The fold, the 60 s prune and the auto-stop deadline all run on one coroutine (packets and ticks
 * are merged into a single stream), so the aggregate needs no locking. Snapshots are published on
 * the tick rather than per packet: a busy room produces hundreds of advertisements a second and
 * one map copy per packet would dominate the frame budget.
 */
class ScannerRepository(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope,
) {
    private val _devices = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    val devices: StateFlow<Map<String, ScannedDevice>> = _devices.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private var job: Job? = null

    /**
     * Bumped by every [start] and [stop]. A cancelled run reaches its `finally` long after the
     * next run was launched, and without this it would clear that run's job handle and status —
     * leaving `start` free to register a second `ScanCallback` while the first one is still live.
     */
    private var generation = 0

    /** True when BLUETOOTH_SCAN has been granted; scanning is impossible without it. */
    fun hasScanPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /**
     * Start scanning. [continuous] keeps the radio on indefinitely; otherwise the scan stops itself
     * after [SCAN_WINDOW_MS] because Android suppresses results from long-running foreground scans.
     */
    fun start(continuous: Boolean) {
        if (job?.isActive == true) return
        val failure = preflight()
        if (failure != null) {
            _status.value = ScanStatus(scanning = false, continuous = continuous, error = failure)
            return
        }
        val startedAt = System.currentTimeMillis()
        val run = ++generation
        _status.value = ScanStatus(scanning = true, continuous = continuous, startedAtEpochMs = startedAt, error = null)
        job = scope.launch { runScan(continuous, startedAt, run) }
    }

    /** Stop scanning; the aggregated devices stay so a capture can still be started from them. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
        _status.value = _status.value.copy(scanning = false, startedAtEpochMs = null)
    }

    /** Drop every aggregated device (the scan itself, if running, keeps going). */
    fun clear() {
        _devices.value = emptyMap()
    }

    fun acknowledgeError() {
        if (_status.value.error != null) _status.value = _status.value.copy(error = null)
    }

    fun device(address: String): ScannedDevice? = _devices.value[address]

    private fun preflight(): String? = when {
        !hasScanPermission() -> "Nearby devices permission (BLUETOOTH_SCAN) has not been granted."
        bluetoothManager.adapter == null -> "This device has no Bluetooth adapter."
        bluetoothManager.adapter?.isEnabled != true -> "Bluetooth is switched off."
        bluetoothManager.adapter?.bluetoothLeScanner == null -> "The Bluetooth LE scanner is unavailable right now."
        else -> null
    }

    private suspend fun runScan(continuous: Boolean, startedAtEpochMs: Long, run: Int) {
        val aggregate = HashMap<String, ScannedDevice>(_devices.value)
        var dirty = false
        try {
            merge(scanSignals(), ticks()).collect { signal ->
                when (signal) {
                    is ScanSignal.Advertisement -> if (fold(aggregate, signal.result, signal.receivedAtEpochMs)) dirty = true
                    is ScanSignal.Failure -> {
                        if (generation == run) {
                            _status.value = _status.value.copy(scanning = false, error = signal.message)
                        }
                        throw ScanAbort()
                    }
                    ScanSignal.Tick -> {
                        val now = System.currentTimeMillis()
                        if (prune(aggregate, now)) dirty = true
                        if (dirty) {
                            _devices.value = HashMap(aggregate)
                            dirty = false
                        }
                        if (!continuous && now - startedAtEpochMs >= SCAN_WINDOW_MS) {
                            if (generation == run) {
                                _status.value = _status.value.copy(
                                    scanning = false,
                                    startedAtEpochMs = null,
                                    error = "Scan stopped after ${SCAN_WINDOW_MS / 1000} s. Android suppresses results from " +
                                        "long foreground scans — switch on continuous scanning to override.",
                                )
                            }
                            throw ScanAbort()
                        }
                    }
                }
            }
        } catch (_: ScanAbort) {
            // Deliberate stop: status already carries the reason.
        } finally {
            if (dirty) _devices.value = HashMap(aggregate)
            // Only the current run owns the handle and the status; a superseded run must not
            // clear the one that replaced it.
            if (generation == run) {
                job = null
                if (_status.value.scanning) _status.value = _status.value.copy(scanning = false, startedAtEpochMs = null)
            }
        }
    }

    private class ScanAbort : Exception(null, null, false, false)

    private sealed interface ScanSignal {
        class Advertisement(val result: ScanResult, val receivedAtEpochMs: Long) : ScanSignal
        class Failure(val message: String) : ScanSignal
        data object Tick : ScanSignal
    }

    @SuppressLint("MissingPermission")
    private fun scanSignals(): Flow<ScanSignal> = callbackFlow {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(ScanSignal.Failure("The Bluetooth LE scanner is unavailable right now."))
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScanSignal.Advertisement(result, System.currentTimeMillis()))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val now = System.currentTimeMillis()
                for (result in results) trySend(ScanSignal.Advertisement(result, now))
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(ScanSignal.Failure(scanFailureMessage(errorCode)))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .setReportDelay(0L)
            .build()
        try {
            scanner.startScan(emptyList(), settings, callback)
        } catch (error: SecurityException) {
            trySend(ScanSignal.Failure("Scanning was refused: ${error.message ?: "missing permission"}."))
            close()
            return@callbackFlow
        } catch (error: IllegalStateException) {
            trySend(ScanSignal.Failure("Scanning could not start: ${error.message ?: "Bluetooth is off"}."))
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }.buffer(PACKET_BUFFER, BufferOverflow.DROP_OLDEST)

    private fun ticks(): Flow<ScanSignal> = flow {
        while (true) {
            delay(TICK_MS)
            emit(ScanSignal.Tick)
        }
    }

    /** Returns true when the aggregate changed in a way worth publishing. */
    private fun fold(aggregate: HashMap<String, ScannedDevice>, result: ScanResult, now: Long): Boolean {
        val address = result.device.address ?: return false
        val record = result.scanRecord
        val raw = record?.bytes
        val previous = aggregate[address]
        // Steady-state beacons repeat one payload: comparing the ≤255 byte record is far cheaper
        // than re-deriving the UUID list and the manufacturer/service maps on every packet.
        val unchangedPayload = previous != null && raw != null && previous.rawRecord != null &&
            previous.rawRecord.contentEquals(raw)
        aggregate[address] = if (unchangedPayload) {
            ScannedDevice(
                address = address,
                name = previous.name,
                rssi = result.rssi,
                txPower = txPowerOf(result, record),
                connectable = result.isConnectable,
                primaryPhy = result.primaryPhy,
                secondaryPhy = result.secondaryPhy,
                legacy = result.isLegacy,
                serviceUuids = previous.serviceUuids,
                manufacturerData = previous.manufacturerData,
                serviceData = previous.serviceData,
                rawRecord = previous.rawRecord,
                firstSeenEpochMs = previous.firstSeenEpochMs,
                lastSeenEpochMs = now,
                packetCount = previous.packetCount + 1,
            )
        } else {
            ScannedDevice(
                address = address,
                name = record?.deviceName?.takeIf { it.isNotBlank() } ?: previous?.name,
                rssi = result.rssi,
                txPower = txPowerOf(result, record),
                connectable = result.isConnectable,
                primaryPhy = result.primaryPhy,
                secondaryPhy = result.secondaryPhy,
                legacy = result.isLegacy,
                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                manufacturerData = manufacturerDataOf(record),
                serviceData = record?.serviceData
                    ?.entries
                    ?.associate { (uuid, bytes) -> uuid.uuid.toString() to bytes }
                    .orEmpty(),
                rawRecord = raw,
                firstSeenEpochMs = previous?.firstSeenEpochMs ?: now,
                lastSeenEpochMs = now,
                packetCount = (previous?.packetCount ?: 0) + 1,
            )
        }
        return true
    }

    private fun prune(aggregate: HashMap<String, ScannedDevice>, now: Long): Boolean {
        val iterator = aggregate.entries.iterator()
        var removed = false
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastSeenEpochMs > STALE_AFTER_MS) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    private fun txPowerOf(result: ScanResult, record: ScanRecord?): Int? {
        val fromResult = result.txPower
        if (fromResult != ScanResult.TX_POWER_NOT_PRESENT) return fromResult
        val fromRecord = record?.txPowerLevel ?: Int.MIN_VALUE
        return fromRecord.takeIf { it != Int.MIN_VALUE }
    }

    private fun manufacturerDataOf(record: ScanRecord?): Map<Int, ByteArray> {
        val sparse = record?.manufacturerSpecificData ?: return emptyMap()
        val size = sparse.size()
        if (size == 0) return emptyMap()
        val map = LinkedHashMap<Int, ByteArray>(size)
        for (index in 0 until size) {
            val bytes = sparse.valueAt(index) ?: continue
            map[sparse.keyAt(index)] = bytes
        }
        return map
    }

    companion object {
        /** Android silently stops reporting results for foreground scans that run past this. */
        const val SCAN_WINDOW_MS = 60_000L
        const val STALE_AFTER_MS = 60_000L
        private const val TICK_MS = 250L
        private const val PACKET_BUFFER = 512

        fun scanFailureMessage(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
                "A scan is already running for this app."
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "The Bluetooth stack refused to register this app for scanning. Toggle Bluetooth and retry."
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                "The Bluetooth stack reported an internal error."
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                "This phone's radio does not support the requested scan settings."
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                "The radio is out of scan slots — close other apps that are scanning."
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                "Android is throttling scans (5 starts per 30 s). Wait about 30 s before scanning again."
            else -> "Scan failed with code $code."
        }
    }
}
