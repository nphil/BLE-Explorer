package dev.nphil.blueshark.relay

import java.util.UUID

/**
 * Pure size planner for the legacy 31-byte advertising payload.
 *
 * The Android stack silently rejects an oversized [android.bluetooth.le.AdvertiseData] with
 * `ADVERTISE_FAILED_DATA_TOO_LARGE`, and it never truncates for you. A relay whose advertisement
 * lost the mirrored service UUIDs looks alive but is invisible to the vendor app, so the plan is
 * computed up front and an impossible request is refused with the exact byte arithmetic.
 *
 * Budget model (matches `BluetoothLeAdvertiser.totalBytes`):
 *  * 31 bytes per PDU, advertisement and scan response counted separately;
 *  * a connectable advertisement always carries the 3-byte AD flags structure, a scan response never does;
 *  * complete local name costs 2 bytes of AD overhead plus the UTF-8 name bytes;
 *  * each service-UUID list costs 2 bytes of AD overhead plus 2, 4 or 16 bytes per UUID, and 16-,
 *    32- and 128-bit UUIDs travel in three separate AD structures.
 */
object AdvertisePlanner {

    const val MAX_PAYLOAD_BYTES = 31
    const val FLAGS_FIELD_BYTES = 3
    const val FIELD_OVERHEAD_BYTES = 2

    private const val BASE_UUID_LSB = -0x7FFFFF7FA064CB05L // 0x800000805F9B34FB
    private const val BASE_UUID_MSB_16 = 0x1000L

    /** 0xFFFF0000FFFFFFFF, composed by shift because that value overflows a signed hex literal. */
    private const val UUID16_MSB_MASK = (0xFFFFL shl 48) or 0xFFFFFFFFL

    /** 2, 4 or 16 - the on-air width of [uuid] once the base-UUID prefix is stripped. */
    fun uuidWidthBytes(uuid: UUID): Int = when {
        is16Bit(uuid) -> 2
        is32Bit(uuid) -> 4
        else -> 16
    }

    fun is16Bit(uuid: UUID): Boolean =
        uuid.leastSignificantBits == BASE_UUID_LSB &&
            (uuid.mostSignificantBits and UUID16_MSB_MASK) == BASE_UUID_MSB_16

    fun is32Bit(uuid: UUID): Boolean =
        uuid.leastSignificantBits == BASE_UUID_LSB &&
            !is16Bit(uuid) &&
            (uuid.mostSignificantBits and 0xFFFFFFFFL) == BASE_UUID_MSB_16

    /** Bytes consumed by the complete-local-name AD structure, or 0 for an absent name. */
    fun localNameFieldBytes(name: String?): Int =
        if (name.isNullOrEmpty()) 0 else FIELD_OVERHEAD_BYTES + name.toByteArray(Charsets.UTF_8).size

    /** Bytes consumed by the service-UUID AD structures (one per UUID width present). */
    fun serviceUuidFieldBytes(uuids: List<UUID>): Int {
        if (uuids.isEmpty()) return 0
        var short = 0
        var medium = 0
        var long = 0
        for (uuid in uuids) when (uuidWidthBytes(uuid)) {
            2 -> short++
            4 -> medium++
            else -> long++
        }
        var total = 0
        if (short > 0) total += FIELD_OVERHEAD_BYTES + short * 2
        if (medium > 0) total += FIELD_OVERHEAD_BYTES + medium * 4
        if (long > 0) total += FIELD_OVERHEAD_BYTES + long * 16
        return total
    }

