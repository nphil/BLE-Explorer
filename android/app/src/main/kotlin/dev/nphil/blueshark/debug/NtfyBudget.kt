package dev.nphil.blueshark.debug

/**
 * Pure decisions behind the ntfy.sh sink, kept free of Android so they are unit-testable.
 *
 * ntfy.sh limits (docs.ntfy.sh/publish/#limitations): 4,096-byte bodies (longer becomes an
 * attachment), a 60-request burst refilling at one request per 5 s, and 250 messages per day.
 */
object NtfyBudget {
    /** Never publish two messages closer than this; twice ntfy's refill period. */
    const val MIN_INTERVAL_MS = 10_000L

    /** Bodies stay under ntfy's 4,096-byte message limit with room for multibyte characters. */
    const val MAX_BODY_BYTES = 3_800

    /** Hard stop well below ntfy's 250/day. */
    const val DAILY_CAP = 200

    data class Counter(val dayKey: String, val sentToday: Int)

    /** Whether one more message may go out now, and the counter to persist if it does. */
    fun admit(counter: Counter, todayKey: String, nowMs: Long, lastSendMs: Long): Pair<Boolean, Counter> {
        val sent = if (counter.dayKey == todayKey) counter.sentToday else 0
        if (sent >= DAILY_CAP) return false to Counter(todayKey, sent)
        if (nowMs - lastSendMs < MIN_INTERVAL_MS) return false to Counter(todayKey, sent)
        return true to Counter(todayKey, sent + 1)
    }

    /**
     * Takes as many whole lines from the head of [pending] as fit in one body, in UTF-8 bytes.
     * A single line longer than the cap is truncated rather than blocking the queue forever.
     * Returns the body and the lines left over.
     */
    fun takeBatch(pending: List<String>, maxBytes: Int = MAX_BODY_BYTES): Pair<String, List<String>> {
        if (pending.isEmpty()) return "" to pending
        val sb = StringBuilder()
        var used = 0
        var index = 0
        while (index < pending.size) {
            var line = pending[index]
            var size = line.toByteArray(Charsets.UTF_8).size + 1
            if (size > maxBytes) {
                line = truncateToBytes(line, maxBytes - 1)
                size = line.toByteArray(Charsets.UTF_8).size + 1
            }
            if (used + size > maxBytes && sb.isNotEmpty()) break
            sb.append(line).append('\n')
            used += size
            index++
        }
        return sb.toString() to pending.subList(index, pending.size)
    }

    private fun truncateToBytes(text: String, maxBytes: Int): String {
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return text.substring(0, end)
    }

    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val ANDROID_ID = Regex("""(?i)(android_id\s*[=:]\s*)[0-9a-f]{16}""")
    private val SECURE_SECTION = Regex("""(?ms)^## settings secure\n.*?(?=^## |\z)""")

    /**
     * What may leave the device: the local clipboard copy is verbatim, the ntfy body is not.
     * Drops the `settings secure` section entirely (device identifiers) and masks MAC-shaped
     * tokens and android_id everywhere else; the last octet is kept so two devices stay
     * distinguishable without the address being reconstructible. Human-readable names (bonded
     * device names, control labels) are deliberately kept: they are what makes a log readable.
     */
    fun redact(body: String): String =
        body.replace(SECURE_SECTION, "## settings secure\n(redacted before upload)\n")
            .replace(MAC) { m -> "xx:xx:xx:xx:xx:" + m.value.takeLast(2) }
            .replace(ANDROID_ID) { m -> m.groupValues[1] + "(redacted)" }
}
