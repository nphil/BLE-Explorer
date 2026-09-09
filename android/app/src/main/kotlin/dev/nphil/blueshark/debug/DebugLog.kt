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

/**
 * ntfy.sh budget (docs.ntfy.sh/publish/#limitations): 4096 bytes per message, 60-request burst then
 * one request per 5 s, 250 messages per day. The sink stays well inside all three: one message per
 * [FLUSH_INTERVAL_MS], bodies capped at [MAX_BODY_BYTES], and a hard [DAILY_MESSAGE_CAP].
 */
private const val FLUSH_INTERVAL_MS = 10_000L
private const val MAX_BODY_BYTES = 3_800
private const val DAILY_MESSAGE_CAP = 200
private const val RING_CAPACITY = 400
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

    /** Publishes one message immediately, subject to the daily cap. Used for "Send diagnostics now". */
    suspend fun sendNow(title: String, body: String): String {
        val s = settings.first()
        return publish(s, title, body)
    }

    private fun startFlusher() {
        if (flusher?.isActive == true) return
        flusher = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                val batch = lock.withLock { takeBatch() } ?: continue
                val result = publish(settings.first(), "BlueShark log", batch)
                _status.update { it.copy(lastResult = result) }
            }
        }
    }

    private fun stopFlusher() {
        flusher?.cancel()
        flusher = null
    }

    /** Pops as many whole lines as fit in one ntfy body; the rest waits for the next interval. */
    private fun takeBatch(): String? {
        if (pending.isEmpty()) return null
        val sb = StringBuilder()
        while (pending.isNotEmpty()) {
            val next = pending.first()
            val projected = sb.length + next.length + 1
            if (projected > MAX_BODY_BYTES && sb.isNotEmpty()) break
            pending.removeFirst()
            sb.append(if (projected > MAX_BODY_BYTES) next.take(MAX_BODY_BYTES) else next).append('\n')
        }
        _status.update { it.copy(queuedLines = pending.size) }
        return sb.toString()
    }

    private suspend fun publish(s: DebugSettings, title: String, body: String): String {
        if (!s.ntfyEnabled) return "ntfy sink is off"
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var allowed = false
        store.edit { prefs ->
            val sent = if (prefs[KEY_DAY] == today) prefs[KEY_SENT_TODAY] ?: 0 else 0
            if (sent < DAILY_MESSAGE_CAP) {
                prefs[KEY_DAY] = today
                prefs[KEY_SENT_TODAY] = sent + 1
                allowed = true
                _status.update { it.copy(sentToday = sent + 1) }
            }
        }
        if (!allowed) return "daily cap of $DAILY_MESSAGE_CAP messages reached; resumes tomorrow"
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(s.topicUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Title", title)
                conn.setRequestProperty("Priority", "min")
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) "sent ${body.length} chars at ${stamp.format(Date())}" else "ntfy HTTP $code"
            } catch (e: IOException) {
                "ntfy unreachable: ${e.message}"
            }
        }
    }

    private companion object {
        val TOPIC_RULE = Regex("[-_A-Za-z0-9]{1,64}")
    }
}
