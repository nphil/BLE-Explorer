package dev.nphil.blueshark.data

import android.content.Context
import dev.nphil.blueshark.BuildConfig
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.EvidenceBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Raised by [SessionStore.update] when the bundle it was asked to patch is no longer on disk. */
class MissingSessionException(val id: String) : IllegalStateException("That session is no longer on disk")

class SessionStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, "sessions"))

    init {
        directory.mkdirs()
    }

    /**
     * One lock per session id, held across load-modify-save; see [update].
     *
     * Keyed by id rather than global so two screens writing different sessions never wait on each
     * other, and bounded by the number of sessions on disk.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    suspend fun list(): List<CaptureSession> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<EvidenceBundle>(it.readText()).session }.getOrNull() }
            .sortedByDescending(CaptureSession::updatedAtEpochMs)
    }

    suspend fun load(id: String): CaptureSession? = withContext(Dispatchers.IO) {
        read(fileFor(id))
    }

    /**
     * Writes the whole bundle, then renames it over the previous one.
     *
     * Use this for a session the caller owns outright - a freshly created one. For a session that
     * already exists use [update]: this overload publishes the caller's whole object, so whatever
     * another screen appended after the caller read it is gone.
     */
    suspend fun save(session: CaptureSession): CaptureSession = withContext(Dispatchers.IO) {
        val target = fileFor(session.id)
        lockFor(session.id).withLock { publish(session, target) }
    }

    /**
     * Load-modify-save under the session's own lock: [mutate] always sees the bytes on disk, and
     * the result is published before any other writer of the same id can read it.
     *
     * This is what makes four screens writing one session lossless. [mutate] runs on
     * [Dispatchers.IO] while the lock is held, so it must not block on anything but CPU.
     *
     * @throws MissingSessionException when no bundle with [id] exists.
     */
    suspend fun update(id: String, mutate: (CaptureSession) -> CaptureSession): CaptureSession =
        withContext(Dispatchers.IO) {
            val target = fileFor(id)
            lockFor(id).withLock {
                val current = read(target) ?: throw MissingSessionException(id)
                val mutated = mutate(current)
                require(mutated.id == id) { "update must not change the session id" }
                publish(mutated, target)
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) { fileFor(id).delete() }

    fun bundleFile(id: String): File = fileFor(id)

    private fun read(file: File): CaptureSession? =
        if (!file.isFile) null else json.decodeFromString<EvidenceBundle>(file.readText()).session

    /**
     * Every write gets its own temp file and is renamed into place: a shared temp path let two
     * concurrent writers interleave their bytes and then publish the mix as a valid-looking
     * session. Callers hold [lockFor] the session id, so the rename is also the commit point.
     */
    private fun publish(session: CaptureSession, target: File): CaptureSession {
        val normalized = session.copy(updatedAtEpochMs = System.currentTimeMillis())
        val bundle = EvidenceBundle(appVersion = BuildConfig.VERSION_NAME, session = normalized)
        val temp = File(directory, ".${session.id}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(json.encodeToString(bundle))
            check(temp.renameTo(target)) { "Could not replace ${target.name}" }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return normalized
    }

    private fun lockFor(id: String): Mutex = locks.computeIfAbsent(id) { Mutex() }

    private fun fileFor(id: String): File {
        require(SESSION_ID.matches(id)) { "Invalid session id" }
        return File(directory, "$id.json")
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
