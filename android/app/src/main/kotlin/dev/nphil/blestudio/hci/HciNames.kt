package dev.nphil.blestudio.hci

import java.util.Locale

/**
 * Field layouts, name tables and shared byte helpers for the layers a btsnoop capture carries.
 *
 * Every table is taken from the Bluetooth Core Specification v5.4:
 *  - HCI events and commands: Vol 4 Part E, Section 7.7 (events) and 7.8 (LE commands).
 *  - HCI error / status codes: Vol 1 Part F, Section 1.3.
 *  - L2CAP signalling: Vol 3 Part A, Section 4.
 *  - SMP: Vol 3 Part H, Section 3.
 *  - ATT: Vol 3 Part F, Sections 3.4 (PDUs) and 3.4.1.1 (error codes).
 *  - Advertising data (AD) structures: Core Specification Supplement, Part A, Section 1.
 *
 * The `when` tables compile to switches, so a lookup neither allocates nor boxes; only the
 * "unknown" branches format a string, which is exactly where a capture surprised us.
 */
object HciNames {

    // ------------------------------------------------------------------ H4 (Vol 4 Part A, 2)

    const val H4_COMMAND = 0x01
    const val H4_ACL = 0x02
    const val H4_SCO = 0x03
    const val H4_EVENT = 0x04
    const val H4_ISO = 0x05

    fun h4TypeName(type: Int): String = when (type) {
        H4_COMMAND -> "HCI Command"
        H4_ACL -> "HCI ACL Data"
        H4_SCO -> "HCI Synchronous Data"
        H4_EVENT -> "HCI Event"
        H4_ISO -> "HCI ISO Data"
        else -> "Unknown H4 packet type (0x%02X)".format(type)
    }

    /** ACL Packet_Boundary_Flag, Vol 4 Part E, Section 5.4.2. */
    fun packetBoundaryName(flag: Int): String = when (flag) {
        0x00 -> "First non-automatically-flushable"
        0x01 -> "Continuing fragment"
        0x02 -> "First automatically-flushable"
        0x03 -> "Complete L2CAP PDU (no fragmentation)"
        else -> "0x%02X".format(flag)
    }

    /** ACL Broadcast_Flag, Vol 4 Part E, Section 5.4.2. */
    fun broadcastFlagName(flag: Int): String = when (flag) {
        0x00 -> "Point-to-point"
        0x01 -> "BR/EDR broadcast"
        0x02 -> "BR/EDR active peripheral broadcast"
        else -> "0x%02X".format(flag)
    }

    // ------------------------------------------------------------------ HCI events (Vol 4 Part E, 7.7)

    const val EVT_DISCONNECTION_COMPLETE = 0x05
    const val EVT_ENCRYPTION_CHANGE = 0x08
    const val EVT_COMMAND_COMPLETE = 0x0E
    const val EVT_COMMAND_STATUS = 0x0F
    const val EVT_NUMBER_OF_COMPLETED_PACKETS = 0x13
    const val EVT_LE_META = 0x3E
    const val EVT_ENCRYPTION_CHANGE_V2 = 0x59

    fun eventName(code: Int): String = when (code) {
        0x01 -> "Inquiry Complete"
        0x02 -> "Inquiry Result"
        0x03 -> "Connection Complete"
        0x04 -> "Connection Request"
        0x05 -> "Disconnection Complete"
        0x06 -> "Authentication Complete"
        0x07 -> "Remote Name Request Complete"
        0x08 -> "Encryption Change"
        0x09 -> "Change Connection Link Key Complete"
        0x0B -> "Read Remote Supported Features Complete"
        0x0C -> "Read Remote Version Information Complete"
        0x0D -> "QoS Setup Complete"
        0x0E -> "Command Complete"
        0x0F -> "Command Status"
        0x10 -> "Hardware Error"
        0x11 -> "Flush Occurred"
        0x12 -> "Role Change"
        0x13 -> "Number Of Completed Packets"
        0x14 -> "Mode Change"
        0x16 -> "PIN Code Request"
        0x17 -> "Link Key Request"
        0x18 -> "Link Key Notification"
        0x1A -> "Data Buffer Overflow"
        0x1C -> "Read Clock Offset Complete"
        0x1D -> "Connection Packet Type Changed"
        0x20 -> "Page Scan Repetition Mode Change"
        0x2F -> "Extended Inquiry Result"
        0x30 -> "Encryption Key Refresh Complete"
        0x31 -> "IO Capability Request"
        0x32 -> "IO Capability Response"
        0x33 -> "User Confirmation Request"
        0x34 -> "User Passkey Request"
        0x35 -> "Remote OOB Data Request"
        0x36 -> "Simple Pairing Complete"
        0x38 -> "Link Supervision Timeout Changed"
        0x3B -> "User Passkey Notification"
        0x3C -> "Keypress Notification"
        0x3D -> "Remote Host Supported Features Notification"
        0x3E -> "LE Meta"
        0x57 -> "Authenticated Payload Timeout Expired"
        0x58 -> "SAM Status Change"
        0x59 -> "Encryption Change [v2]"
        0xFF -> "Vendor-Specific"
        else -> "HCI event 0x%02X".format(code)
    }

    // ------------------------------------------------------------------ LE meta subevents (7.7.65)

    const val LE_CONNECTION_COMPLETE = 0x01
    const val LE_ADVERTISING_REPORT = 0x02
    const val LE_CONNECTION_UPDATE_COMPLETE = 0x03
    const val LE_READ_REMOTE_FEATURES_COMPLETE = 0x04
    const val LE_DATA_LENGTH_CHANGE = 0x07
    const val LE_ENHANCED_CONNECTION_COMPLETE = 0x0A
    const val LE_DIRECT_ADVERTISING_REPORT = 0x0B
    const val LE_PHY_UPDATE_COMPLETE = 0x0C
    const val LE_EXTENDED_ADVERTISING_REPORT = 0x0D

