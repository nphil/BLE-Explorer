package dev.nphil.blueshark.hci

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import dev.nphil.blueshark.model.CaptureEnvironment
import dev.nphil.blueshark.shell.ShellResult
import dev.nphil.blueshark.shell.ShellUnavailableException
import dev.nphil.blueshark.shell.ShizukuGateway
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.zip.ZipInputStream

/** The two snoop modes worth offering; AOSP also knows `filtered`, which truncates ACL payloads. */
enum class SnoopMode(val property: String) {
    FULL("full"),
    DISABLED("disabled"),
}

/** Everything the capability probe could learn about this device's shell. */
data class SnoopCapabilities(
    val shellIdentity: String = "",
    /** Raw `persist.bluetooth.btsnooplogmode`; empty when unset. */
    val snoopMode: String = "",
    /** `getprop` lines mentioning "snoop" plus `ro.debuggable`: what the Settings toggle actually wrote. */
    val snoopProperties: List<String> = emptyList(),
    /**
     * The newest "Snoop Logs ... " line the Bluetooth stack printed to logcat. The stack logs its
     * mode every time the adapter starts, so this is ground truth from inside the stack — it does
     * not depend on which property an OEM's Settings toggle writes or on what shell may read.
     */
    val stackSnoopLog: String = "",
    /**
     * `sSnoopLogSettingAtEnable` from `dumpsys bluetooth_manager`: the mode the Bluetooth service
     * read when the adapter last came up. Present on AOSP and OEM stacks alike (HyperOS included),
     * which makes it the most portable ground truth we have; "" if the line is absent.
     */
    val serviceSnoopSetting: String = "",
    /** Broad, read-only dump of everything Bluetooth-related the shell can see; for diagnosing OEM toggles. */
    val diagnostics: String = "",
    val logDirectory: String = "",
    val logDirectoryReadable: Boolean = false,
    val bluetoothManagerShell: Boolean = false,
    val bluetoothManagerDumpsys: Boolean = false,
    val bugreportz: String? = null,
    val probedAtEpochMs: Long = 0,
    val error: String? = null,
) {
    /** Mode the stack announced at its last start: "full", "filtered", "disabled", or "" if never seen. */
    val stackSnoopMode: String
        get() = when {
            stackSnoopLog.contains("full mode enabled") -> "full"
            stackSnoopLog.contains("filtered mode enabled") -> "filtered"
            stackSnoopLog.contains("Snoop Logs disabled") -> "disabled"
            else -> ""
        }

    /**
     * What the stack would compute, mirroring `SnoopLogger::GetBtSnoopMode`: `btsnooplogmode` if
     * set, otherwise `btsnoopdefaultmode` (falling back to filtered) but only on debuggable builds,
     * otherwise disabled. Blank when the property is unreadable and nothing else applies.
     */
    val propertySnoopMode: String
        get() {
            if (snoopMode.isNotBlank()) return snoopMode
            if (propertyValue("ro.debuggable") == "1") {
                return propertyValue("persist.bluetooth.btsnoopdefaultmode")?.takeIf { it.isNotBlank() } ?: "filtered"
            }
            return ""
        }

    /** What the service recorded at enable, else the stack's logcat line, else the property view. */
    val effectiveSnoopMode: String
        get() = serviceSnoopSetting.lowercase().takeIf { it in KNOWN_MODES }
            ?: stackSnoopMode.ifBlank { propertySnoopMode }
    val snoopModeIsFull: Boolean get() = effectiveSnoopMode.equals("full", ignoreCase = true)
    val probed: Boolean get() = probedAtEpochMs > 0

    private fun propertyValue(name: String): String? =
        snoopProperties.firstOrNull { it.startsWith("[$name]: [") }
            ?.substringAfter("]: [")?.removeSuffix("]")

    private companion object {
        val KNOWN_MODES = setOf("full", "filtered", "disabled", "kernel")
    }
}

/** `sSnoopLogSettingAtEnable = FULL` -> "FULL"; "" when the dump has no such line. */
fun serviceSnoopSetting(dumpsysOutput: String): String =
    Regex("""sSnoopLogSettingAtEnable\s*=\s*(\S+)""").find(dumpsysOutput)?.groupValues?.get(1) ?: ""

/** Filters `getprop` output down to snoop-related lines (plus `ro.debuggable`), sorted for stable display. */
fun snoopPropertyLines(getpropOutput: String): List<String> =
    getpropOutput.lineSequence().map { it.trim() }
        .filter { it.contains("snoop", ignoreCase = true) || it.startsWith("[ro.debuggable]") }
        .sorted().toList()

/** Newest stack "Snoop Logs" announcement in a `logcat -d` dump, or "" when the stack never said. */
fun latestStackSnoopLine(logcatOutput: String): String =
    logcatOutput.lineSequence().lastOrNull { it.contains("Snoop Logs") }?.trim() ?: ""

