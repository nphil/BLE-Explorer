package dev.nphil.blueshark.hci

/**
 * What one connection attempt looked like from the host's side.
 *
 * The start is the HCI_LE_Create_Connection / HCI_LE_Extended_Create_Connection command
 * (Vol 4 Part E, Sections 7.8.12 and 7.8.66) and the end is the LE Connection Complete subevent
 * (7.7.65.1 / 7.7.65.10) or a Command Status that refused the command (7.7.15). [durationMicros] is
 * -1 when the capture holds only one of the two.
 */
data class ConnectionAttemptFact(
    val startedEpochMicros: Long,
    val durationMicros: Long,
    val success: Boolean,
    val error: String? = null,
)

/**
 * Everything the capture proved about the link to one peer address, accumulated over every
 * connection handle that peer used.
 *
 * @param intervalMs connection interval in milliseconds (Conn_Interval * 1.25 ms).
 * @param supervisionTimeoutMs supervision timeout in milliseconds (Supervision_Timeout * 10 ms).
 * @param mtu negotiated ATT MTU, the smaller of the two Exchange MTU values (Vol 3 Part F, 3.4.2.2).
 * @param pairingMethod association model inferred from the SMP feature exchange, null if none seen.
 * @param pairingSeen true when any SMP pairing or security request crossed this link, even if the
 *   capture did not hold the whole exchange.
 * @param bonded true when a pairing feature exchange asked for bonding (Vol 3 Part H, 3.5.1).
 * @param creditBasedChannels true when an L2CAP credit-based channel (EATT or a CoC) was opened.
 * @param attempts one entry per connection attempt the capture held, newest last.
 * @param idleDisconnectSamplesMs idle time from the last ATT PDU on a handle to a disconnection the
 *   peer or the supervision timer caused, one sample per such disconnection. A disconnection the
 *   host asked for is never a sample: it says nothing about the peer's idle timer.
 */
data class ConnectionSummary(
    val address: String,
    val addressType: String? = null,
    val handles: List<Int> = emptyList(),
    val intervalMs: Double? = null,
    val latency: Int? = null,
    val supervisionTimeoutMs: Int? = null,
    val mtu: Int? = null,
    val encrypted: Boolean = false,
    val pairingMethod: PairingMethod? = null,
    val pairingSeen: Boolean = false,
    val bonded: Boolean = false,
    val txPhy: String? = null,
    val rxPhy: String? = null,
    val disconnectReasons: List<String> = emptyList(),
    val durationMs: Long = 0,
    val creditBasedChannels: Boolean = false,
    val attempts: List<ConnectionAttemptFact> = emptyList(),
    val idleDisconnectSamplesMs: List<Long> = emptyList(),
)

/**
 * One advertiser seen in an LE Advertising Report or LE Extended Advertising Report
 * (Vol 4 Part E, Sections 7.7.65.2 and 7.7.65.13), with the AD structures decoded per the Core
 * Specification Supplement, Part A, Section 1.
 *
 * @param manufacturerData most recent payload per company identifier, uppercase hex.
 */
data class AdvertiserSummary(
    val address: String,
    val addressType: String? = null,
    val count: Int = 0,
    val lastRssi: Int? = null,
    val names: Set<String> = emptySet(),
    val serviceUuids: Set<String> = emptySet(),
    val manufacturerIds: Set<Int> = emptySet(),
    val manufacturerData: Map<Int, String> = emptyMap(),
)

/**
 * One node of a Wireshark-style dissection tree: a label, an optional decoded value, and children.
 *
 * Leaves carry a [value]; a layer (H4, HCI, L2CAP, ATT, SMP) carries [children]. Nothing here is
 * mutable, so a node can be handed straight to Compose.
 */
data class DissectionNode(
    val label: String,
    val value: String? = null,
    val children: List<DissectionNode> = emptyList(),
)
