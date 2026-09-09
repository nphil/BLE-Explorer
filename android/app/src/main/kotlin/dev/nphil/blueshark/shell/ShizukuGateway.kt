package dev.nphil.blueshark.shell

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Package name of the Shizuku manager app, used for the install/open deep links. */
const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Exit code reported when the process was force-killed after [ShizukuGateway.run] timed out. */
const val EXIT_TIMED_OUT = -1

private const val MAX_TEXT_BYTES = 1 shl 20
private const val COPY_BUFFER = 64 * 1024
private const val DRAIN_GRACE_MS = 3_000L

/** Availability of the privileged shell backend. */
sealed interface ShizukuState {
    /** Neither the Shizuku manager nor Sui is present on the device. */
    data object NotInstalled : ShizukuState

    /** Shizuku exists but no live binder: not started after reboot, or too old to use. */
    data class NotRunning(val detail: String) : ShizukuState

    /** Binder alive, the user has not authorised this app yet. */
    data object PermissionNeeded : ShizukuState

    /** Shell commands can run. [uid] is 2000 for the ADB backend and 0 for the root backend. */
    data class Ready(val uid: Int, val version: Int) : ShizukuState

    val ready: Boolean get() = this is Ready
}

/** Outcome of a finished — or force-killed — shell invocation. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val outputTruncated: Boolean = false,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0

    /** stdout without surrounding whitespace: what `getprop`, `id`, `settings get` callers want. */
    val text: String get() = stdout.trim()

    /** Best available human explanation of a failure, preferring the device's own words. */
    val failure: String
        get() = when {
            timedOut -> "timed out"
            stderr.isNotBlank() -> "exit $exitCode: " + clip(stderr.trim())
            stdout.isNotBlank() -> "exit $exitCode: " + clip(stdout.trim())
            else -> "exit code $exitCode"
        }

    private companion object {
        /** Keeps both ends of a long message: the cause is usually at the head, the summary at the tail. */
        fun clip(text: String, head: Int = 1_400, tail: Int = 600): String =
            if (text.length <= head + tail) text else text.take(head) + "\n…[${text.length - head - tail} chars elided]…\n" + text.takeLast(tail)
    }
}

/** Outcome of streaming a process' raw stdout straight into a file. */
data class ShellFileResult(
    val exitCode: Int,
    val bytes: Long,
    val stderr: String,
    val timedOut: Boolean,
    val capped: Boolean,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0 && bytes > 0
    val failure: String
        get() = when {
            timedOut -> "timed out"
            stderr.isNotBlank() -> "exit $exitCode: " + stderr.trim().let { if (it.length <= 2_000) it else it.take(1_400) + "\n…\n" + it.takeLast(600) }
            bytes == 0L -> "produced no output (exit code $exitCode)"
            else -> "exit code $exitCode"
        }
}

/** Raised when a command cannot even be started. Callers surface the message verbatim. */
class ShellUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs fixed argument vectors as the Shizuku backend UID (2000/shell for ADB, 0 for root).
 *
 * Process-wide singleton: the Shizuku listeners it registers are static and must be attached
 * exactly once, so [get] hands out the one instance instead of letting every ViewModel add
 * another listener that would never be removed.
 *
 * Commands are always `List<String>` argv, never a shell string. Nothing in the UI can inject a
 * command: every argv is a constant declared in code (see `hci.SnoopController.Argv`).
 */