/**
 * [deniedByPolicy] is true when `setprop` itself was refused. On every Android build the property
 * is SELinux-labelled `bluetooth_prop`, writable only by `system_server` and the Bluetooth stack,
 * so a shell-UID Shizuku can never set it; the Developer options toggle (which runs as system)
 * is the supported way and writes the very same property.
 */
data class SnoopModeResult(
    val requested: SnoopMode,
    val applied: Boolean,
    val observed: String,
    val detail: String,
    val deniedByPolicy: Boolean = false,
)

/** Pure decision behind [SnoopController.setSnoopMode]: what the setprop + getprop pair means. */
fun classifySnoopWrite(mode: SnoopMode, write: ShellResult, read: ShellResult): SnoopModeResult {
    val observed = read.text
    val applied = observed.equals(mode.property, ignoreCase = true)
    val detail = when {
        applied -> "persist.bluetooth.btsnooplogmode = $observed"
        !write.succeeded -> "setprop refused (${write.failure}). " +
            "Only system_server and the Bluetooth stack may write this property; a shell-UID Shizuku cannot. " +
            "Use Developer options > Enable Bluetooth HCI snoop log."
        else -> "setprop reported success but the property still reads \"$observed\""
    }
    return SnoopModeResult(mode, applied, observed, detail, deniedByPolicy = !applied && !write.succeeded)
}

data class BluetoothRestartResult(val ok: Boolean, val steps: List<String>, val error: String? = null)

enum class CollectStage { DIRECT_FILE, DUMPSYS, BUGREPORT, EXTRACT, DECODE, DONE, FAILED }

/**
 * One progress tick. [percent] is set only when the total is genuinely known; otherwise the UI
 * shows [bytes] and elapsed time rather than pretending. [startedAtMs] anchors the elapsed clock
 * for the whole collect run, not the stage.
 */
data class CollectProgress(
    val stage: CollectStage,
    val message: String,
    val percent: Int? = null,
    val bytes: Long? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
)