    /**
     * Places [serviceUuids] and the local name into the advertisement and, when needed, the scan
     * response. Service UUIDs are kept in the advertisement whenever they fit, because passive
     * scanners and iOS service filters only see the advertisement PDU.
     *
     * @param deviceName the name the adapter will actually broadcast, or null when the name is not wanted.
     */
    fun plan(deviceName: String?, serviceUuids: List<UUID>): AdvertisePlanResult {
        val nameBytes = localNameFieldBytes(deviceName)
        val uuidBytes = serviceUuidFieldBytes(serviceUuids)
        val advBudget = MAX_PAYLOAD_BYTES - FLAGS_FIELD_BYTES
        val notes = ArrayList<String>(2)

        if (uuidBytes > MAX_PAYLOAD_BYTES) {
            return AdvertisePlanResult.Rejected(
                "The ${serviceUuids.size} mirrored service UUID(s) need $uuidBytes bytes, but a single " +
                    "advertising PDU carries at most $MAX_PAYLOAD_BYTES. " + reductionAdvice(serviceUuids),
            )
        }

        val uuidsInAdvertisement = uuidBytes <= advBudget
        if (!uuidsInAdvertisement) {
            notes += "Service UUIDs moved to the scan response ($uuidBytes bytes did not fit in the " +
                "$advBudget bytes left after the ${FLAGS_FIELD_BYTES}-byte AD flags); passive scanners will not see them."
        }

        var nameInAdvertisement = false
        var nameInScanResponse = false
        if (nameBytes > 0) {
            val advUsed = if (uuidsInAdvertisement) uuidBytes else 0
            val scanUsed = if (uuidsInAdvertisement) 0 else uuidBytes
            when {
                advUsed + nameBytes <= advBudget -> nameInAdvertisement = true
                scanUsed + nameBytes <= MAX_PAYLOAD_BYTES -> {
                    nameInScanResponse = true
                    notes += "Local name moved to the scan response; it needs $nameBytes bytes and only " +
                        "${advBudget - advUsed} were left in the advertisement."
                }
                else -> return AdvertisePlanResult.Rejected(
                    buildString {
                        append("The local name needs $nameBytes bytes ")
                        append("(2 + ${nameBytes - FIELD_OVERHEAD_BYTES} UTF-8 name bytes)")
                        if (uuidBytes > 0) append(" next to $uuidBytes bytes of service UUIDs")
                        append(", which fits neither the advertisement ($advBudget bytes after the ")
                        append("${FLAGS_FIELD_BYTES}-byte AD flags) nor the scan response ($MAX_PAYLOAD_BYTES bytes). ")
                        append("Shorten the alias to at most ")
                        append((MAX_PAYLOAD_BYTES - scanUsed - FIELD_OVERHEAD_BYTES).coerceAtLeast(0))
                        append(" characters")
                        if (serviceUuids.isEmpty()) append('.') else append(" or advertise fewer services.")
                    },
                )
            }
        }

        val advertisementBytes = FLAGS_FIELD_BYTES +
            (if (uuidsInAdvertisement) uuidBytes else 0) +
            (if (nameInAdvertisement) nameBytes else 0)
        val scanResponseBytes = (if (uuidsInAdvertisement) 0 else uuidBytes) +
            (if (nameInScanResponse) nameBytes else 0)

        return AdvertisePlanResult.Planned(
            AdvertisePlan(
                includeNameInAdvertisement = nameInAdvertisement,
                includeNameInScanResponse = nameInScanResponse,
                advertisementServiceUuids = if (uuidsInAdvertisement) serviceUuids else emptyList(),
                scanResponseServiceUuids = if (uuidsInAdvertisement) emptyList() else serviceUuids,
                advertisementBytes = advertisementBytes,
                scanResponseBytes = scanResponseBytes,
                notes = notes,
            ),
        )
    }

    private fun reductionAdvice(serviceUuids: List<UUID>): String {
        val wide = serviceUuids.count { uuidWidthBytes(it) == 16 }
        return if (wide > 1) {
            "Advertise fewer services: each 128-bit UUID costs 16 bytes, so at most one fits alongside the AD flags."
        } else {
            "Advertise fewer services."
        }
    }
}

data class AdvertisePlan(
    val includeNameInAdvertisement: Boolean,
    val includeNameInScanResponse: Boolean,
    val advertisementServiceUuids: List<UUID>,
    val scanResponseServiceUuids: List<UUID>,
    val advertisementBytes: Int,
    val scanResponseBytes: Int,
    val notes: List<String> = emptyList(),
) {
    val usesScanResponse: Boolean
        get() = includeNameInScanResponse || scanResponseServiceUuids.isNotEmpty()

    fun summary(): String = buildString {
        append("Advertisement $advertisementBytes/")
        append(AdvertisePlanner.MAX_PAYLOAD_BYTES)
        append(" B (flags ")
        append(AdvertisePlanner.FLAGS_FIELD_BYTES)
        append(" B")
        if (includeNameInAdvertisement) append(" + name")
        if (advertisementServiceUuids.isNotEmpty()) append(" + ${advertisementServiceUuids.size} UUID(s)")
        append(')')
        if (usesScanResponse) {
            append(", scan response $scanResponseBytes/")
            append(AdvertisePlanner.MAX_PAYLOAD_BYTES)
            append(" B (")
            if (includeNameInScanResponse) append("name")
            if (includeNameInScanResponse && scanResponseServiceUuids.isNotEmpty()) append(" + ")
            if (scanResponseServiceUuids.isNotEmpty()) append("${scanResponseServiceUuids.size} UUID(s)")
            append(')')
        }
    }
}

sealed interface AdvertisePlanResult {
    data class Planned(val plan: AdvertisePlan) : AdvertisePlanResult
    data class Rejected(val reason: String) : AdvertisePlanResult
}
