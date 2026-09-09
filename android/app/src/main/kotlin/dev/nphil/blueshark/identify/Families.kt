package dev.nphil.blueshark.identify

import dev.nphil.blueshark.export.HaProfileBuilder
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.toHex

/**
 * How much of a [FamilyMatch] had to be guessed.
 *
 * [CERTAIN] is reserved for evidence that cannot come from anything else - in practice the GATT
 * database, because a connected device cannot lie about which characteristics it exposes.
 * [LIKELY] is a distinctive advertisement layout, [POSSIBLE] a single weak signal such as a name
 * prefix or one of the vendor-generic 16-bit service ids every second module reuses.
 */
enum class FamilyConfidence { CERTAIN, LIKELY, POSSIBLE }

/**
 * One recognised ecosystem, with the bytes that gave it away.
 *
 * [evidence] quotes the actual uuids and payloads: a match the operator cannot check is worse than
 * no match at all, because it silently steers the whole project down the wrong protocol.
 *
 * @param codecId id of the [dev.nphil.blueshark.probe.FrameCodec] that frames this family's
 *   payloads, or null when the framing is unknown.
 * @param commandCharacteristicHints short uuids ("fff1") or the distinguishing uuid tail ("1911")
 *   of the characteristics worth probing first.
 */
data class FamilyMatch(
    val familyId: String,
    val name: String,
    val confidence: FamilyConfidence,
    val evidence: List<String>,
    val publicDriverUrl: String?,
    val codecId: String?,
    val commandCharacteristicHints: List<String>,
)

/**
 * Everything a scan (and optionally one connection) knows about a device.
 *
 * Service uuids and service-data keys may be 16-bit shorthands, undashed or fully qualified: they
 * are canonicalised before any rule sees them, so callers can pass whatever their source produced.
 */
data class FingerprintInput(
    val name: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val gatt: GattDatabase? = null,
)

// ---------------------------------------------------------------------------
// Normalised views the rules are written against
// ---------------------------------------------------------------------------

/**
 * The advertisement, canonicalised once so every rule can match on fragments of a full 128-bit
 * uuid instead of re-deriving the short forms.
 *
 * Canonicalisation is [HaProfileBuilder.canonicalUuid] on purpose: one uuid convention per module,
 * and it is already the one the export path validates against.
 */
internal class Advert(input: FingerprintInput) {
    val name: String = input.name?.trim().orEmpty()
    private val lowerName: String = name.lowercase()

    /** Canonical lowercase 128-bit forms; entries that are not uuids at all are dropped. */
    val services: List<String> = input.serviceUuids.mapNotNull { HaProfileBuilder.canonicalUuid(it) }

    private val serviceData: List<Pair<String, ByteArray>> = input.serviceData.entries.mapNotNull { entry ->
        HaProfileBuilder.canonicalUuid(entry.key)?.let { it to entry.value }
    }

    private val manufacturerData: Map<Int, ByteArray> = input.manufacturerData

    fun nameStartsWith(vararg prefixes: String): Boolean = prefixes.any { lowerName.startsWith(it) }

    fun nameContains(fragment: String): Boolean = lowerName.contains(fragment)

    /** First advertised service whose canonical uuid contains [fragment], else null. */
    fun service(fragment: String): String? = services.firstOrNull { fragment in it }

    /** First service-data entry whose canonical key contains [fragment], else null. */
    fun serviceData(fragment: String): Pair<String, ByteArray>? =
        serviceData.firstOrNull { fragment in it.first }

    fun manufacturer(id: Int): ByteArray? = manufacturerData[id]
}

/** A write characteristic and the notify characteristic that answers it, inside one service. */
internal class CommandChannel(
    val service: String,
    val write: String,
    val writeProperties: List<String>,
    val notify: String,
    val notifyProperties: List<String>,
) {
    /** True when one characteristic is both the request and the response path, as on fff1. */
    val bidirectional: Boolean get() = write == notify
}

/**
 * Property names as [dev.nphil.blueshark.ble.GattMapping] writes them ("WRITE",
 * "WRITE_NO_RESPONSE", "NOTIFY", "INDICATE"), folded so the relay's hyphenated spelling and an
 * imported bundle's casing compare equal.
 */
internal fun String.normalisedGattProperty(): String = trim().uppercase().replace('-', '_')

internal val GATT_WRITE_PROPERTIES = setOf("WRITE", "WRITE_NO_RESPONSE", "WRITE_WITHOUT_RESPONSE")