    fun leSubeventName(code: Int): String = when (code) {
        0x01 -> "LE Connection Complete"
        0x02 -> "LE Advertising Report"
        0x03 -> "LE Connection Update Complete"
        0x04 -> "LE Read Remote Features Complete"
        0x05 -> "LE Long Term Key Request"
        0x06 -> "LE Remote Connection Parameter Request"
        0x07 -> "LE Data Length Change"
        0x08 -> "LE Read Local P-256 Public Key Complete"
        0x09 -> "LE Generate DHKey Complete"
        0x0A -> "LE Enhanced Connection Complete"
        0x0B -> "LE Direct Advertising Report"
        0x0C -> "LE PHY Update Complete"
        0x0D -> "LE Extended Advertising Report"
        0x0E -> "LE Periodic Advertising Sync Established"
        0x0F -> "LE Periodic Advertising Report"
        0x10 -> "LE Periodic Advertising Sync Lost"
        0x11 -> "LE Scan Timeout"
        0x12 -> "LE Advertising Set Terminated"
        0x13 -> "LE Scan Request Received"
        0x14 -> "LE Channel Selection Algorithm"
        0x17 -> "LE CTE Request Failed"
        0x19 -> "LE CIS Established"
        0x1A -> "LE CIS Request"
        0x1F -> "LE Request Peer SCA Complete"
        0x21 -> "LE Transmit Power Reporting"
        0x22 -> "LE BIGInfo Advertising Report"
        0x23 -> "LE Subrate Change"
        else -> "LE subevent 0x%02X".format(code)
    }

    // ------------------------------------------------------------------ HCI commands (7.8, 7.1)

    const val OGF_LINK_CONTROL = 0x01
    const val OGF_LE_CONTROLLER = 0x08

    const val CMD_DISCONNECT = 0x0406
    const val CMD_LE_SET_ADVERTISING_DATA = 0x2008
    const val CMD_LE_SET_SCAN_PARAMETERS = 0x200B
    const val CMD_LE_SET_SCAN_ENABLE = 0x200C
    const val CMD_LE_CREATE_CONNECTION = 0x200D
    const val CMD_LE_CREATE_CONNECTION_CANCEL = 0x200E
    const val CMD_LE_CONNECTION_UPDATE = 0x2013
    const val CMD_LE_ENABLE_ENCRYPTION = 0x2019
    const val CMD_LE_EXTENDED_CREATE_CONNECTION = 0x2043

    fun opcodeGroup(opcode: Int): Int = (opcode shr 10) and 0x3F

    fun opcodeCommand(opcode: Int): Int = opcode and 0x03FF

    /**
     * Command names. The OGF 0x08 (LE Controller, Section 7.8) range is complete up to Core v5.4;
     * other groups carry the handful of commands a BLE capture actually contains, and anything
     * else is reported by its numeric OGF/OCF rather than guessed at.
     */
    fun commandName(opcode: Int): String {
        val ogf = opcodeGroup(opcode)
        val ocf = opcodeCommand(opcode)
        if (ogf == OGF_LE_CONTROLLER) leCommandName(ocf)?.let { return it }
        return when (opcode) {
            0x0401 -> "Inquiry"
            0x0405 -> "Create Connection"
            0x0406 -> "Disconnect"
            0x0408 -> "Create Connection Cancel"
            0x0409 -> "Accept Connection Request"
            0x040B -> "Link Key Request Reply"
            0x040D -> "PIN Code Request Reply"
            0x041D -> "Read Remote Version Information"
            0x042B -> "IO Capability Request Reply"
            0x0C01 -> "Set Event Mask"
            0x0C03 -> "Reset"
            0x0C13 -> "Write Local Name"
            0x0C14 -> "Read Local Name"
            0x0C1A -> "Write Scan Enable"
            0x0C31 -> "Set Controller To Host Flow Control"
            0x0C35 -> "Host Buffer Size"
            0x0C56 -> "Write Simple Pairing Mode"
            0x0C6C -> "Read LE Host Support"
            0x0C6D -> "Write LE Host Support"
            0x0C7B -> "Read Authenticated Payload Timeout"
            0x0C7C -> "Write Authenticated Payload Timeout"
            0x1001 -> "Read Local Version Information"
            0x1002 -> "Read Local Supported Commands"
            0x1003 -> "Read Local Supported Features"
            0x1005 -> "Read Buffer Size"
            0x1009 -> "Read BD_ADDR"
            0x1405 -> "Read RSSI"
            0x1408 -> "Read Encryption Key Size"
            else -> "OGF 0x%02X OCF 0x%03X".format(ogf, ocf)
        }
    }

