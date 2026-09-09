package dev.nphil.blestudio.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import dev.nphil.blestudio.model.GattCharacteristicRecord
import dev.nphil.blestudio.model.GattDatabase
import dev.nphil.blestudio.model.GattDescriptorRecord
import dev.nphil.blestudio.model.GattServiceRecord
import java.util.Locale
import java.util.UUID

/**
 * Stable, serialisable pointer to one characteristic of the currently connected device.
 *
 * Instance ids come straight from the platform and are derived from the ATT handle, so the pair
 * (service instance, characteristic instance) is unique for the lifetime of a connection even when
 * a peripheral exposes the same UUID several times.
 */
data class CharacteristicRef(
    val serviceUuid: String,
    val serviceInstanceId: Int,
    val uuid: String,
    val instanceId: Int,
)

/**
 * Pointer to one descriptor.
 *
 * The platform does not expose descriptor instance ids to applications, so a descriptor is
 * addressed by its owning characteristic plus its UUID and its position inside that characteristic.
 */
data class DescriptorRef(
    val characteristic: CharacteristicRef,
    val uuid: String,
    val index: Int,
)

/** Client Characteristic Configuration descriptor: the notification/indication switch. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

fun BluetoothGattService.ref(characteristic: BluetoothGattCharacteristic): CharacteristicRef =
    CharacteristicRef(
        serviceUuid = uuid.toString(),
        serviceInstanceId = instanceId,
        uuid = characteristic.uuid.toString(),
        instanceId = characteristic.instanceId,
    )

fun BluetoothGattCharacteristic.refWithin(service: BluetoothGattService): CharacteristicRef = service.ref(this)

/** Property bit -> human name, ordered the way the ATT spec lists them. */
private val PROPERTY_NAMES = arrayOf(
    BluetoothGattCharacteristic.PROPERTY_BROADCAST to "BROADCAST",
    BluetoothGattCharacteristic.PROPERTY_READ to "READ",
    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "WRITE_NO_RESPONSE",
    BluetoothGattCharacteristic.PROPERTY_WRITE to "WRITE",
    BluetoothGattCharacteristic.PROPERTY_NOTIFY to "NOTIFY",
    BluetoothGattCharacteristic.PROPERTY_INDICATE to "INDICATE",
    BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE to "SIGNED_WRITE",
    BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS to "EXTENDED_PROPS",
)

private val PERMISSION_NAMES = arrayOf(
    BluetoothGattCharacteristic.PERMISSION_READ to "READ",
    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED to "READ_ENCRYPTED",
    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM to "READ_ENCRYPTED_MITM",
    BluetoothGattCharacteristic.PERMISSION_WRITE to "WRITE",
    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED to "WRITE_ENCRYPTED",
    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM to "WRITE_ENCRYPTED_MITM",
    BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED to "WRITE_SIGNED",
    BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM to "WRITE_SIGNED_MITM",
)

fun propertyNames(properties: Int): List<String> =
    PROPERTY_NAMES.mapNotNull { (bit, name) -> name.takeIf { properties and bit != 0 } }

fun permissionNames(permissions: Int): List<String> =
    PERMISSION_NAMES.mapNotNull { (bit, name) -> name.takeIf { permissions and bit != 0 } }

fun BluetoothGattDescriptor.permissionNamesOf(): List<String> = permissionNames(permissions)

private fun serviceTypeName(type: Int): String = when (type) {
    BluetoothGattService.SERVICE_TYPE_PRIMARY -> "PRIMARY"
    BluetoothGattService.SERVICE_TYPE_SECONDARY -> "SECONDARY"
    else -> "UNKNOWN"
}

/** Snapshot the live platform service list into the serialisable evidence model. */
fun List<BluetoothGattService>.toGattDatabase(): GattDatabase = GattDatabase(
    services = map { service ->
        GattServiceRecord(
            uuid = service.uuid.toString(),
            instanceId = service.instanceId,
            type = serviceTypeName(service.type),
            characteristics = service.characteristics.map { characteristic ->
                GattCharacteristicRecord(
                    uuid = characteristic.uuid.toString(),
                    instanceId = characteristic.instanceId,
                    properties = propertyNames(characteristic.properties),
                    permissions = permissionNames(characteristic.permissions),
                    descriptors = characteristic.descriptors.mapIndexed { index, descriptor ->
                        GattDescriptorRecord(
                            uuid = descriptor.uuid.toString(),
                            instanceId = index,
                            permissions = permissionNames(descriptor.permissions),
                        )
                    },
                )
            },
        )
    },
)