internal val GATT_NOTIFY_PROPERTIES = setOf("NOTIFY", "INDICATE")

/** True when a phone can write this characteristic at all, acknowledged or not. */
internal fun List<String>.writable(): Boolean = any { it.normalisedGattProperty() in GATT_WRITE_PROPERTIES }

/** True when the characteristic takes acknowledged writes (ATT Write Request). */
internal fun List<String>.acknowledgedWrite(): Boolean = any { it.normalisedGattProperty() == "WRITE" }

/** True when the characteristic takes unacknowledged writes (ATT Write Command). */
internal fun List<String>.unacknowledgedWrite(): Boolean =
    any { it.normalisedGattProperty() == "WRITE_NO_RESPONSE" || it.normalisedGattProperty() == "WRITE_WITHOUT_RESPONSE" }

/**
 * The GATT database, normalised the same way as [Advert].
 *
 * Property names are the ones [dev.nphil.blueshark.ble.GattMapping] writes ("WRITE",
 * "WRITE_NO_RESPONSE", "NOTIFY", "INDICATE"); comparison is case-insensitive and tolerant of the
 * hyphenated spelling the relay uses, because a database can also arrive from an imported bundle.
 */
internal class GattView(database: GattDatabase?) {
    private class Characteristic(val uuid: String, val properties: List<String>) {
        val canWrite: Boolean = properties.writable()
        val canNotify: Boolean = properties.any { it.normalisedGattProperty() in GATT_NOTIFY_PROPERTIES }
    }

    private class Service(val uuid: String, val characteristics: List<Characteristic>)

    private val services: List<Service> = database?.services.orEmpty().map { service ->
        Service(
            uuid = canonical(service.uuid),
            characteristics = service.characteristics.map { Characteristic(canonical(it.uuid), it.properties) },
        )
    }

    val isEmpty: Boolean get() = services.isEmpty()

    /**
     * Service uuid, characteristic uuid and that characteristic's properties for the first service
     * containing [serviceFragment] that exposes a characteristic containing [characteristicFragment].
     */
    fun pair(serviceFragment: String, characteristicFragment: String): Triple<String, String, List<String>>? {
        for (service in services) {
            if (serviceFragment !in service.uuid) continue
            val characteristic = service.characteristics.firstOrNull { characteristicFragment in it.uuid }
                ?: continue
            return Triple(service.uuid, characteristic.uuid, characteristic.properties)
        }
        return null
    }

    /**
     * Every service that can carry commands: it has a writable characteristic and a characteristic
     * that notifies. A characteristic doing both is preferred over an arbitrary pair, because that
     * is what a device with a request/response protocol on one handle looks like (fff1 props 0x16).
     */
    fun commandChannels(): List<CommandChannel> = services.mapNotNull { service ->
        val writes = service.characteristics.filter { it.canWrite }
        val notifies = service.characteristics.filter { it.canNotify }
        if (writes.isEmpty() || notifies.isEmpty()) return@mapNotNull null
        val dual = writes.firstOrNull { it.canNotify }
        val write = dual ?: writes.first()
        val notify = dual ?: notifies.first()
        CommandChannel(
            service = service.uuid,
            write = write.uuid,
            writeProperties = write.properties,
            notify = notify.uuid,
            notifyProperties = notify.properties,
        )
    }

    private companion object {
        fun canonical(uuid: String): String = HaProfileBuilder.canonicalUuid(uuid) ?: uuid.trim().lowercase()
    }
}

// ---------------------------------------------------------------------------
// The family table
// ---------------------------------------------------------------------------

internal class Detection(val confidence: FamilyConfidence, val evidence: List<String>)

/**
 * One entry of the family table.
 *
 * [detect] only ever looks at the advertisement, and [confirm] only ever at the GATT database, so
 * that a family cannot be claimed on connection evidence alone: fff0/fff1, 1910/1911 and a201 are
 * reused by unrelated modules, and the generic command-channel heuristic already reports those
 * without asserting a protocol. A non-null [confirm] result raises the verdict to
 * [FamilyConfidence.CERTAIN] and is appended to the evidence.
 */
internal class Family(
    val id: String,
    val name: String,
    val driverUrl: String? = null,
    val codecId: String? = null,
    val hints: List<String> = emptyList(),
    val detect: (Advert) -> Detection?,
    val confirm: (GattView) -> String? = { null },
)

/** Manufacturer id 0x3194 (12692), the one every CoolLED panel advertises. */
internal const val COOLLED_MANUFACTURER = 0x3194