    /** OGF 0x08, Section 7.8. Null means "not assigned in Core v5.4". */
    private fun leCommandName(ocf: Int): String? = when (ocf) {
        0x001 -> "LE Set Event Mask"
        0x002 -> "LE Read Buffer Size"
        0x003 -> "LE Read Local Supported Features"
        0x005 -> "LE Set Random Address"
        0x006 -> "LE Set Advertising Parameters"
        0x007 -> "LE Read Advertising Physical Channel Tx Power"
        0x008 -> "LE Set Advertising Data"
        0x009 -> "LE Set Scan Response Data"
        0x00A -> "LE Set Advertising Enable"
        0x00B -> "LE Set Scan Parameters"
        0x00C -> "LE Set Scan Enable"
        0x00D -> "LE Create Connection"
        0x00E -> "LE Create Connection Cancel"
        0x00F -> "LE Read Filter Accept List Size"
        0x010 -> "LE Clear Filter Accept List"
        0x011 -> "LE Add Device To Filter Accept List"
        0x012 -> "LE Remove Device From Filter Accept List"
        0x013 -> "LE Connection Update"
        0x014 -> "LE Set Host Channel Classification"
        0x015 -> "LE Read Channel Map"
        0x016 -> "LE Read Remote Features"
        0x017 -> "LE Encrypt"
        0x018 -> "LE Rand"
        0x019 -> "LE Enable Encryption"
        0x01A -> "LE Long Term Key Request Reply"
        0x01B -> "LE Long Term Key Request Negative Reply"
        0x01C -> "LE Read Supported States"
        0x01D -> "LE Receiver Test"
        0x01E -> "LE Transmitter Test"
        0x01F -> "LE Test End"
        0x020 -> "LE Remote Connection Parameter Request Reply"
        0x021 -> "LE Remote Connection Parameter Request Negative Reply"
        0x022 -> "LE Set Data Length"
        0x023 -> "LE Read Suggested Default Data Length"
        0x024 -> "LE Write Suggested Default Data Length"
        0x025 -> "LE Read Local P-256 Public Key"
        0x026 -> "LE Generate DHKey"
        0x027 -> "LE Add Device To Resolving List"
        0x028 -> "LE Remove Device From Resolving List"
        0x029 -> "LE Clear Resolving List"
        0x02A -> "LE Read Resolving List Size"
        0x02B -> "LE Read Peer Resolvable Address"
        0x02C -> "LE Read Local Resolvable Address"
        0x02D -> "LE Set Address Resolution Enable"
        0x02E -> "LE Set Resolvable Private Address Timeout"
        0x02F -> "LE Read Maximum Data Length"
        0x030 -> "LE Read PHY"
        0x031 -> "LE Set Default PHY"
        0x032 -> "LE Set PHY"
        0x033 -> "LE Receiver Test [v2]"
        0x034 -> "LE Transmitter Test [v2]"
        0x035 -> "LE Set Advertising Set Random Address"
        0x036 -> "LE Set Extended Advertising Parameters"
        0x037 -> "LE Set Extended Advertising Data"
        0x038 -> "LE Set Extended Scan Response Data"
        0x039 -> "LE Set Extended Advertising Enable"
        0x03A -> "LE Read Maximum Advertising Data Length"
        0x03B -> "LE Read Number Of Supported Advertising Sets"
        0x03C -> "LE Remove Advertising Set"
        0x03D -> "LE Clear Advertising Sets"
        0x03E -> "LE Set Periodic Advertising Parameters"
        0x03F -> "LE Set Periodic Advertising Data"
        0x040 -> "LE Set Periodic Advertising Enable"
        0x041 -> "LE Set Extended Scan Parameters"
        0x042 -> "LE Set Extended Scan Enable"
        0x043 -> "LE Extended Create Connection"
        0x044 -> "LE Periodic Advertising Create Sync"
        0x045 -> "LE Periodic Advertising Create Sync Cancel"
        0x046 -> "LE Periodic Advertising Terminate Sync"
        0x047 -> "LE Add Device To Periodic Advertiser List"
        0x048 -> "LE Remove Device From Periodic Advertiser List"
        0x049 -> "LE Clear Periodic Advertiser List"
        0x04A -> "LE Read Periodic Advertiser List Size"
        0x04B -> "LE Read Transmit Power"
        0x04C -> "LE Read RF Path Compensation"
        0x04D -> "LE Write RF Path Compensation"
        0x04E -> "LE Set Privacy Mode"
        0x04F -> "LE Receiver Test [v3]"
        0x050 -> "LE Transmitter Test [v3]"
        0x051 -> "LE Set Connectionless CTE Transmit Parameters"
        0x052 -> "LE Set Connectionless CTE Transmit Enable"
        0x053 -> "LE Set Connectionless IQ Sampling Enable"
        0x054 -> "LE Set Connection CTE Receive Parameters"
        0x055 -> "LE Set Connection CTE Transmit Parameters"
        0x056 -> "LE Connection CTE Request Enable"
        0x057 -> "LE Connection CTE Response Enable"
        0x058 -> "LE Read Antenna Information"
        0x059 -> "LE Set Periodic Advertising Receive Enable"
        0x05A -> "LE Periodic Advertising Sync Transfer"
        0x05B -> "LE Periodic Advertising Set Info Transfer"
        0x05C -> "LE Set Periodic Advertising Sync Transfer Parameters"
        0x05D -> "LE Set Default Periodic Advertising Sync Transfer Parameters"
        0x05E -> "LE Generate DHKey [v2]"
        0x05F -> "LE Modify Sleep Clock Accuracy"
        0x060 -> "LE Read Buffer Size [v2]"
        0x061 -> "LE Read ISO Tx Sync"
        0x062 -> "LE Set CIG Parameters"
        0x063 -> "LE Set CIG Parameters Test"
        0x064 -> "LE Create CIS"
        0x065 -> "LE Remove CIG"
        0x066 -> "LE Accept CIS Request"
        0x067 -> "LE Reject CIS Request"
        0x068 -> "LE Create BIG"
        0x069 -> "LE Create BIG Test"
        0x06A -> "LE Terminate BIG"
        0x06B -> "LE BIG Create Sync"
        0x06C -> "LE BIG Terminate Sync"
        0x06D -> "LE Request Peer SCA"
        0x06E -> "LE Setup ISO Data Path"
        0x06F -> "LE Remove ISO Data Path"
        0x070 -> "LE ISO Transmit Test"
        0x071 -> "LE ISO Receive Test"
        0x072 -> "LE ISO Read Test Counters"
        0x073 -> "LE ISO Test End"
        0x074 -> "LE Set Host Feature"
        0x075 -> "LE Read ISO Link Quality"
        0x076 -> "LE Enhanced Read Transmit Power Level"
        0x077 -> "LE Read Remote Transmit Power Level"
        0x078 -> "LE Set Path Loss Reporting Parameters"
        0x079 -> "LE Set Path Loss Reporting Enable"
        0x07A -> "LE Set Transmit Power Reporting Enable"
        0x07B -> "LE Transmitter Test [v4]"
        0x07C -> "LE Set Data Related Address Changes"
        0x07D -> "LE Set Default Subrate"
        0x07E -> "LE Subrate Request"
        0x07F -> "LE Set Extended Advertising Parameters [v2]"
        else -> null
    }

