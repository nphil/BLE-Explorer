package dev.nphil.blueshark.debug

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nphil.blueshark.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Rate limits, batching and redaction live in [NtfyBudget]; this class is the Android plumbing. */
private const val RING_CAPACITY = 400
/** Unsent lines kept while ntfy is unreachable; older ones are dropped with a marker line. */
private const val PENDING_CAPACITY = 2_000
private const val DEFAULT_TOPIC = "blueshark-nphil-XWESpf9F3gas"

private val Context.debugStore: DataStore<Preferences> by preferencesDataStore("debug")
private val KEY_ENABLED = booleanPreferencesKey("ntfy_enabled")
private val KEY_TOPIC = stringPreferencesKey("ntfy_topic")
private val KEY_DAY = stringPreferencesKey("ntfy_day")
private val KEY_SENT_TODAY = intPreferencesKey("ntfy_sent_today")

data class DebugSettings(val ntfyEnabled: Boolean = false, val topic: String = DEFAULT_TOPIC) {
    val topicUrl: String get() = "https://ntfy.sh/$topic"
}

data class DebugStatus(
    val queuedLines: Int = 0,
    val sentToday: Int = 0,
    val lastResult: String = "",
)

/**
 * Process-wide debug log. Every line is kept in a bounded ring for "copy diagnostics"; when the
 * ntfy sink is on, lines are also batched and published so a remote reader can follow a session.
 */
class DebugLog(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val store = appContext.debugStore
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val pending = ArrayDeque<String>()
    private val lock = Mutex()
    private var flusher: Job? = null
    @Volatile private var lastSendMs = 0L

    val settings: Flow<DebugSettings> = store.data.map { prefs ->
        DebugSettings(
            ntfyEnabled = prefs[KEY_ENABLED] ?: false,
            topic = prefs[KEY_TOPIC]?.takeIf { it.matches(TOPIC_RULE) } ?: DEFAULT_TOPIC,
        )
    }

    private val _status = MutableStateFlow(DebugStatus())
    val status: StateFlow<DebugStatus> = _status

    init {
        scope.launch {
            settings.collect { s -> if (s.ntfyEnabled) startFlusher() else stopFlusher() }
        }
    }

    /** Records one line locally and, when enabled, queues it for the remote sink. */
    fun log(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] ${message.trimEnd()}"
        scope.launch {
            lock.withLock {
                if (ring.size == RING_CAPACITY) ring.removeFirst()
                ring.addLast(line)
                pending.addLast(line)
                if (pending.size > PENDING_CAPACITY) {
                    val drop = pending.size - PENDING_CAPACITY + PENDING_CAPACITY / 10
                    repeat(drop) { pending.removeFirst() }
                    pending.addFirst("${stamp.format(Date())} [debug] dropped $drop unsent lines (ntfy unreachable)")
                }
                _status.update { it.copy(queuedLines = pending.size) }
            }
        }
    }

    /** The whole local ring, oldest first: what "Copy diagnostics" pastes. */
    suspend fun snapshot(): String = lock.withLock { ring.joinToString("\n") }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[KEY_ENABLED] = enabled }
        if (enabled) log("debug", "ntfy sink enabled on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, BlueShark ${BuildConfig.VERSION_NAME}")
    }

    suspend fun setTopic(topic: String) {
        if (topic.matches(TOPIC_RULE)) store.edit { it[KEY_TOPIC] = topic }
    }

    /**
     * Publishes one dump immediately as a single attachment (ntfy treats bodies over 4 KB as
     * attachments anyway; naming the file keeps it in one piece and one budget slot).
     */
    suspend fun sendNow(title: String, body: String): String =
        publish(settings.first(), title, NtfyBudget.redact(body), filename = "blueshark-diagnostics.txt").detail

    private fun startFlusher() {
        if (flusher?.isActive == true) return
        flusher = scope.launch {
            while (true) {
                delay(NtfyBudget.MIN_INTERVAL_MS)
                // Peek: lines leave the queue only after ntfy accepted them, so a flaky network
                // defers a batch instead of losing it.
                val (batch, taken) = lock.withLock {
                    val (body, rest) = NtfyBudget.takeBatch(pending.toList())
                    body to (pending.size - rest.size)
                }
                if (batch.isEmpty()) continue
                val result = publish(settings.first(), "BlueShark log", NtfyBudget.redact(batch))
                if (result.sent) {
                    lock.withLock {
                        repeat(taken) { pending.removeFirst() }
                        _status.update { it.copy(queuedLines = pending.size) }
                    }
                }
                _status.update { it.copy(lastResult = result.detail) }
            }
        }
    }

    private fun stopFlusher() {
        flusher?.cancel()
        flusher = null
    }


    private class Outcome(val sent: Boolean, val detail: String)

    /**
     * One HTTP publish. The daily counter is charged only after ntfy accepted the message; the
     * spacing rule is enforced against the last *attempt* so a failing server is not hammered.
     */
    private suspend fun publish(s: DebugSettings, title: String, body: String, filename: String? = null): Outcome {
        if (!s.ntfyEnabled) return Outcome(false, "ntfy sink is off")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        val prefs = store.data.first()
        val counter = NtfyBudget.Counter(prefs[KEY_DAY] ?: "", prefs[KEY_SENT_TODAY] ?: 0)
        val (allowed, next) = NtfyBudget.admit(counter, today, now, lastSendMs)
        if (!allowed) {
            return Outcome(
                false,
                if (now - lastSendMs < NtfyBudget.MIN_INTERVAL_MS) "rate-limited; try again in a few seconds"
                else "daily cap of ${NtfyBudget.DAILY_CAP} messages reached; resumes tomorrow",
            )
        }
        lastSendMs = now
        val outcome = withContext(Dispatchers.IO) {
            try {
                val conn = URL(s.topicUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Title", title)
                conn.setRequestProperty("Priority", "min")
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                filename?.let { conn.setRequestProperty("Filename", it) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) Outcome(true, "sent ${body.length} chars at ${stamp.format(Date())}")
                else Outcome(false, "ntfy HTTP $code")
            } catch (e: IOException) {
                Outcome(false, "ntfy unreachable: ${e.message}")
            }
        }
        if (outcome.sent) {
            store.edit {
                it[KEY_DAY] = next.dayKey
                it[KEY_SENT_TODAY] = next.sentToday
            }
            _status.update { it.copy(sentToday = next.sentToday) }
        }
        return outcome
    }

    private companion object {
        val TOPIC_RULE = Regex("[-_A-Za-z0-9]{1,64}")
    }
}