/** Manufacturer id 0xEC88, Govee's. */
internal const val GOVEE_MANUFACTURER = 0xEC88

/** Manufacturer id 0x0211, used by Telink's mesh SDK samples. */
internal const val TELINK_MANUFACTURER = 0x0211

/** Manufacturer id 2306 (0x0902), AC Infinity's. */
internal const val AC_INFINITY_MANUFACTURER = 2306

/** The panel geometry a CoolLED advertisement carries in its manufacturer payload. */
internal class CoolLedPanel(
    val idHex: String,
    val width: Int,
    val height: Int,
    val colour: Int,
    val firmware: Int,
)

/**
 * Reads the 11-byte CoolLED manufacturer layout: 6-byte id, height, big-endian 16-bit width,
 * colour mode, firmware. Verified against the test device (`bcdc070000011000200421` =>
 * id `bcdc07000001`, 32x16, colour 4, firmware 0x21).
 */
internal fun decodeCoolLedPanel(data: ByteArray): CoolLedPanel? {
    if (data.size != 11) return null
    val height = data[6].toInt() and 0xFF
    val width = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
    if (height == 0 || width == 0) return null
    return CoolLedPanel(
        idHex = data.copyOfRange(0, 6).toHex().lowercase(),
        width = width,
        height = height,
        colour = data[9].toInt() and 0xFF,
        firmware = data[10].toInt() and 0xFF,
    )
}

/** The fixed head of a MiBeacon frame, as `xiaomi-ble` reads it. */
internal class MiBeaconFrame(
    val frameControl: Int,
    val productId: Int,
    val counter: Int,
    val mac: String,
    val encrypted: Boolean,
)

/**
 * Reads the first 11 bytes of 0xFE95 service data: little-endian frame control and product id, a
 * frame counter, then the address reversed. Layout as documented on
 * [dev.nphil.blueshark.crypto.Presets] for the MiBeacon decryption preset.
 */
internal fun decodeMiBeacon(data: ByteArray): MiBeaconFrame? {
    if (data.size < 11) return null
    val frameControl = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    val productId = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
    val mac = (10 downTo 5).joinToString(":") { "%02X".format(data[it].toInt() and 0xFF) }
    return MiBeaconFrame(
        frameControl = frameControl,
        productId = productId,
        counter = data[4].toInt() and 0xFF,
        mac = mac,
        encrypted = frameControl and 0x08 != 0,
    )
}

private fun List<String>.propertyList(): String = "[${joinToString(", ")}]"

private fun hex(data: ByteArray): String = data.toHex().lowercase()

/**
 * The families BlueShark can name from a scan.
 *
 * Order is irrelevant - [DeviceFingerprint.identify] sorts by confidence - but every rule must
 * append evidence for each signal it used, and must not claim a family from a signal that is
 * shared with unrelated devices.
 */
internal object Families {