    // ------------------------------------------------------------------ status codes (Vol 1 Part F, 1.3)

    const val STATUS_SUCCESS = 0x00
    const val STATUS_CONNECTION_TIMEOUT = 0x08
    const val STATUS_REMOTE_USER_TERMINATED = 0x13
    const val STATUS_LOCAL_HOST_TERMINATED = 0x16

    fun statusText(code: Int): String = when (code) {
        0x00 -> "Success"
        0x01 -> "Unknown HCI Command"
        0x02 -> "Unknown Connection Identifier"
        0x03 -> "Hardware Failure"
        0x04 -> "Page Timeout"
        0x05 -> "Authentication Failure"
        0x06 -> "PIN or Key Missing"
        0x07 -> "Memory Capacity Exceeded"
        0x08 -> "Connection Timeout"
        0x09 -> "Connection Limit Exceeded"
        0x0A -> "Synchronous Connection Limit To A Device Exceeded"
        0x0B -> "Connection Already Exists"
        0x0C -> "Command Disallowed"
        0x0D -> "Connection Rejected due to Limited Resources"
        0x0E -> "Connection Rejected due to Security Reasons"
        0x0F -> "Connection Rejected due to Unacceptable BD_ADDR"
        0x10 -> "Connection Accept Timeout Exceeded"
        0x11 -> "Unsupported Feature or Parameter Value"
        0x12 -> "Invalid HCI Command Parameters"
        0x13 -> "Remote User Terminated Connection"
        0x14 -> "Remote Device Terminated Connection due to Low Resources"
        0x15 -> "Remote Device Terminated Connection due to Power Off"
        0x16 -> "Connection Terminated By Local Host"
        0x17 -> "Repeated Attempts"
        0x18 -> "Pairing Not Allowed"
        0x19 -> "Unknown LMP PDU"
        0x1A -> "Unsupported Remote Feature"
        0x1B -> "SCO Offset Rejected"
        0x1C -> "SCO Interval Rejected"
        0x1D -> "SCO Air Mode Rejected"
        0x1E -> "Invalid LMP Parameters / Invalid LL Parameters"
        0x1F -> "Unspecified Error"
        0x20 -> "Unsupported LMP Parameter Value / Unsupported LL Parameter Value"
        0x21 -> "Role Change Not Allowed"
        0x22 -> "LMP Response Timeout / LL Response Timeout"
        0x23 -> "LMP Error Transaction Collision / LL Procedure Collision"
        0x24 -> "LMP PDU Not Allowed"
        0x25 -> "Encryption Mode Not Acceptable"
        0x26 -> "Link Key cannot be Changed"
        0x27 -> "Requested QoS Not Supported"
        0x28 -> "Instant Passed"
        0x29 -> "Pairing With Unit Key Not Supported"
        0x2A -> "Different Transaction Collision"
        0x2C -> "QoS Unacceptable Parameter"
        0x2D -> "QoS Rejected"
        0x2E -> "Channel Classification Not Supported"
        0x2F -> "Insufficient Security"
        0x30 -> "Parameter Out Of Mandatory Range"
        0x32 -> "Role Switch Pending"
        0x34 -> "Reserved Slot Violation"
        0x35 -> "Role Switch Failed"
        0x36 -> "Extended Inquiry Response Too Large"
        0x37 -> "Secure Simple Pairing Not Supported By Host"
        0x38 -> "Host Busy - Pairing"
        0x39 -> "Connection Rejected due to No Suitable Channel Found"
        0x3A -> "Controller Busy"
        0x3B -> "Unacceptable Connection Parameters"
        0x3C -> "Advertising Timeout"
        0x3D -> "Connection Terminated due to MIC Failure"
        0x3E -> "Connection Failed to be Established / Synchronization Timeout"
        0x40 -> "Coarse Clock Adjustment Rejected"
        0x41 -> "Type0 Submap Not Defined"
        0x42 -> "Unknown Advertising Identifier"
        0x43 -> "Limit Reached"
        0x44 -> "Operation Cancelled by Host"
        0x45 -> "Packet Too Long"
        0x46 -> "Too Late"
        0x47 -> "Too Early"
        0x48 -> "Insufficient Channels"
        else -> "Status 0x%02X".format(code)
    }

    // ------------------------------------------------------------------ LE link fields

    /** Vol 4 Part E, Section 7.7.65.1: Role. */
    fun roleName(role: Int): String = when (role) {
        0x00 -> "Central"
        0x01 -> "Peripheral"
        else -> "Role 0x%02X".format(role)
    }

    /** Vol 4 Part E, Section 7.7.65.1: Peer_Address_Type. */
    fun addressTypeName(type: Int): String = when (type) {
        0x00 -> "Public"
        0x01 -> "Random"
        0x02 -> "Public Identity"
        0x03 -> "Random Identity"
        0xFF -> "Anonymous"
        else -> "Address type 0x%02X".format(type)
    }

    /** Vol 4 Part E, Section 7.7.65.12: TX_PHY / RX_PHY. */
    fun phyName(phy: Int): String = when (phy) {
        0x00 -> "None"
        0x01 -> "LE 1M"
        0x02 -> "LE 2M"
        0x03 -> "LE Coded"
        else -> "PHY 0x%02X".format(phy)
    }

    /** Vol 4 Part E, Section 7.7.65.2: Event_Type of a legacy advertising report. */
    fun advertisingEventTypeName(type: Int): String = when (type) {
        0x00 -> "ADV_IND"
        0x01 -> "ADV_DIRECT_IND"
        0x02 -> "ADV_SCAN_IND"
        0x03 -> "ADV_NONCONN_IND"
        0x04 -> "SCAN_RSP"
        else -> "Event type 0x%02X".format(type)
    }

    /** Vol 4 Part E, Section 7.8.10: LE_Scan_Type. */
    fun scanTypeName(type: Int): String = when (type) {
        0x00 -> "Passive"
        0x01 -> "Active"
        else -> "Scan type 0x%02X".format(type)
    }

