package dev.nphil.blestudio.hci

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import dev.nphil.blestudio.model.CaptureEnvironment
import dev.nphil.blestudio.shell.ShellUnavailableException
import dev.nphil.blestudio.shell.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The two snoop modes worth offering; AOSP also knows `filtered`, which truncates ACL payloads. */
enum class SnoopMode(val property: String) {
    FULL("full"),
    DISABLED("disabled"),
}

/** Everything the capability probe could learn about this device's shell. */
data class SnoopCapabilities(
    val shellIdentity: String = "",
    val snoopMode: String = "",
    val logDirectory: String = "",
    val logDirectoryReadable: Boolean = false,
    val bluetoothManagerShell: Boolean = false,
    val bluetoothManagerDumpsys: Boolean = false,
    val bugreportz: String? = null,
    val probedAtEpochMs: Long = 0,
    val error: String? = null,
) {
    val snoopModeIsFull: Boolean get() = snoopMode.equals("full", ignoreCase = true)
    val probed: Boolean get() = probedAtEpochMs > 0
}

data class SnoopModeResult(val requested: SnoopMode, val applied: Boolean, val observed: String, val detail: String)

data class BluetoothRestartResult(val ok: Boolean, val steps: List<String>, val error: String? = null)

enum class CollectStage { DIRECT_FILE, DUMPSYS, BUGREPORT, EXTRACT, DECODE, DONE, FAILED }

data class CollectProgress(val stage: CollectStage, val message: String, val percent: Int? = null)

data class CollectAttempt(val label: String, val ok: Boolean, val detail: String)