class ShizukuGateway private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning("Looking for Shizuku"))
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        // Sui (the Magisk-module flavour of Shizuku) delivers its binder only after init().
        runCatching { Sui.init(appContext.packageName) }
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refresh()
    }

    /** Re-probes the binder; safe to call from any thread. */
    fun refresh() {
        _state.value = probe()
    }

    private fun probe(): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return if (isManagerInstalled()) {
                ShizukuState.NotRunning("Shizuku is installed but not running. Start it, then come back.")
            } else {
                ShizukuState.NotInstalled
            }
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return ShizukuState.NotRunning("This Shizuku is older than v11. Update it to run shell commands.")
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) return ShizukuState.PermissionNeeded
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        return ShizukuState.Ready(uid, version)
    }

    private fun isManagerInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false) || runCatching { Sui.isSui() }.getOrDefault(false)

    /** Shows Shizuku's authorisation dialog. The result arrives through [state]. */
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(requestCode) }.onFailure { refresh() }
    }

    /**
     * Runs [argv] and captures its output as text.
     *
     * The process is destroyed when [timeoutMs] elapses, when the caller's coroutine is cancelled,
     * and on every error path. A timeout is never reported as success.
     */
    suspend fun run(argv: List<String>, timeoutMs: Long, stdin: String? = null): ShellResult {
        val outcome = exec(argv, timeoutMs, stdin, target = null, maxBytes = MAX_TEXT_BYTES.toLong())
        return ShellResult(
            exitCode = outcome.exitCode,
            stdout = outcome.stdoutText,
            stderr = outcome.stderr,
            timedOut = outcome.timedOut,
            outputTruncated = outcome.capped,
        )
    }

    /**
     * Runs [argv] and writes its raw stdout bytes into [target] — binary safe, so it can carry a
     * btsnoop log or a bugreport zip. Reading stops (and the process is killed) at [maxBytes].
     */
    suspend fun runToFile(
        argv: List<String>,
        timeoutMs: Long,
        target: File,
        maxBytes: Long,
        onBytes: ((Long) -> Unit)? = null,
    ): ShellFileResult {
        val outcome = exec(argv, timeoutMs, stdin = null, target = target, maxBytes = maxBytes, onBytes = onBytes)
        return ShellFileResult(
            exitCode = outcome.exitCode,
            bytes = outcome.stdoutBytes,
            stderr = outcome.stderr,
            timedOut = outcome.timedOut,
            capped = outcome.capped,
        )
    }

    /**
     * Streams stdout line by line for long-lived commands such as `logcat`. The remote process is
     * destroyed as soon as the collector stops, which is also what unblocks the reading thread.
     */
    fun runStreaming(argv: List<String>): Flow<String> = callbackFlow {
        val process = spawn(argv)
        runCatching { process.outputStream.close() }
        val reader = launch(Dispatchers.IO) {
            try {
                val stream = process.inputStream.bufferedReader()
                while (true) {
                    val line = stream.readLine() ?: break
                    trySendBlocking(line)
                }
            } catch (_: Throwable) {
                // Stream torn down by destroy(): normal shutdown, nothing to report.
            } finally {
                close()
            }
        }
        awaitClose {
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            reader.cancel()
        }
    }.buffer(256)

    private class ExecOutcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val stdoutText: String,
        val stdoutBytes: Long,
        val stderr: String,
        val capped: Boolean,
    )

    private suspend fun exec(
        argv: List<String>,
        timeoutMs: Long,
        stdin: String?,
        target: File?,
        maxBytes: Long,
        onBytes: ((Long) -> Unit)? = null,
    ): ExecOutcome {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val process = spawn(argv)
        // Detached: a drain blocked in a kernel read must never hold up the caller's cancellation.
        // destroy() below closes the write ends, so these coroutines always reach EOF and finish.
        val drains = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val stdoutTask = drains.async {
                if (target != null) {
                    drainToFile(process, process.inputStream, target, maxBytes, onBytes)
                } else {
                    drainToText(process, process.inputStream, maxBytes)
                }
            }
            val stderrTask = drains.async {
                drainToText(process, process.errorStream, MAX_TEXT_BYTES.toLong())
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    process.outputStream.use { sink ->
                        if (stdin != null) {
                            sink.write(stdin.toByteArray())
                            sink.flush()
                        }
                    }
                }
            }
            val exit = withTimeoutOrNull(timeoutMs) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
            // Always destroy: on the happy path it is a no-op, otherwise it is what ends the drains.
            runCatching { process.destroy() }
            val stdout = withTimeoutOrNull(DRAIN_GRACE_MS) { stdoutTask.await() } ?: Drained.EMPTY
            val stderr = withTimeoutOrNull(DRAIN_GRACE_MS) { stderrTask.await() } ?: Drained.EMPTY
            return ExecOutcome(
                exitCode = exit ?: EXIT_TIMED_OUT,
                timedOut = exit == null,
                stdoutText = stdout.text,
                stdoutBytes = stdout.bytes,
                stderr = stderr.text,
                capped = stdout.capped,
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { process.destroy() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
            drains.cancel()
        }
    }

    private class Drained(val text: String, val bytes: Long, val capped: Boolean) {
        companion object {
            val EMPTY = Drained("", 0, false)
        }
    }

    private fun drainToText(process: Process, stream: InputStream, maxBytes: Long): Drained {
        val buffer = ByteArray(COPY_BUFFER)
        // Bytes first, decoded once: a chunk boundary must never split a UTF-8 sequence.
        val collected = ByteArrayOutputStream(COPY_BUFFER)
        var total = 0L
        var capped = false
        stream.use { input ->
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: Throwable) {
                    break
                }
                if (read <= 0) break
                val room = (maxBytes - total).coerceAtMost(read.toLong()).toInt()
                if (room > 0) collected.write(buffer, 0, room)
                total += read
                if (total >= maxBytes) {
                    capped = true
                    runCatching { process.destroy() }
                    break
                }
            }
        }
        return Drained(collected.toString(Charsets.UTF_8.name()), total, capped)
    }

    private fun drainToFile(
        process: Process,
        stream: InputStream,
        target: File,
        maxBytes: Long,
        onBytes: ((Long) -> Unit)?,
    ): Drained {
        target.parentFile?.mkdirs()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        var capped = false
        var lastReport = 0L
        FileOutputStream(target).use { sink ->
            stream.use { input ->
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: Throwable) {
                        break
                    }
                    if (read <= 0) break
                    val room = (maxBytes - total).coerceAtMost(read.toLong()).toInt()
                    if (room > 0) sink.write(buffer, 0, room)
                    total += read
                    if (total - lastReport >= 1 shl 20) {
                        lastReport = total
                        onBytes?.invoke(total)
                    }
                    if (total >= maxBytes) {
                        capped = true
                        runCatching { process.destroy() }
                        break
                    }
                }
            }
            sink.flush()
        }
        onBytes?.invoke(total)
        return Drained("", total.coerceAtMost(maxBytes), capped)
    }

    /**
     * The single point where the hidden Shizuku shell API is touched.
     *
     * `Shizuku.newProcess(String[] cmd, String[] env, String dir)` is a private static method of
     * the `dev.rikka.shizuku:api` artifact (verified against 13.1.5), so it is reached by
     * reflection; the returned `ShizukuRemoteProcess` is a public `java.lang.Process` subclass, so
     * everything past this function is plain JDK process handling. Swapping in a Shizuku
     * UserService later means replacing this function only.
     */
    @Suppress("DEPRECATION")
    private fun spawn(argv: List<String>): Process {
        when (val current = _state.value) {
            is ShizukuState.Ready -> Unit
            is ShizukuState.NotInstalled -> throw ShellUnavailableException("Shizuku is not installed")
            is ShizukuState.PermissionNeeded -> throw ShellUnavailableException("Shizuku permission not granted")
            is ShizukuState.NotRunning -> {
                refresh()
                if (_state.value !is ShizukuState.Ready) throw ShellUnavailableException(current.detail)
            }
        }
        val method = newProcessMethod
            ?: throw ShellUnavailableException("This Shizuku API build does not expose newProcess")
        return try {
            method.invoke(null, argv.toTypedArray(), null, "/") as Process
        } catch (e: InvocationTargetException) {
            throw ShellUnavailableException(
                "Shizuku refused to start ${argv.first()}: ${e.targetException?.message ?: e.message}",
                e.targetException ?: e,
            )
        } catch (e: ReflectiveOperationException) {
            throw ShellUnavailableException("Shizuku shell access failed: ${e.message}", e)
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 4411

        private val newProcessMethod: Method? by lazy {
            runCatching {
                Shizuku::class.java
                    .getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    )
                    .apply { isAccessible = true }
            }.getOrNull()
        }

        @Volatile
        private var instance: ShizukuGateway? = null

        fun get(context: Context): ShizukuGateway =
            instance ?: synchronized(this) {
                instance ?: ShizukuGateway(context).also { instance = it }
            }
    }
}