    /** Vol 4 Part E, Section 7.8.12: Initiator_Filter_Policy. */
    fun initiatorFilterPolicyName(policy: Int): String = when (policy) {
        0x00 -> "Use peer address"
        0x01 -> "Use Filter Accept List"
        else -> "Policy 0x%02X".format(policy)
    }

    // ------------------------------------------------------------------ L2CAP (Vol 3 Part A)

    const val CID_SIGNALING = 0x0005
    const val CID_ATT = 0x0004
    const val CID_SMP = 0x0006

    const val L2CAP_CONN_PARAM_UPDATE_REQ = 0x12
    const val L2CAP_CONN_PARAM_UPDATE_RSP = 0x13
    const val L2CAP_LE_CREDIT_CONN_REQ = 0x14
    const val L2CAP_LE_CREDIT_CONN_RSP = 0x15
    const val L2CAP_CREDIT_CONN_REQ = 0x17
    const val L2CAP_CREDIT_CONN_RSP = 0x18

    /** Vol 3 Part A, Section 2.1, Table 2.1 (fixed channel identifiers). */
    fun cidName(cid: Int): String = when (cid) {
        0x0001 -> "L2CAP Signalling (BR/EDR)"
        0x0002 -> "Connectionless"
        0x0003 -> "AMP Manager"
        0x0004 -> "ATT"
        0x0005 -> "LE L2CAP Signalling"
        0x0006 -> "Security Manager"
        0x0007 -> "BR/EDR Security Manager"
        0x001F -> "AMP Test Manager"
        else -> if (cid >= 0x0040) "Dynamic channel 0x%04X".format(cid) else "CID 0x%04X".format(cid)
    }

    /** Vol 3 Part A, Section 4, Table 4.1 (signalling command codes). */
    fun l2capSignalName(code: Int): String = when (code) {
        0x01 -> "Command Reject"
        0x02 -> "Connection Request"
        0x03 -> "Connection Response"
        0x04 -> "Configuration Request"
        0x05 -> "Configuration Response"
        0x06 -> "Disconnection Request"
        0x07 -> "Disconnection Response"
        0x08 -> "Echo Request"
        0x09 -> "Echo Response"
        0x0A -> "Information Request"
        0x0B -> "Information Response"
        0x0C -> "Create Channel Request"
        0x0D -> "Create Channel Response"
        0x0E -> "Move Channel Request"
        0x0F -> "Move Channel Response"
        0x10 -> "Move Channel Confirmation Request"
        0x11 -> "Move Channel Confirmation Response"
        0x12 -> "Connection Parameter Update Request"
        0x13 -> "Connection Parameter Update Response"
        0x14 -> "LE Credit Based Connection Request"
        0x15 -> "LE Credit Based Connection Response"
        0x16 -> "LE Flow Control Credit"
        0x17 -> "L2CAP Credit Based Connection Request"
        0x18 -> "L2CAP Credit Based Connection Response"
        0x19 -> "L2CAP Credit Based Reconfigure Request"
        0x1A -> "L2CAP Credit Based Reconfigure Response"
        else -> "L2CAP signal 0x%02X".format(code)
    }

    /** Vol 3 Part A, Section 4.21: Connection Parameter Update Response result. */
    fun connectionParameterResultText(result: Int): String = when (result) {
        0x0000 -> "Accepted"
        0x0001 -> "Rejected"
        else -> "Result 0x%04X".format(result)
    }

    /** Vol 3 Part A, Section 4.23: LE Credit Based Connection Response result. */
    fun leCreditConnectionResultText(result: Int): String = when (result) {
        0x0000 -> "Connection successful"
        0x0002 -> "SPSM not supported"
        0x0004 -> "No resources available"
        0x0005 -> "Insufficient authentication"
        0x0006 -> "Insufficient authorization"
        0x0007 -> "Insufficient encryption key size"
        0x0008 -> "Insufficient encryption"
        0x0009 -> "Invalid Source CID"
        0x000A -> "Source CID already allocated"
        0x000B -> "Unacceptable parameters"
        else -> "Result 0x%04X".format(result)
    }

    /**
     * Vol 3 Part A, Section 4.22 and Assigned Numbers: SPSM 0x001F is Enhanced ATT (EATT), which is
     * the only credit-based channel BLE Studio cares to name.
     */
    const val SPSM_EATT = 0x001F

    fun spsmName(spsm: Int): String = when (spsm) {
        0x0001 -> "IPSP"
        0x001F -> "EATT"
        else -> "SPSM 0x%04X".format(spsm)
    }

    // ------------------------------------------------------------------ SMP (Vol 3 Part H, 3)

    const val SMP_PAIRING_REQUEST = 0x01
    const val SMP_PAIRING_RESPONSE = 0x02
    const val SMP_PAIRING_CONFIRM = 0x03
    const val SMP_PAIRING_RANDOM = 0x04
    const val SMP_PAIRING_FAILED = 0x05
    const val SMP_ENCRYPTION_INFORMATION = 0x06
    const val SMP_CENTRAL_IDENTIFICATION = 0x07
    const val SMP_IDENTITY_INFORMATION = 0x08
    const val SMP_IDENTITY_ADDRESS_INFORMATION = 0x09
    const val SMP_SIGNING_INFORMATION = 0x0A
    const val SMP_SECURITY_REQUEST = 0x0B
    const val SMP_PAIRING_PUBLIC_KEY = 0x0C
    const val SMP_PAIRING_DHKEY_CHECK = 0x0D
    const val SMP_KEYPRESS_NOTIFICATION = 0x0E

    fun smpOpcodeName(code: Int): String = when (code) {
        0x01 -> "Pairing Request"
        0x02 -> "Pairing Response"
        0x03 -> "Pairing Confirm"
        0x04 -> "Pairing Random"
        0x05 -> "Pairing Failed"
        0x06 -> "Encryption Information"
        0x07 -> "Central Identification"
        0x08 -> "Identity Information"
        0x09 -> "Identity Address Information"
        0x0A -> "Signing Information"
        0x0B -> "Security Request"
        0x0C -> "Pairing Public Key"
        0x0D -> "Pairing DHKey Check"
        0x0E -> "Pairing Keypress Notification"
        else -> "SMP opcode 0x%02X".format(code)
    }