data class CollectResult(
    val btsnoop: File? = null,
    val source: String = "",
    val artifacts: List<File> = emptyList(),
    val attempts: List<CollectAttempt> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Drives Android's HCI snoop capture through the Shizuku shell.
 *
 * Every command is a constant in [Argv]; nothing here accepts a command from the UI. The one
 * runtime-derived argument is the bugreport path that `bugreportz -p` itself prints, and it is
 * validated before use (and passed as its own argv element, so there is no shell to inject into).
 */
class SnoopController(
    context: Context,
    private val shell: ShizukuGateway,
    private val adapter: BluetoothAdapter?,
) {

    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.cacheDir, "hci")

    /** Exact argument vectors used by this controller. */
    object Argv {
        val ID = listOf("id")
        val GET_SNOOP_MODE = listOf("getprop", "persist.bluetooth.btsnooplogmode")
        val LIST_LOG_DIR = listOf("ls", "-l", "/data/misc/bluetooth/logs/")
        val DUMPSYS_HELP = listOf("dumpsys", "bluetooth_manager", "--help")
        val BLUETOOTH_MANAGER_HELP = listOf("cmd", "bluetooth_manager", "help")
        val BUGREPORTZ_VERSION = listOf("bugreportz", "-v")

        fun setSnoopMode(mode: SnoopMode) = listOf("setprop", "persist.bluetooth.btsnooplogmode", mode.property)

        val SVC_BLUETOOTH_DISABLE = listOf("svc", "bluetooth", "disable")
        val SVC_BLUETOOTH_ENABLE = listOf("svc", "bluetooth", "enable")
        val CMD_BLUETOOTH_DISABLE = listOf("cmd", "bluetooth_manager", "disable")
        val CMD_BLUETOOTH_ENABLE = listOf("cmd", "bluetooth_manager", "enable")

        val CAT_SNOOP_LOG = listOf("cat", "/data/misc/bluetooth/logs/btsnoop_hci.log")
        val CAT_SNOOP_LOG_LAST = listOf("cat", "/data/misc/bluetooth/logs/btsnoop_hci.log.last")
        val DUMPSYS_BLUETOOTH = listOf("dumpsys", "bluetooth_manager")
        val BUGREPORTZ_STREAM = listOf("bugreportz", "-s")
        val BUGREPORTZ_PROGRESS = listOf("bugreportz", "-p")

        fun cat(path: String) = listOf("cat", path)

        val LOGCAT = listOf(
            "logcat", "-v", "epoch", "-b", "main,system",
            "-s", "bt_stack:V", "bluetooth:V", "BtGatt.GattService:V", "BluetoothGatt:V",
        )
    }

    // ------------------------------------------------------------------ capability probe

    suspend fun probe(): SnoopCapabilities {
        return try {
            val identity = shell.run(Argv.ID, SHORT_TIMEOUT)
            val mode = shell.run(Argv.GET_SNOOP_MODE, SHORT_TIMEOUT)
            val listing = shell.run(Argv.LIST_LOG_DIR, SHORT_TIMEOUT)
            val dumpsysHelp = shell.run(Argv.DUMPSYS_HELP, SHORT_TIMEOUT)
            val managerHelp = shell.run(Argv.BLUETOOTH_MANAGER_HELP, SHORT_TIMEOUT)
            val bugreportz = shell.run(Argv.BUGREPORTZ_VERSION, SHORT_TIMEOUT)
            SnoopCapabilities(
                shellIdentity = identity.text.ifBlank { identity.failure },
                snoopMode = mode.text.ifBlank { "(unset)" },
                logDirectory = listing.text.ifBlank { listing.failure },
                logDirectoryReadable = listing.succeeded && listing.text.isNotBlank(),
                bluetoothManagerShell = managerHelp.succeeded || managerHelp.text.contains("enable"),
                bluetoothManagerDumpsys = dumpsysHelp.succeeded || dumpsysHelp.text.isNotBlank(),
                bugreportz = bugreportz.text.takeIf { bugreportz.succeeded && it.isNotBlank() },
                probedAtEpochMs = System.currentTimeMillis(),
            )
        } catch (e: ShellUnavailableException) {
            SnoopCapabilities(probedAtEpochMs = System.currentTimeMillis(), error = e.message)
        }
    }

    suspend fun setSnoopMode(mode: SnoopMode): SnoopModeResult {
        return try {
            val write = shell.run(Argv.setSnoopMode(mode), SHORT_TIMEOUT)
            val read = shell.run(Argv.GET_SNOOP_MODE, SHORT_TIMEOUT)
            val observed = read.text
            val applied = observed.equals(mode.property, ignoreCase = true)
            val detail = when {
                applied -> "persist.bluetooth.btsnooplogmode = $observed"
                !write.succeeded -> "setprop failed: ${write.failure}"
                else -> "setprop reported success but the property still reads \"$observed\""
            }
            SnoopModeResult(mode, applied, observed, detail)
        } catch (e: ShellUnavailableException) {
            SnoopModeResult(mode, false, "", e.message ?: "Shell unavailable")
        }
    }

    /**
     * Cycles the adapter so a new snoop mode takes effect, waiting for real adapter states rather
     * than assuming the command worked.
     */
    suspend fun restartBluetooth(): BluetoothRestartResult {
        val steps = ArrayList<String>(6)
        return try {
            val off = powerCommand(Argv.SVC_BLUETOOTH_DISABLE, Argv.CMD_BLUETOOTH_DISABLE, steps)
            if (!off) return BluetoothRestartResult(false, steps, "No command could turn Bluetooth off")
            if (!awaitAdapterState(BluetoothAdapter.STATE_OFF)) {
                return BluetoothRestartResult(false, steps, "Bluetooth did not reach OFF within ${STATE_TIMEOUT_MS / 1000}s")
            }
            steps += "Adapter reported OFF"
            val on = powerCommand(Argv.SVC_BLUETOOTH_ENABLE, Argv.CMD_BLUETOOTH_ENABLE, steps)
            if (!on) return BluetoothRestartResult(false, steps, "No command could turn Bluetooth back on")
            if (!awaitAdapterState(BluetoothAdapter.STATE_ON)) {
                return BluetoothRestartResult(false, steps, "Bluetooth did not reach ON within ${STATE_TIMEOUT_MS / 1000}s")
            }
            steps += "Adapter reported ON"
            BluetoothRestartResult(true, steps)
        } catch (e: ShellUnavailableException) {
            BluetoothRestartResult(false, steps, e.message)
        }
    }

    private suspend fun powerCommand(primary: List<String>, fallback: List<String>, steps: MutableList<String>): Boolean {
        val first = shell.run(primary, POWER_TIMEOUT)
        if (first.succeeded) {
            steps += "${primary.joinToString(" ")} → ok"
            return true
        }
        steps += "${primary.joinToString(" ")} → ${first.failure}"
        val second = shell.run(fallback, POWER_TIMEOUT)
        steps += "${fallback.joinToString(" ")} → ${if (second.succeeded) "ok" else second.failure}"
        return second.succeeded
    }

    private suspend fun awaitAdapterState(target: Int): Boolean {
        val adapter = adapter ?: return false
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (adapter.state == target) return true
            delay(250)
        }
        return adapter.state == target
    }

    // ------------------------------------------------------------------ collection

    /**
     * Walks the collection strategies in order of fidelity and cost, reporting every step.
     *
     * 1. `cat` the live snoop file — cheap, usually blocked by SELinux, but free when it works.
     * 2. `dumpsys bluetooth_manager` — some builds embed the base64 btsnooz summary there.
     * 3. `bugreportz -s` (falling back to `-p`) — the AOSP-sanctioned path; the zip may hold the
     *    real snoop file under `FS/`, otherwise the btsnooz summary inside the main text report.
     *
     * Runs on [Dispatchers.IO]: between the shell round trips this walks the cache directory,
     * sniffs magic bytes and deletes rejected artefacts, none of which belongs on the main thread.
     * [onProgress] is therefore invoked from a worker thread.
     */
    suspend fun collect(onProgress: (CollectProgress) -> Unit): CollectResult =
        withContext(Dispatchers.IO) { collectInternal(onProgress) }

    private suspend fun collectInternal(onProgress: (CollectProgress) -> Unit): CollectResult {
        val attempts = ArrayList<CollectAttempt>(4)
        val artifacts = ArrayList<File>(3)
        cacheRoot.mkdirs()
        val stamp = System.currentTimeMillis()

        try {
            // ---- 1: direct file
            for ((label, argv) in listOf(
                "cat btsnoop_hci.log" to Argv.CAT_SNOOP_LOG,
                "cat btsnoop_hci.log.last" to Argv.CAT_SNOOP_LOG_LAST,
            )) {
                onProgress(CollectProgress(CollectStage.DIRECT_FILE, "Trying ${argv.joinToString(" ")}"))
                val target = File(cacheRoot, "direct-$stamp-${argv.last().substringAfterLast('/')}")
                val result = shell.runToFile(argv, DIRECT_TIMEOUT, target, MAX_SNOOP_BYTES)
                if (result.succeeded && hasBtsnoopMagic(target)) {
                    attempts += CollectAttempt(label, true, "${target.length()} bytes")
                    artifacts += target
                    onProgress(CollectProgress(CollectStage.DONE, "Read the live snoop file directly"))
                    return CollectResult(target, label, artifacts, attempts)
                }
                target.delete()
                attempts += CollectAttempt(
                    label,
                    false,
                    if (result.succeeded) "output was not a btsnoop file" else result.failure,
                )
            }

            // ---- 2: dumpsys
            onProgress(CollectProgress(CollectStage.DUMPSYS, "Checking dumpsys bluetooth_manager for a btsnooz summary"))
            val dump = File(cacheRoot, "dumpsys-$stamp.txt")
            val dumpResult = shell.runToFile(Argv.DUMPSYS_BLUETOOTH, DUMPSYS_TIMEOUT, dump, MAX_TEXT_ARTIFACT)
            if (dumpResult.bytes > 0) {
                val snooz = withContext(Dispatchers.IO) {
                    runCatching { dump.inputStream().buffered(READ_BUFFER).use { BtsnoozDecoder.extract(it) } }
                }
                val block = snooz.getOrNull()
                if (block != null) {
                    attempts += CollectAttempt("dumpsys bluetooth_manager", true, "${block.size} bytes of btsnooz")
                    artifacts += dump
                    return decodeSnooz(block, "dumpsys bluetooth_manager", stamp, artifacts, attempts, onProgress)
                }
                attempts += CollectAttempt(
                    "dumpsys bluetooth_manager",
                    false,
                    snooz.exceptionOrNull()?.message ?: "no ${BtsnoozDecoder.BEGIN_MARKER} block",
                )
            } else {
                attempts += CollectAttempt("dumpsys bluetooth_manager", false, dumpResult.failure)
            }
            dump.delete()

            // ---- 3: bugreport
            val zip = File(cacheRoot, "bugreport-$stamp.zip")
            val zipped = captureBugreport(zip, attempts, onProgress)
            if (zipped == null) {
                onProgress(CollectProgress(CollectStage.FAILED, "No capture path succeeded"))
                return CollectResult(
                    attempts = attempts,
                    artifacts = artifacts,
                    error = attempts.filter { !it.ok }.joinToString("; ") { "${it.label}: ${it.detail}" },
                )
            }
            artifacts += zipped

            onProgress(CollectProgress(CollectStage.EXTRACT, "Searching the bugreport for Bluetooth logs"))
            val found = withContext(Dispatchers.IO) { scanBugreportZip(zipped, stamp) }
            if (found.snoopFile != null) {
                attempts += CollectAttempt("bugreport FS copy", true, "${found.snoopFile.length()} bytes from ${found.snoopEntry}")
                artifacts += found.snoopFile
                onProgress(CollectProgress(CollectStage.DONE, "Extracted ${found.snoopEntry}"))
                return CollectResult(found.snoopFile, "bugreport: ${found.snoopEntry}", artifacts, attempts)
            }
            if (found.snooz != null) {
                attempts += CollectAttempt("bugreport btsnooz summary", true, "${found.snooz.size} bytes from ${found.snoozEntry}")
                return decodeSnooz(found.snooz, "bugreport: ${found.snoozEntry}", stamp, artifacts, attempts, onProgress)
            }
            attempts += CollectAttempt(
                "bugreport contents",
                false,
                "neither FS/data/misc/bluetooth/logs/btsnoop_hci.log nor a ${BtsnoozDecoder.BEGIN_MARKER} block " +
                    "(scanned ${found.entriesScanned} entries)",
            )
            onProgress(CollectProgress(CollectStage.FAILED, "The bugreport contained no Bluetooth snoop data"))
            return CollectResult(
                attempts = attempts,
                artifacts = artifacts,
                error = "The bugreport contained no Bluetooth snoop data. Enable Full HCI logging, restart " +
                    "Bluetooth, reproduce the action, then collect again.",
            )
        } catch (e: ShellUnavailableException) {
            onProgress(CollectProgress(CollectStage.FAILED, e.message ?: "Shell unavailable"))
            return CollectResult(attempts = attempts, artifacts = artifacts, error = e.message)
        }
    }

    private suspend fun captureBugreport(
        zip: File,
        attempts: MutableList<CollectAttempt>,
        onProgress: (CollectProgress) -> Unit,
    ): File? {
        onProgress(CollectProgress(CollectStage.BUGREPORT, "Running bugreportz -s (this takes minutes)"))
        val streamed = shell.runToFile(
            argv = Argv.BUGREPORTZ_STREAM,
            timeoutMs = BUGREPORT_TIMEOUT,
            target = zip,
            maxBytes = MAX_BUGREPORT_BYTES,
            onBytes = { bytes ->
                onProgress(CollectProgress(CollectStage.BUGREPORT, "bugreportz -s: ${bytes / (1024 * 1024)} MiB received"))
            },
        )
        if (streamed.succeeded && hasZipMagic(zip)) {
            attempts += CollectAttempt("bugreportz -s", true, "${zip.length()} bytes")
            return zip
        }
        attempts += CollectAttempt(
            "bugreportz -s",
            false,
            if (streamed.bytes > 0) "output was not a zip (${streamed.bytes} bytes)" else streamed.failure,
        )
        zip.delete()

        onProgress(CollectProgress(CollectStage.BUGREPORT, "Falling back to bugreportz -p"))
        var path: String? = null
        var failure: String? = null
        val finished = withTimeoutOrNull(BUGREPORT_TIMEOUT) {
            shell.runStreaming(Argv.BUGREPORTZ_PROGRESS).buffer(64).collect { line ->
                when {
                    line.startsWith("PROGRESS:") -> {
                        val fraction = line.removePrefix("PROGRESS:").trim().split('/')
                        val percent = fraction.getOrNull(0)?.toIntOrNull()?.let { done ->
                            val total = fraction.getOrNull(1)?.toIntOrNull() ?: 100
                            if (total > 0) done * 100 / total else null
                        }
                        onProgress(CollectProgress(CollectStage.BUGREPORT, "bugreportz -p: ${percent ?: 0}%", percent))
                    }

                    line.startsWith("OK:") -> path = line.removePrefix("OK:").trim()
                    line.startsWith("FAIL:") -> failure = line.removePrefix("FAIL:").trim()
                }
            }
        }
        val reported = path
        if (reported == null || !BUGREPORT_PATH.matches(reported)) {
            attempts += CollectAttempt(
                "bugreportz -p",
                false,
                failure ?: reported?.let { "unusable path \"$it\"" } ?: if (finished == null) "timed out" else "no OK: line",
            )
            return null
        }
        onProgress(CollectProgress(CollectStage.BUGREPORT, "Copying $reported"))
        val copied = shell.runToFile(Argv.cat(reported), COPY_TIMEOUT, zip, MAX_BUGREPORT_BYTES)
        if (copied.succeeded && hasZipMagic(zip)) {
            attempts += CollectAttempt("bugreportz -p", true, "${zip.length()} bytes from $reported")
            return zip
        }
        attempts += CollectAttempt("bugreportz -p", false, "could not copy $reported: ${copied.failure}")
        zip.delete()
        return null
    }

    private suspend fun decodeSnooz(
        snooz: ByteArray,
        source: String,
        stamp: Long,
        artifacts: MutableList<File>,
        attempts: MutableList<CollectAttempt>,
        onProgress: (CollectProgress) -> Unit,
    ): CollectResult {
        onProgress(CollectProgress(CollectStage.DECODE, "Decoding btsnooz stream"))
        val target = File(cacheRoot, "btsnooz-$stamp.log")
        return withContext(Dispatchers.IO) {
            runCatching {
                BufferedOutputStream(FileOutputStream(target), READ_BUFFER).use { out ->
                    BtsnoozDecoder.decodeTo(snooz, out)
                }
            }.fold(
                onSuccess = { result ->
                    artifacts += target
                    attempts += CollectAttempt("btsnooz v${result.version}", true, "${result.records} records")
                    onProgress(CollectProgress(CollectStage.DONE, "Decoded ${result.records} btsnooz records"))
                    CollectResult(target, source, artifacts, attempts, result.warnings + BTSNOOZ_CAVEAT)
                },
                onFailure = { error ->
                    target.delete()
                    attempts += CollectAttempt("btsnooz decode", false, error.message ?: error.toString())
                    onProgress(CollectProgress(CollectStage.FAILED, "btsnooz decode failed"))
                    CollectResult(attempts = attempts, artifacts = artifacts, error = error.message)
                },
            )
        }
    }

    private class ZipFindings(
        val snoopFile: File?,
        val snoopEntry: String?,
        val snooz: ByteArray?,
        val snoozEntry: String?,
        val entriesScanned: Int,
    )

    /**
     * One streaming pass over the bugreport zip. The FS copy of the snoop file wins when present
     * because it is the unfiltered capture; the btsnooz summary is the fallback.
     */
    private fun scanBugreportZip(zip: File, stamp: Long): ZipFindings {
        var snoopFile: File? = null
        var snoopEntry: String? = null
        var snooz: ByteArray? = null
        var snoozEntry: String? = null
        var entries = 0
        ZipInputStream(BufferedInputStream(zip.inputStream(), READ_BUFFER)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries++
                val name = entry.name
                when {
                    entry.isDirectory -> Unit

                    snoopFile == null && name.contains(SNOOP_IN_ZIP) && !name.endsWith(".last") -> {
                        val target = File(cacheRoot, "bugreport-$stamp-btsnoop.log")
                        val bytes = copyEntry(zin, target, MAX_SNOOP_BYTES)
                        if (bytes > 0 && hasBtsnoopMagic(target)) {
                            snoopFile = target
                            snoopEntry = name
                        } else {
                            target.delete()
                        }
                    }

                    snooz == null && name.endsWith(".txt", ignoreCase = true) -> {
                        val block = runCatching { BtsnoozDecoder.extract(NonClosing(zin)) }.getOrNull()
                        if (block != null && block.isNotEmpty()) {
                            snooz = block
                            snoozEntry = name
                        }
                    }
                }
                zin.closeEntry()
            }
        }
        return ZipFindings(snoopFile, snoopEntry, snooz, snoozEntry, entries)
    }

    private fun copyEntry(source: InputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(READ_BUFFER)
        FileOutputStream(target).use { out ->
            while (total < maxBytes) {
                val read = source.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    // ------------------------------------------------------------------ import

    /**
     * Accepts whatever the operator picked: a raw btsnoop log, a bugreport zip, or a bugreport
     * text file with a btsnooz summary.
     */
    suspend fun importFrom(input: InputStream, displayName: String, onProgress: (CollectProgress) -> Unit): CollectResult =
        withContext(Dispatchers.IO) {
            cacheRoot.mkdirs()
            val stamp = System.currentTimeMillis()
            val attempts = ArrayList<CollectAttempt>(2)
            val artifacts = ArrayList<File>(2)
            val staged = File(cacheRoot, "import-$stamp-${displayName.take(60).replace(SAFE_NAME, "_")}")
            onProgress(CollectProgress(CollectStage.EXTRACT, "Copying $displayName"))
            val bytes = input.use { copyEntry(it, staged, MAX_BUGREPORT_BYTES) }
            if (bytes == 0L) {
                staged.delete()
                return@withContext CollectResult(error = "$displayName was empty")
            }
            artifacts += staged

            if (hasBtsnoopMagic(staged)) {
                attempts += CollectAttempt("imported btsnoop", true, "$bytes bytes")
                onProgress(CollectProgress(CollectStage.DONE, "Imported a btsnoop capture"))
                return@withContext CollectResult(staged, "imported $displayName", artifacts, attempts)
            }
            if (hasZipMagic(staged)) {
                onProgress(CollectProgress(CollectStage.EXTRACT, "Searching $displayName"))
                val found = scanBugreportZip(staged, stamp)
                if (found.snoopFile != null) {
                    artifacts += found.snoopFile
                    attempts += CollectAttempt("imported bugreport", true, found.snoopEntry.orEmpty())
                    onProgress(CollectProgress(CollectStage.DONE, "Extracted ${found.snoopEntry}"))
                    return@withContext CollectResult(found.snoopFile, "imported ${found.snoopEntry}", artifacts, attempts)
                }
                if (found.snooz != null) {
                    return@withContext decodeSnooz(found.snooz, "imported ${found.snoozEntry}", stamp, artifacts, attempts, onProgress)
                }
                return@withContext CollectResult(
                    attempts = attempts,
                    artifacts = artifacts,
                    error = "$displayName is a zip with no Bluetooth snoop data (${found.entriesScanned} entries)",
                )
            }
            val block = runCatching { staged.inputStream().buffered(READ_BUFFER).use { BtsnoozDecoder.extract(it) } }
            val snooz = block.getOrNull()
            if (snooz != null) {
                return@withContext decodeSnooz(snooz, "imported $displayName", stamp, artifacts, attempts, onProgress)
            }
            CollectResult(
                attempts = attempts,
                artifacts = artifacts,
                error = block.exceptionOrNull()?.message
                    ?: "$displayName is neither a btsnoop capture, a bugreport zip, nor a bugreport containing " +
                    BtsnoozDecoder.BEGIN_MARKER,
            )
        }

    // ------------------------------------------------------------------ logcat

    /**
     * Streams the Bluetooth stack log into [target] until the collector is cancelled or [maxBytes]
     * is reached. Cancelling the calling coroutine destroys the remote `logcat`.
     */
    suspend fun streamLogcat(
        target: File = File(cacheRoot, "logcat-${System.currentTimeMillis()}.txt"),
        maxBytes: Long = MAX_LOGCAT_BYTES,
        onLine: ((String) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        cacheRoot.mkdirs()
        var written = 0L
        BufferedOutputStream(FileOutputStream(target), READ_BUFFER).use { out ->
            shell.runStreaming(Argv.LOGCAT)
                .buffer(512)
                .takeWhile { line -> written + line.length + 1 <= maxBytes }
                .collect { line ->
                    val bytes = (line + "\n").toByteArray()
                    out.write(bytes)
                    written += bytes.size
                    onLine?.invoke(line)
                }
        }
        target
    }

    // ------------------------------------------------------------------ environment

    fun environment(vendorPackage: String?, vendorVersion: String?, snoopMode: String?) = CaptureEnvironment(
        androidSdk = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE ?: "",
        buildFingerprint = Build.FINGERPRINT ?: "",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        vendorAppPackage = vendorPackage,
        vendorAppVersion = vendorVersion,
        hciSnoopMode = snoopMode,
    )

    private companion object {
        const val SHORT_TIMEOUT = 8_000L
        const val POWER_TIMEOUT = 15_000L
        const val DIRECT_TIMEOUT = 60_000L
        const val DUMPSYS_TIMEOUT = 120_000L
        const val COPY_TIMEOUT = 300_000L
        const val BUGREPORT_TIMEOUT = 480_000L
        const val STATE_TIMEOUT_MS = 10_000L

        const val READ_BUFFER = 64 * 1024
        const val MAX_SNOOP_BYTES = 64L * 1024 * 1024
        const val MAX_TEXT_ARTIFACT = 64L * 1024 * 1024
        const val MAX_BUGREPORT_BYTES = 512L * 1024 * 1024
        const val MAX_LOGCAT_BYTES = 16L * 1024 * 1024

        const val SNOOP_IN_ZIP = "data/misc/bluetooth/logs/btsnoop_hci.log"
        val BUGREPORT_PATH = Regex("^/[A-Za-z0-9._/@+-]{1,255}\\.zip$")
        val SAFE_NAME = Regex("[^A-Za-z0-9._-]")

        /** btsnooz is Android's in-memory summary, not the on-disk log; say so before conclusions. */
        const val BTSNOOZ_CAVEAT = "These events came from Android's in-memory btsnooz summary: it is a bounded " +
            "ring buffer of the most recent traffic, and only snoop mode \"full\" keeps whole ACL payloads."

        fun hasBtsnoopMagic(file: File): Boolean = matchesMagic(file, BTSNOOP_MAGIC)

        fun hasZipMagic(file: File): Boolean = matchesMagic(file, ZIP_MAGIC)

        val BTSNOOP_MAGIC = byteArrayOf(0x62, 0x74, 0x73, 0x6E, 0x6F, 0x6F, 0x70, 0x00)
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        fun matchesMagic(file: File, magic: ByteArray): Boolean {
            if (!file.isFile || file.length() < magic.size) return false
            return runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(magic.size)
                    var read = 0
                    while (read < head.size) {
                        val step = input.read(head, read, head.size - read)
                        if (step < 0) return@use false
                        read += step
                    }
                    head.contentEquals(magic)
                }
            }.getOrDefault(false)
        }
    }
}

/** Wraps a zip entry stream so a reader cannot close the underlying [ZipInputStream]. */
private class NonClosing(stream: InputStream) : FilterInputStream(stream) {
    override fun close() = Unit
}