    private val coolled = Family(
        id = "coolled",
        name = "CoolLED (CoolLEDX / iLedClock)",
        driverUrl = "https://github.com/UpDryTwist/coolledx-driver",
        codecId = "coolled",
        hints = listOf("fff1"),
        detect = { advert ->
            val evidence = ArrayList<String>(4)
            val named = advert.nameStartsWith("coolled", "iled")
            if (named) {
                evidence += "advertised name \"${advert.name}\" matches CoolLED naming (CoolLED*/iLed*)"
            }
            val service = advert.service("0000fff0-")
            if (service != null) {
                evidence += "advertised service $service (fff0) is the CoolLED command service"
            }
            val raw = advert.manufacturer(COOLLED_MANUFACTURER)
            var panel: CoolLedPanel? = null
            if (raw != null) {
                panel = decodeCoolLedPanel(raw)
                evidence += if (panel != null) {
                    "manufacturer 0x3194 data ${hex(raw)}: 6-byte id ${panel.idHex}, " +
                        "panel ${panel.width}x${panel.height} px, colour mode ${panel.colour}, " +
                        "firmware 0x${"%02x".format(panel.firmware)}"
                } else {
                    "manufacturer 0x3194 data ${hex(raw)} does not fit the CoolLED layout " +
                        "(11 bytes: 6-byte id, height, width16, colour, firmware)"
                }
            }
            when {
                panel != null -> Detection(FamilyConfidence.LIKELY, evidence)
                named && service != null -> Detection(FamilyConfidence.LIKELY, evidence)
                evidence.isNotEmpty() -> Detection(FamilyConfidence.POSSIBLE, evidence)
                else -> null
            }
        },
        confirm = { gatt ->
            gatt.pair("0000fff0-", "0000fff1-")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: CoolLED command channel confirmed"
            }
        },
    )

    private val xiaomi = Family(
        id = "xiaomi-mibeacon",
        name = "Xiaomi MiBeacon",
        driverUrl = "https://github.com/Bluetooth-Devices/xiaomi-ble",
        detect = { advert ->
            val data = advert.serviceData("0000fe95-")
            val service = advert.service("0000fe95-")
            when {
                data != null -> {
                    val evidence = ArrayList<String>(3)
                    evidence += "service data ${data.first} (fe95) ${hex(data.second)} is a MiBeacon frame"
                    val frame = decodeMiBeacon(data.second)
                    if (frame != null) {
                        evidence += "MiBeacon frame control 0x${"%04x".format(frame.frameControl)}, " +
                            "product id 0x${"%04x".format(frame.productId)}, counter ${frame.counter}, " +
                            "device ${frame.mac}"
                        if (frame.encrypted) {
                            evidence += "frame control bit 0x08 is set: the payload is encrypted, " +
                                "the MiBeacon AES-CCM preset applies"
                        }
                    }
                    Detection(FamilyConfidence.LIKELY, evidence)
                }

                service != null -> Detection(
                    FamilyConfidence.POSSIBLE,
                    listOf("advertised service $service (fe95) is Xiaomi's, but no fe95 service data was seen"),
                )

                else -> null
            }
        },
    )

    private val govee = Family(
        id = "govee",
        name = "Govee",
        driverUrl = "https://github.com/Bluetooth-Devices/govee-ble",
        hints = listOf("1911"),
        detect = { advert ->
            val evidence = ArrayList<String>(2)
            val raw = advert.manufacturer(GOVEE_MANUFACTURER)
            if (raw != null) {
                evidence += "manufacturer 0xEC88 data ${hex(raw)} is Govee's"
            }
            val named = advert.nameStartsWith("govee", "gvh", "ihoment")
            if (named) {
                evidence += "advertised name \"${advert.name}\" matches Govee naming (Govee_*/GVH*/ihoment*)"
            }
            when {
                raw != null -> Detection(FamilyConfidence.LIKELY, evidence)
                named -> Detection(FamilyConfidence.POSSIBLE, evidence)
                else -> null
            }
        },
        confirm = { gatt ->
            gatt.pair("0a0b0c0d1910", "0a0b0c0d1911")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: Govee 1910/1911 command channel confirmed"
            }
        },
    )

    private val telink = Family(
        id = "telink-mesh",
        name = "Telink mesh",
        driverUrl = "https://github.com/mjg59/python-tikteck",
        hints = listOf("1911"),
        detect = { advert ->
            val evidence = ArrayList<String>(2)
            val service = advert.service("1910") ?: advert.service("1911")
            if (service != null) {
                evidence += "advertised service $service is Telink's mesh service (1910/1911)"
            }
            val raw = advert.manufacturer(TELINK_MANUFACTURER)
            if (raw != null) {
                evidence += "manufacturer 0x0211 data ${hex(raw)} is Telink's"
            }
            when {
                service != null || raw != null -> Detection(FamilyConfidence.LIKELY, evidence)
                else -> null
            }
        },
        confirm = { gatt ->
            gatt.pair("0a0b0c0d1910", "0a0b0c0d1911")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: Telink 1910/1911 command channel confirmed"
            }
        },
    )

    private val tuya = Family(
        id = "tuya-ble",
        name = "Tuya BLE",
        driverUrl = "https://github.com/PlusPlus-ua/ha_tuya_ble",
        hints = listOf("a202"),
        detect = { advert ->
            val evidence = ArrayList<String>(2)
            val data = advert.serviceData("0000fd50-") ?: advert.serviceData("0000a201-")
            if (data != null) {
                evidence += "service data ${data.first} ${hex(data.second)} is a Tuya BLE frame"
            }
            val service = advert.service("0000fd50-")
            if (service != null) {
                evidence += "advertised service $service (fd50) is Tuya's"
            }
            when {
                data != null -> Detection(FamilyConfidence.LIKELY, evidence)
                service != null -> Detection(FamilyConfidence.POSSIBLE, evidence)
                else -> null
            }
        },
        confirm = { gatt ->
            gatt.pair("0000a201-", "0000a202-")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: Tuya a201/a202 command channel confirmed"
            }
        },
    )

    private val nordicUart = Family(
        id = "nordic-uart",
        name = "Nordic UART service",
        hints = listOf("6e400002"),
        detect = { advert ->
            advert.service("6e400001-b5a3")?.let { service ->
                Detection(
                    FamilyConfidence.LIKELY,
                    listOf("advertised service $service is the Nordic UART service (RX 6e400002, TX 6e400003)"),
                )
            }
        },
        confirm = { gatt ->
            gatt.pair("6e400001-b5a3", "6e400002-b5a3")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: Nordic UART RX confirmed"
            }
        },
    )

    private val bedJet = Family(
        id = "bedjet",
        name = "BedJet",
        driverUrl = "https://www.home-assistant.io/integrations/bedjet/",
        hints = listOf("2004"),
        detect = { advert ->
            val evidence = ArrayList<String>(2)
            val service = advert.service("-bed0-")
            if (service != null) {
                evidence += "advertised service $service carries BedJet's `bed0` uuid block"
            }
            val named = advert.nameStartsWith("bedjet")
            if (named) {
                evidence += "advertised name \"${advert.name}\" matches BedJet naming"
            }
            when {
                service != null || named -> Detection(FamilyConfidence.LIKELY, evidence)
                else -> null
            }
        },
        confirm = { gatt ->
            gatt.pair("00001000-bed0", "00002004-bed0")?.let { (service, characteristic, properties) ->
                "GATT service $service exposes characteristic $characteristic " +
                    "${properties.propertyList()}: BedJet command characteristic confirmed"
            }
        },
    )

    private val acInfinity = Family(
        id = "ac-infinity",
        name = "AC Infinity",
        driverUrl = "https://github.com/hunterjm/ac-infinity-ble",
        detect = { advert ->
            val evidence = ArrayList<String>(2)
            val raw = advert.manufacturer(AC_INFINITY_MANUFACTURER)
            if (raw != null) {
                evidence += "manufacturer 2306 (0x0902) data ${hex(raw)} is AC Infinity's"
            }
            val named = advert.nameContains("ac infinity") || advert.nameStartsWith("acinfinity")
            if (named) {
                evidence += "advertised name \"${advert.name}\" matches AC Infinity naming"
            }
            when {
                raw != null -> Detection(FamilyConfidence.LIKELY, evidence)
                named -> Detection(FamilyConfidence.POSSIBLE, evidence)
                else -> null
            }
        },
    )

    val all: List<Family> = listOf(coolled, xiaomi, govee, telink, tuya, nordicUart, bedJet, acInfinity)
}