    /**
     * True for SMP PDUs whose body is (or is derived from) key material: confirm and random values,
     * the LTK, EDIV/Rand, the IRK, the CSRK, the public key and the DHKey check. BLE Studio counts
     * these and never keeps their bytes.
     */
    fun smpCarriesKeyMaterial(code: Int): Boolean = when (code) {
        SMP_PAIRING_CONFIRM, SMP_PAIRING_RANDOM, SMP_ENCRYPTION_INFORMATION, SMP_CENTRAL_IDENTIFICATION,
        SMP_IDENTITY_INFORMATION, SMP_IDENTITY_ADDRESS_INFORMATION, SMP_SIGNING_INFORMATION,
        SMP_PAIRING_PUBLIC_KEY, SMP_PAIRING_DHKEY_CHECK,
        -> true
        else -> false
    }

    /** Vol 3 Part H, Section 3.3.1, Table 3.4: IO Capability. */
    fun ioCapabilityName(capability: Int): String = when (capability) {
        0x00 -> "DisplayOnly"
        0x01 -> "DisplayYesNo"
        0x02 -> "KeyboardOnly"
        0x03 -> "NoInputNoOutput"
        0x04 -> "KeyboardDisplay"
        else -> "IO capability 0x%02X".format(capability)
    }

    /** Vol 3 Part H, Section 3.3.1, Table 3.5: OOB data flag. */
    fun oobDataFlagName(flag: Int): String = when (flag) {
        0x00 -> "OOB data not present"
        0x01 -> "OOB data present"
        else -> "OOB flag 0x%02X".format(flag)
    }

    /** Vol 3 Part H, Section 3.5.5, Table 3.7: Pairing Failed reason. */
    fun smpFailureReason(reason: Int): String = when (reason) {
        0x01 -> "Passkey Entry Failed"
        0x02 -> "OOB Not Available"
        0x03 -> "Authentication Requirements"
        0x04 -> "Confirm Value Failed"
        0x05 -> "Pairing Not Supported"
        0x06 -> "Encryption Key Size"
        0x07 -> "Command Not Supported"
        0x08 -> "Unspecified Reason"
        0x09 -> "Repeated Attempts"
        0x0A -> "Invalid Parameters"
        0x0B -> "DHKey Check Failed"
        0x0C -> "Numeric Comparison Failed"
        0x0D -> "BR/EDR Pairing In Progress"
        0x0E -> "Cross-transport Key Derivation/Generation Not Allowed"
        0x0F -> "Key Rejected"
        else -> "Reason 0x%02X".format(reason)
    }

    /** Vol 3 Part H, Section 3.5.1, Figure 3.3: AuthReq bit field. */
    const val AUTH_REQ_BONDING_MASK = 0x03
    const val AUTH_REQ_MITM = 0x04
    const val AUTH_REQ_SECURE_CONNECTIONS = 0x08
    const val AUTH_REQ_KEYPRESS = 0x10
    const val AUTH_REQ_CT2 = 0x20

    /** Vol 3 Part H, Section 3.6.1, Figure 3.11: key distribution bit field. */
    const val KEY_DIST_ENC_KEY = 0x01
    const val KEY_DIST_ID_KEY = 0x02
    const val KEY_DIST_SIGN_KEY = 0x04
    const val KEY_DIST_LINK_KEY = 0x08

    fun keyDistributionText(mask: Int): String {
        if (mask == 0) return "none"
        val out = StringBuilder(24)
        if (mask and KEY_DIST_ENC_KEY != 0) out.append("LTK")
        if (mask and KEY_DIST_ID_KEY != 0) out.appendSeparated("IRK")
        if (mask and KEY_DIST_SIGN_KEY != 0) out.appendSeparated("CSRK")
        if (mask and KEY_DIST_LINK_KEY != 0) out.appendSeparated("LinkKey")
        return out.toString()
    }

    fun authReqText(authReq: Int): String {
        val out = StringBuilder(40)
        out.append(if (authReq and AUTH_REQ_BONDING_MASK == 0x01) "bonding" else "no bonding")
        if (authReq and AUTH_REQ_MITM != 0) out.appendSeparated("MITM")
        if (authReq and AUTH_REQ_SECURE_CONNECTIONS != 0) out.appendSeparated("Secure Connections")
        if (authReq and AUTH_REQ_KEYPRESS != 0) out.appendSeparated("keypress")
        if (authReq and AUTH_REQ_CT2 != 0) out.appendSeparated("CT2")
        return out.toString()
    }

    private fun StringBuilder.appendSeparated(text: String) {
        if (isNotEmpty()) append('+')
        append(text)
    }

    /**
     * Association model for a pairing feature exchange, per Vol 3 Part H, Section 2.3.5.1
     * (Tables 2.7 and 2.8). OOB wins when either side has OOB data; when neither side asks for MITM
     * protection the IO capabilities are ignored and Just Works is used; otherwise the IO capability
     * matrix decides, and Numeric Comparison only exists on an LE Secure Connections pairing.
     */
    fun associationModel(
        initiatorIo: Int,
        responderIo: Int,
        initiatorOob: Int,
        responderOob: Int,
        initiatorAuthReq: Int,
        responderAuthReq: Int,
    ): PairingMethod {
        val secureConnections = (initiatorAuthReq and AUTH_REQ_SECURE_CONNECTIONS) != 0 &&
            (responderAuthReq and AUTH_REQ_SECURE_CONNECTIONS) != 0
        // Vol 3 Part H, 2.3.5.1: legacy pairing uses OOB only when both sides have OOB data;
        // Secure Connections uses it when either side does.
        val oob = if (secureConnections) {
            initiatorOob != 0 || responderOob != 0
        } else {
            initiatorOob != 0 && responderOob != 0
        }
        if (oob) return PairingMethod.OUT_OF_BAND
        val mitm = (initiatorAuthReq and AUTH_REQ_MITM) != 0 || (responderAuthReq and AUTH_REQ_MITM) != 0
        if (!mitm) return PairingMethod.JUST_WORKS
        return when (ioMatrix(initiatorIo, responderIo)) {
            IO_JUST_WORKS -> PairingMethod.JUST_WORKS
            IO_PASSKEY -> PairingMethod.PASSKEY_ENTRY
            else -> if (secureConnections) PairingMethod.NUMERIC_COMPARISON else PairingMethod.PASSKEY_ENTRY
        }
    }