/** [skipped] marks a path that was not expected to work here; rendered neutrally, not as a failure. */
data class CollectAttempt(val label: String, val ok: Boolean, val detail: String, val skipped: Boolean = false)

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
        fun statSize(path: String) = listOf("stat", "-c", "%s", path)
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
        /** Whole property table; filtered in-app to the snoop keys, because vendors add their own. */
        val ALL_PROPS = listOf("getprop")
        /** Dump (not follow) of the stack's own "Snoop Logs ..." announcements; `-e` filters by regex. */
        val LOGCAT_SNOOP_MODE = listOf("logcat", "-d", "-b", "main,system", "-v", "time", "-e", "Snoop Logs")
        /** Where OEM stacks have been seen to keep snoop logs; listed (not read) for the diagnostics dump. */
        val SNOOP_DIR_CANDIDATES = listOf(
            "/data/misc/bluetooth/logs", "/data/misc/bluetooth", "/data/vendor/bluetooth", "/data/vendor/bt",
            "/data/log/bt", "/data/misc/logd", "/sdcard/MIUI/debug_log", "/sdcard/btsnoop", "/data/local/tmp",
        )
        fun listDir(path: String) = listOf("ls", "-la", path)
        val SETTINGS_GLOBAL = listOf("settings", "list", "global")
        val SETTINGS_SECURE = listOf("settings", "list", "secure")
        /** Newest stack lines mentioning snoop/btsnoop, whatever the tag; bounded by -t. */
        val LOGCAT_SNOOP_ANY = listOf("logcat", "-d", "-b", "main,system", "-v", "time", "-t", "400", "-e", "(?i)snoop")

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
            val allProps = shell.run(Argv.ALL_PROPS, SHORT_TIMEOUT)
            val listing = shell.run(Argv.LIST_LOG_DIR, SHORT_TIMEOUT)
            val dumpsysHelp = shell.run(Argv.DUMPSYS_HELP, SHORT_TIMEOUT)
            val managerHelp = shell.run(Argv.BLUETOOTH_MANAGER_HELP, SHORT_TIMEOUT)
            val bugreportz = shell.run(Argv.BUGREPORTZ_VERSION, SHORT_TIMEOUT)
            val stackLog = shell.run(Argv.LOGCAT_SNOOP_MODE, SHORT_TIMEOUT)
            val settingsGlobal = shell.run(Argv.SETTINGS_GLOBAL, SHORT_TIMEOUT)
            val settingsSecure = shell.run(Argv.SETTINGS_SECURE, SHORT_TIMEOUT)
            val dumpsys = shell.run(Argv.DUMPSYS_BLUETOOTH, SHORT_TIMEOUT)
            val snoopLogcat = shell.run(Argv.LOGCAT_SNOOP_ANY, SHORT_TIMEOUT)
            val bt = Regex("""(?i)bluetooth|snoop|\bbt[._]""")
            fun section(title: String, text: String, filter: Regex? = bt) = buildString {
                append("## ").append(title).append('\n')
                val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && (filter == null || filter.containsMatchIn(it)) }.take(80).toList()
                if (lines.isEmpty()) append("(nothing)\n") else lines.forEach { append(it).append('\n') }
            }
            val dirListings = buildString {
                for (dir in Argv.SNOOP_DIR_CANDIDATES) {
                    val r = shell.run(Argv.listDir(dir), SHORT_TIMEOUT)
                    append(dir).append(": ")
                    if (r.succeeded) r.text.lineSequence().take(12).forEach { append("\n    ").append(it) }
                    else append(r.failure.lineSequence().first())
                    append('\n')
                }
            }
            val diagnostics = section("getprop", allProps.stdout) +
                section("snoop directory candidates (ls -la)", dirListings, null) +
                section("dumpsys bluetooth_manager (log/snoop/path lines)", dumpsys.stdout, Regex("(?i)snoop|log.?path|btsnoop|logging|\\.log")) +
                section("settings global", settingsGlobal.stdout) +
                section("settings secure", settingsSecure.stdout) +
                section("logcat (snoop lines, newest 400 entries)", snoopLogcat.stdout, null)
            SnoopCapabilities(
                diagnostics = diagnostics,
                shellIdentity = identity.text.ifBlank { identity.failure },
                snoopProperties = snoopPropertyLines(allProps.stdout),
                snoopMode = mode.text,
                stackSnoopLog = latestStackSnoopLine(stackLog.stdout),
                serviceSnoopSetting = serviceSnoopSetting(dumpsys.stdout),
                logDirectory = listing.text.ifBlank { listing.failure },
                logDirectoryReadable = listing.succeeded && listing.text.isNotBlank(),
                bluetoothManagerShell = managerHelp.succeeded || managerHelp.text.contains("enable"),
                bluetoothManagerDumpsys = dumpsysHelp.succeeded || dumpsysHelp.text.isNotBlank(),
                // `bugreportz -v` prints its version but exits non-zero on some builds; the version is what counts.
                bugreportz = bugreportz.text.takeIf { it.matches(Regex("""\d+(\.\d+)*""")) }
                    ?: bugreportz.text.takeIf { bugreportz.succeeded && it.isNotBlank() }
                    ?: "not available (${bugreportz.failure})",
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
            classifySnoopWrite(mode, write, read)
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
                val decodeError = snooz.exceptionOrNull()?.message
                attempts += CollectAttempt(
                    "dumpsys bluetooth_manager",
                    false,
                    decodeError ?: "no ${BtsnoozDecoder.BEGIN_MARKER} block (expected: the stack only emits that summary while snoop logging is off)",
                    skipped = decodeError == null,
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
                attempts += CollectAttempt(
                    "bugreport FS copy",
                    true,
                    "${found.snoopFile.length()} bytes from ${found.snoopEntry}" +
                        if (found.snoopEntry.orEmpty().contains(".last")) " (a rotated file: the stack restarted since; re-run the action and collect again if your traffic is missing)" else "",
                )
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
                "no entry starting with the btsnoop magic and no ${BtsnoozDecoder.BEGIN_MARKER} block " +
                    "(scanned ${found.entriesScanned} entries). Bluetooth-related entries: " +
                    (found.bluetoothEntries.takeIf { it.isNotEmpty() }?.joinToString("\n  ", prefix = "\n  ") ?: "none"),
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
        val started = System.currentTimeMillis()
        fun tick(message: String, percent: Int? = null, bytes: Long? = null) =
            onProgress(CollectProgress(CollectStage.BUGREPORT, message, percent, bytes, started))

        // `-p` reports PROGRESS:n/m, so it goes first: a real bar. `-s` streams the zip with no
        // total, so it is the fallback and shows bytes plus elapsed time instead.
        tick("bugreportz -p: starting (a full bugreport takes one to several minutes)", percent = 0)
        var path: String? = null
        var failure: String? = null
        val finished = withTimeoutOrNull(BUGREPORT_TIMEOUT) {
            shell.runStreaming(Argv.BUGREPORTZ_PROGRESS).buffer(64).collect { line ->
                when {
                    line.startsWith("PROGRESS:") -> {
                        val fraction = line.removePrefix("PROGRESS:").trim().split('/')
                        val done = fraction.getOrNull(0)?.toLongOrNull()
                        val total = fraction.getOrNull(1)?.toLongOrNull()
                        val percent = if (done != null && total != null && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
                        tick("bugreportz -p: ${percent?.let { "$it%" } ?: line.trim()}", percent)
                    }
                    line.startsWith("OK:") -> path = line.removePrefix("OK:").trim()
                    line.startsWith("FAIL:") -> failure = line.removePrefix("FAIL:").trim()
                }
            }
        }
        val reported = path
        if (reported != null && BUGREPORT_PATH.matches(reported)) {
            val size = shell.run(Argv.statSize(reported), SHORT_TIMEOUT).text.toLongOrNull()
            tick("Copying ${reported.substringAfterLast('/')}" + (size?.let { " (${it / (1024 * 1024)} MiB)" } ?: ""), percent = size?.let { 0 }, bytes = 0)
            val copied = shell.runToFile(
                argv = Argv.cat(reported),
                timeoutMs = COPY_TIMEOUT,
                target = zip,
                maxBytes = MAX_BUGREPORT_BYTES,
                onBytes = { bytes ->
                    val percent = size?.takeIf { it > 0 }?.let { (bytes * 100 / it).toInt().coerceIn(0, 100) }
                    tick("Copying: ${bytes / (1024 * 1024)} MiB" + (size?.let { " of ${it / (1024 * 1024)} MiB" } ?: ""), percent, bytes)
                },
            )
            if (copied.succeeded && hasZipMagic(zip)) {
                attempts += CollectAttempt("bugreportz -p", true, "${zip.length()} bytes from $reported")
                return zip
            }
            attempts += CollectAttempt("bugreportz -p", false, "could not copy $reported: ${copied.failure}")
            zip.delete()
        } else {
            attempts += CollectAttempt(
                "bugreportz -p",
                false,
                failure ?: reported?.let { "unusable path \"$it\"" } ?: if (finished == null) "timed out" else "no OK: line",
            )
        }

        tick("Falling back to bugreportz -s (streams the zip; no total is reported)", bytes = 0)
        val streamed = shell.runToFile(
            argv = Argv.BUGREPORTZ_STREAM,
            timeoutMs = BUGREPORT_TIMEOUT,
            target = zip,
            maxBytes = MAX_BUGREPORT_BYTES,
            onBytes = { bytes -> tick("bugreportz -s: ${bytes / (1024 * 1024)} MiB received", bytes = bytes) },
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
        /** Every entry whose name smells of Bluetooth: what to show when nothing usable was found. */
        val bluetoothEntries: List<String>,
    )

    /**
     * One streaming pass over the bugreport zip. Any entry that starts with the btsnoop magic is
     * a capture, whatever the OEM named or placed it (AOSP: `FS/data/misc/bluetooth/logs/
     * btsnoop_hci.log`; Qualcomm/HyperOS builds differ). The current file beats a `.last`
     * rotation; the btsnooz summary in the text report is the fallback.
     */
    private fun scanBugreportZip(zip: File, stamp: Long): ZipFindings {
        var snoopFile: File? = null
        var snoopEntry: String? = null
        var snoopIsRotated = true
        var snooz: ByteArray? = null
        var snoozEntry: String? = null
        var entries = 0
        val related = ArrayList<String>()
        ZipInputStream(BufferedInputStream(zip.inputStream(), READ_BUFFER)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries++
                val name = entry.name
                if (entry.isDirectory) { zin.closeEntry(); continue }
                val smellsBluetooth = BLUETOOTH_ENTRY.containsMatchIn(name)
                if (smellsBluetooth && related.size < MAX_RELATED_ENTRIES) {
                    related += name + if (entry.size >= 0) " (${entry.size} B)" else ""
                }
                val rotated = name.endsWith(".last") || name.contains(".last.")
                val wantSnoop = smellsBluetooth && !name.endsWith(".txt", ignoreCase = true) && (snoopFile == null || (snoopIsRotated && !rotated))
                when {
                    wantSnoop -> {
                        val head = ByteArray(BTSNOOP_MAGIC.size)
                        val read = readFully(zin, head)
                        if (read == head.size && head.contentEquals(BTSNOOP_MAGIC)) {
                            val target = File(cacheRoot, "bugreport-$stamp-btsnoop${if (rotated) "-last" else ""}.log")
                            val bytes = copyEntry(SequenceInputStream(ByteArrayInputStream(head), NonClosing(zin)), target, MAX_SNOOP_BYTES)
                            if (bytes > head.size) {
                                snoopFile?.takeIf { it != target }?.delete()
                                snoopFile = target
                                snoopEntry = name
                                snoopIsRotated = rotated
                            } else {
                                target.delete()
                            }
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
        return ZipFindings(snoopFile, snoopEntry, snooz, snoozEntry, entries, related)
    }

    private fun readFully(source: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = source.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
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

        /** Entry names worth sniffing for the btsnoop magic, and worth listing when nothing matched. */
        val BLUETOOTH_ENTRY = Regex("""(?i)snoop|bluetooth|/bt[_/]|btsnoop|hci|\.cfa$""")
        const val MAX_RELATED_ENTRIES = 40
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