/**
 * The last-resort reading of a GATT database: a service that can be written to and that notifies
 * is a command channel, whatever protocol runs over it.
 *
 * One match per qualifying service, so [FamilyMatch.commandCharacteristicHints] names exactly the
 * characteristic the prober should target for that service. The id carries the service's uuid tail
 * for the same reason - two candidate channels must not collapse into one card.
 */
internal fun commandChannelMatches(gatt: GattView): List<FamilyMatch> = gatt.commandChannels().map { channel ->
    val evidence = if (channel.bidirectional) {
        "GATT service ${channel.service}: characteristic ${channel.write} " +
            "${channel.writeProperties.propertyList()} both takes writes and notifies - " +
            "a command channel that answers on itself"
    } else {
        "GATT service ${channel.service}: write characteristic ${channel.write} " +
            "${channel.writeProperties.propertyList()} paired with notify characteristic " +
            "${channel.notify} ${channel.notifyProperties.propertyList()}"
    }
    FamilyMatch(
        familyId = "command-channel:${channel.service.tail()}",
        name = "Command channel on ${channel.service.tail()}",
        confidence = FamilyConfidence.POSSIBLE,
        evidence = listOf(evidence),
        publicDriverUrl = null,
        codecId = null,
        commandCharacteristicHints = listOfNotNull(
            channel.write.tail(),
            channel.notify.tail().takeIf { !channel.bidirectional },
        ),
    )
}

/**
 * The part of a uuid an operator recognises: the 16-bit short for base-uuid attributes ("fff1"),
 * the last four hex digits otherwise ("1911").
 */
internal fun String.tail(): String {
    val short = SHORT_FORM.matchEntire(this)?.groupValues?.get(1)
    return short ?: takeLast(4)
}

private val SHORT_FORM = Regex("0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb")