    private const val IO_JUST_WORKS = 0
    private const val IO_PASSKEY = 1

    /** Numeric Comparison under Secure Connections, Passkey Entry under legacy pairing. */
    private const val IO_COMPARE_OR_PASSKEY = 2

    /**
     * Tables 2.7 / 2.8, indexed [initiator][responder] over DisplayOnly, DisplayYesNo, KeyboardOnly,
     * NoInputNoOutput, KeyboardDisplay.
     */
    private val IO_MATRIX = arrayOf(
        intArrayOf(IO_JUST_WORKS, IO_JUST_WORKS, IO_PASSKEY, IO_JUST_WORKS, IO_PASSKEY),
        intArrayOf(IO_JUST_WORKS, IO_COMPARE_OR_PASSKEY, IO_PASSKEY, IO_JUST_WORKS, IO_COMPARE_OR_PASSKEY),
        intArrayOf(IO_PASSKEY, IO_PASSKEY, IO_PASSKEY, IO_JUST_WORKS, IO_PASSKEY),
        intArrayOf(IO_JUST_WORKS, IO_JUST_WORKS, IO_JUST_WORKS, IO_JUST_WORKS, IO_JUST_WORKS),
        intArrayOf(IO_PASSKEY, IO_COMPARE_OR_PASSKEY, IO_PASSKEY, IO_JUST_WORKS, IO_COMPARE_OR_PASSKEY),
    )

    private fun ioMatrix(initiator: Int, responder: Int): Int {
        if (initiator !in 0..4 || responder !in 0..4) return IO_JUST_WORKS
        return IO_MATRIX[initiator][responder]
    }

    // ------------------------------------------------------------------ ATT (Vol 3 Part F, 3.4)

    fun attOpcodeName(opcode: Int): String = when (opcode) {
        0x01 -> "Error Response"
        0x02 -> "Exchange MTU Request"
        0x03 -> "Exchange MTU Response"
        0x04 -> "Find Information Request"
        0x05 -> "Find Information Response"
        0x06 -> "Find By Type Value Request"
        0x07 -> "Find By Type Value Response"
        0x08 -> "Read By Type Request"
        0x09 -> "Read By Type Response"
        0x0A -> "Read Request"
        0x0B -> "Read Response"
        0x0C -> "Read Blob Request"
        0x0D -> "Read Blob Response"
        0x0E -> "Read Multiple Request"
        0x0F -> "Read Multiple Response"
        0x10 -> "Read By Group Type Request"
        0x11 -> "Read By Group Type Response"
        0x12 -> "Write Request"
        0x13 -> "Write Response"
        0x16 -> "Prepare Write Request"
        0x17 -> "Prepare Write Response"
        0x18 -> "Execute Write Request"
        0x19 -> "Execute Write Response"
        0x1B -> "Handle Value Notification"
        0x1D -> "Handle Value Indication"
        0x1E -> "Handle Value Confirmation"
        0x20 -> "Read Multiple Variable Request"
        0x21 -> "Read Multiple Variable Response"
        0x23 -> "Multiple Handle Value Notification"
        0x52 -> "Write Command"
        0xD2 -> "Signed Write Command"
        else -> "ATT opcode 0x%02X".format(opcode)
    }

    // ------------------------------------------------------------------ advertising data (CSS Part A, 1)

    const val AD_FLAGS = 0x01
    const val AD_INCOMPLETE_16 = 0x02
    const val AD_COMPLETE_16 = 0x03
    const val AD_INCOMPLETE_32 = 0x04
    const val AD_COMPLETE_32 = 0x05
    const val AD_INCOMPLETE_128 = 0x06
    const val AD_COMPLETE_128 = 0x07
    const val AD_SHORTENED_NAME = 0x08
    const val AD_COMPLETE_NAME = 0x09
    const val AD_TX_POWER = 0x0A
    const val AD_SERVICE_DATA_16 = 0x16
    const val AD_APPEARANCE = 0x19
    const val AD_MANUFACTURER_SPECIFIC = 0xFF

    fun adTypeName(type: Int): String = when (type) {
        0x01 -> "Flags"
        0x02 -> "Incomplete List of 16-bit Service UUIDs"
        0x03 -> "Complete List of 16-bit Service UUIDs"
        0x04 -> "Incomplete List of 32-bit Service UUIDs"
        0x05 -> "Complete List of 32-bit Service UUIDs"
        0x06 -> "Incomplete List of 128-bit Service UUIDs"
        0x07 -> "Complete List of 128-bit Service UUIDs"
        0x08 -> "Shortened Local Name"
        0x09 -> "Complete Local Name"
        0x0A -> "Tx Power Level"
        0x0D -> "Class of Device"
        0x12 -> "Peripheral Connection Interval Range"
        0x14 -> "List of 16-bit Service Solicitation UUIDs"
        0x15 -> "List of 128-bit Service Solicitation UUIDs"
        0x16 -> "Service Data - 16-bit UUID"
        0x19 -> "Appearance"
        0x1A -> "Advertising Interval"
        0x20 -> "Service Data - 32-bit UUID"
        0x21 -> "Service Data - 128-bit UUID"
        0x24 -> "URI"
        0x2D -> "Broadcast Name"
        0xFF -> "Manufacturer Specific Data"
        else -> "AD type 0x%02X".format(type)
    }
}