/**
 * `0000180f-0000-1000-8000-00805f9b34fb` -> `180F`; any other UUID is returned unchanged
 * (uppercased) so callers can print it verbatim.
 */
fun shortUuid(uuid: String): String {
    val upper = uuid.uppercase(Locale.ROOT)
    if (upper.length != 36 || !upper.endsWith(BASE_UUID_SUFFIX.uppercase(Locale.ROOT))) return upper
    val group = upper.substring(0, 8)
    return if (group.startsWith("0000")) group.substring(4) else group
}

/**
 * Display-only names for the handful of assigned numbers a BLE gadget is likely to expose, plus
 * the custom services that show up constantly on cheap modules. Never used for behaviour.
 */
private val WELL_KNOWN: Map<String, String> = mapOf(
    // GATT services
    "1800" to "Generic Access",
    "1801" to "Generic Attribute",
    "1802" to "Immediate Alert",
    "1803" to "Link Loss",
    "1804" to "Tx Power",
    "1805" to "Current Time",
    "180A" to "Device Information",
    "180D" to "Heart Rate",
    "180F" to "Battery",
    "1810" to "Blood Pressure",
    "1812" to "Human Interface Device",
    "181A" to "Environmental Sensing",
    "181B" to "Body Composition",
    "1826" to "Fitness Machine",
    "FE59" to "Nordic DFU",
    // GATT characteristics
    "2A00" to "Device Name",
    "2A01" to "Appearance",
    "2A04" to "Preferred Connection Parameters",
    "2A05" to "Service Changed",
    "2A19" to "Battery Level",
    "2A23" to "System ID",
    "2A24" to "Model Number",
    "2A25" to "Serial Number",
    "2A26" to "Firmware Revision",
    "2A27" to "Hardware Revision",
    "2A28" to "Software Revision",
    "2A29" to "Manufacturer Name",
    "2A2B" to "Current Time",
    "2A37" to "Heart Rate Measurement",
    "2A38" to "Body Sensor Location",
    "2A50" to "PnP ID",
    "2A6E" to "Temperature",
    "2A6F" to "Humidity",
    "2A9D" to "Weight Measurement",
    // Descriptors
    "2900" to "Extended Properties",
    "2901" to "User Description",
    "2902" to "Client Characteristic Configuration",
    "2903" to "Server Characteristic Configuration",
    "2904" to "Presentation Format",
    "2905" to "Aggregate Format",
    "2908" to "Report Reference",
    // Vendor blocks that appear on most cheap modules
    "FFF0" to "Vendor FFF0 (generic serial)",
    "FFF1" to "Vendor FFF1",
    "FFF2" to "Vendor FFF2",
    "FFF3" to "Vendor FFF3",
    "FFF4" to "Vendor FFF4",
    "FFF5" to "Vendor FFF5",
    "FFF6" to "Vendor FFF6",
    "FFE0" to "Vendor FFE0 (HM-10 serial)",
    "FFE1" to "Vendor FFE1 (HM-10 data)",
    "FFE2" to "Vendor FFE2",
    "AE00" to "Vendor AE00 (Telink serial)",
    "AE01" to "Vendor AE01 (write)",
    "AE02" to "Vendor AE02 (notify)",
    "AE03" to "Vendor AE03",
    "AE04" to "Vendor AE04",
    "FEE7" to "Tencent / vendor OTA",
    "FD00" to "Vendor FD00",
)

private val NORDIC_UART: Map<String, String> = mapOf(
    "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART Service",
    "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART RX (write)",
    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART TX (notify)",
    "0000FE59-0000-1000-8000-00805F9B34FB" to "Nordic DFU",
    "8EC90003-F315-4F60-9FB8-838830DAEA50" to "Nordic Buttonless DFU",
)

/** Human label for a UUID, or `null` when nothing is known about it. */
fun wellKnownName(uuid: String): String? {
    val upper = uuid.uppercase(Locale.ROOT)
    NORDIC_UART[upper]?.let { return it }
    val short = shortUuid(upper)
    return if (short.length == 4) WELL_KNOWN[short] else null
}

/** `Battery` or, when unknown, the short form of the UUID itself. */
fun displayName(uuid: String): String = wellKnownName(uuid) ?: shortUuid(uuid)
