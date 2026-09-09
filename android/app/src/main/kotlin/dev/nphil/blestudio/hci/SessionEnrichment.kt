package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.ConnectAttempt
import dev.nphil.blestudio.model.EventSource

/** Idle gaps must agree this closely before they are treated as one idle timeout. */
private const val IDLE_AGREEMENT = 0.20

/** Fewer samples than this is a coincidence, not a timer. */
private const val MIN_IDLE_SAMPLES = 3

/**
 * Folds what a btsnoop capture proved about one peer into the session's own facts.
 *
 * [targetAddress] names the gadget: a capture normally holds the phone's traffic with several
 * peers, and only the operator knows which one matters. With exactly one connected peer the caller
 * may omit it and this picks that peer.
 *
 * The function is pure and idempotent, and it never overwrites a fact the operator or a live GATT
 * session already established: an MTU already recorded stays, `pairingRequired` is only ever raised
 * to true (never cleared), and connect attempts already in the session are kept. Evidence that is
 * not conclusive lands in [dev.nphil.blestudio.model.ConnectionFacts.notes] instead of a field.
 */
fun enrich(session: CaptureSession, summary: ParseSummary, targetAddress: String? = null): CaptureSession {
    val address = targetAddress ?: summary.connectionsByPeer.keys.singleOrNull() ?: return session
    val connection = summary.connectionsByPeer[address]
    val advertiser = summary.advertisers[address]
    if (connection == null && advertiser == null) return session

    val device = session.device.copy(
        address = address,
        addressType = connection?.addressType ?: advertiser?.addressType ?: session.device.addressType,
        name = session.device.name?.takeIf { it.isNotBlank() } ?: advertiser?.names?.firstOrNull(),
        advertisedServiceUuids = merge(session.device.advertisedServiceUuids, advertiser?.serviceUuids),
        manufacturerData = merge(session.device.manufacturerData, advertiser?.manufacturerData),
    )

    val pairingSeen = connection?.pairingSeen == true
    val idleSamples = connection?.idleDisconnectSamplesMs ?: emptyList()
    val idleDisconnectMs = idleTimeout(idleSamples)
    val facts = session.connection.copy(
        negotiatedMtu = session.connection.negotiatedMtu ?: connection?.mtu,
        pairingRequired = if (pairingSeen) true else session.connection.pairingRequired,
        idleDisconnectMs = idleDisconnectMs ?: session.connection.idleDisconnectMs,
        connectAttempts = attempts(session.connection.connectAttempts, connection?.attempts.orEmpty()),
        notes = if (idleDisconnectMs == null) {
            appendIdleSamples(session.connection.notes, idleSamples)
        } else {
            session.connection.notes
        },
    )

    val protocol = session.protocol.copy(
        requiresPairing = if (pairingSeen) true else session.protocol.requiresPairing,
    )

    return session.copy(device = device, connection = facts, protocol = protocol)
}

/**
 * The shortest idle gap, when at least [MIN_IDLE_SAMPLES] gaps agree to within [IDLE_AGREEMENT].
 * One gap proves nothing, and gaps that disagree describe a link that dropped for other reasons.
 */
internal fun idleTimeout(samplesMs: List<Long>): Long? {
    if (samplesMs.size < MIN_IDLE_SAMPLES) return null
    var min = Long.MAX_VALUE
    var max = 0L
    for (sample in samplesMs) {
        if (sample <= 0) return null
        if (sample < min) min = sample
        if (sample > max) max = sample
    }
    return if (max.toDouble() <= min * (1 + IDLE_AGREEMENT)) min else null
}

private fun appendIdleSamples(notes: String, samplesMs: List<Long>): String {
    if (samplesMs.isEmpty()) return notes
    val line = "HCI idle gaps before disconnect: ${samplesMs.joinToString(", ")} ms"
    if (notes.contains(line)) return notes
    return if (notes.isBlank()) line else "$notes\n$line"
}

/**
 * Adds one [ConnectAttempt] per observed attempt, keyed by start time so re-running the enrichment
 * over the same capture does not pile up duplicates. A duration of zero means the capture held the
 * completion but not the Create Connection command that started it.
 */
private fun attempts(existing: List<ConnectAttempt>, observed: List<ConnectionAttemptFact>): List<ConnectAttempt> {
    if (observed.isEmpty()) return existing
    val out = ArrayList<ConnectAttempt>(existing.size + observed.size)
    out += existing
    for (fact in observed) {
        val startedEpochMs = fact.startedEpochMicros / 1000
        val duplicate = out.any { it.source == EventSource.HCI_SNOOP && it.startedEpochMs == startedEpochMs }
        if (duplicate) continue
        out += ConnectAttempt(
            startedEpochMs = startedEpochMs,
            durationMs = if (fact.durationMicros >= 0) fact.durationMicros / 1000 else 0,
            success = fact.success,
            source = EventSource.HCI_SNOOP,
            error = fact.error,
        )
    }
    return out
}

private fun merge(existing: List<String>, observed: Set<String>?): List<String> {
    if (observed.isNullOrEmpty()) return existing
    val out = LinkedHashSet<String>(existing.size + observed.size)
    out += existing
    out += observed
    return out.toList()
}

private fun merge(existing: Map<Int, String>, observed: Map<Int, String>?): Map<Int, String> {
    if (observed.isNullOrEmpty()) return existing
    val out = LinkedHashMap<Int, String>(existing.size + observed.size)
    out += existing
    for ((company, data) in observed) out.putIfAbsent(company, data)
    return out
}