/** Association models of Vol 3 Part H, Section 2.3.5.1. */
enum class PairingMethod { JUST_WORKS, PASSKEY_ENTRY, NUMERIC_COMPARISON, OUT_OF_BAND }

// ---------------------------------------------------------------------- byte helpers

/** Conn_Interval is counted in 1.25 ms units (Vol 4 Part E, Section 7.7.65.1). */
internal fun connectionIntervalMs(units: Int): Double = units * 1.25

/** Supervision_Timeout is counted in 10 ms units (Vol 4 Part E, Section 7.7.65.1). */
internal fun supervisionTimeoutMs(units: Int): Int = units * 10

/** Locale-independent so a capture reads the same on every phone. */
internal fun formatMillis(value: Double): String =
    if (value == kotlin.math.floor(value)) {
        String.format(Locale.ROOT, "%.0f ms", value)
    } else {
        String.format(Locale.ROOT, "%.2f ms", value)
    }

private val HEX_UPPER = "0123456789ABCDEF".toCharArray()
private val HEX_LOWER = "0123456789abcdef".toCharArray()

/** Byte indexes of the 8-4-4-4-12 groups inside a little-endian 128-bit UUID. */
private val UUID_GROUPS = arrayOf(
    intArrayOf(15, 14, 13, 12),
    intArrayOf(11, 10),
    intArrayOf(9, 8),
    intArrayOf(7, 6),
    intArrayOf(5, 4, 3, 2, 1, 0),
)

internal fun hex(bytes: ByteArray, offset: Int, length: Int): String {
    if (length <= 0) return ""
    val out = CharArray(length * 2)
    var i = 0
    while (i < length) {
        val v = bytes[offset + i].toInt() and 0xFF
        out[i * 2] = HEX_UPPER[v ushr 4]
        out[i * 2 + 1] = HEX_UPPER[v and 0x0F]
        i++
    }
    return String(out)
}

internal fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

internal fun u16le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun u32le(bytes: ByteArray, offset: Int): Long =
    (u16le(bytes, offset).toLong()) or (u16le(bytes, offset + 2).toLong() shl 16)

/** Bluetooth addresses travel least significant byte first (Vol 4 Part E, Section 5.2). */
internal fun bdAddr(bytes: ByteArray, offset: Int): String {
    val out = CharArray(17)
    var pos = 0
    for (i in 5 downTo 0) {
        if (pos != 0) out[pos++] = ':'
        val v = bytes[offset + i].toInt() and 0xFF
        out[pos++] = HEX_UPPER[v ushr 4]
        out[pos++] = HEX_UPPER[v and 0x0F]
    }
    return String(out, 0, pos)
}

internal fun uuid16(value: Int): String = "%08x-0000-1000-8000-00805f9b34fb".format(value and 0xFFFF)

internal fun uuid32(value: Long): String = "%08x-0000-1000-8000-00805f9b34fb".format(value)

/** ATT and AD both carry 128-bit UUIDs least significant byte first. */
internal fun uuid128(bytes: ByteArray, offset: Int): String {
    val out = CharArray(36)
    var pos = 0
    for (group in UUID_GROUPS) {
        if (pos != 0) out[pos++] = '-'
        for (index in group) {
            val v = bytes[offset + index].toInt() and 0xFF
            out[pos++] = HEX_LOWER[v ushr 4]
            out[pos++] = HEX_LOWER[v and 0x0F]
        }
    }
    return String(out, 0, pos)
}

/** GATT attribute type names used by the discovery decoder (Vol 3 Part G, Section 3.4). */
internal fun typeName(type: Int): String = when (type) {
    0x2800 -> "primary service"
    0x2801 -> "secondary service"
    0x2802 -> "include"
    0x2803 -> "characteristic"
    0x2902 -> "client characteristic configuration"
    -1 -> "128-bit type"
    else -> "0x%04X".format(type)
}

/** ATT error codes, Vol 3 Part F, Section 3.4.1.1 and Table 3.4. */
internal fun attError(code: Int): String = when (code) {
    0x01 -> "invalid handle"
    0x02 -> "read not permitted"
    0x03 -> "write not permitted"
    0x04 -> "invalid PDU"
    0x05 -> "insufficient authentication"
    0x06 -> "request not supported"
    0x07 -> "invalid offset"
    0x08 -> "insufficient authorization"
    0x09 -> "prepare queue full"
    0x0A -> "attribute not found"
    0x0B -> "attribute not long"
    0x0C -> "insufficient encryption key size"
    0x0D -> "invalid attribute value length"
    0x0E -> "unlikely error"
    0x0F -> "insufficient encryption"
    0x10 -> "unsupported group type"
    0x11 -> "insufficient resources"
    0x12 -> "database out of sync"
    0x13 -> "value not allowed"
    0xFD -> "improper client characteristic configuration descriptor"
    0xFE -> "procedure already in progress"
    0xFF -> "out of range"
    else -> if (code in 0x80..0x9F) "application error 0x%02X".format(code) else "0x%02X".format(code)
}

/**
 * Walks the AD structures in `[offset, offset + length)` (Core Specification Supplement, Part A,
 * Section 1.1: each structure is length(1), AD type(1), then length-1 data bytes). The callback is
 * inlined, so a scan allocates nothing; a structure that runs past the buffer stops the walk.
 */
internal inline fun forEachAdStructure(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    action: (type: Int, valueOffset: Int, valueLength: Int) -> Unit,
) {
    var cursor = offset
    val end = offset + length
    while (cursor < end) {
        val fieldLength = bytes[cursor].toInt() and 0xFF
        if (fieldLength == 0) return
        if (cursor + 1 + fieldLength > end) return
        action(bytes[cursor + 1].toInt() and 0xFF, cursor + 2, fieldLength - 1)
        cursor += 1 + fieldLength
    }
}
