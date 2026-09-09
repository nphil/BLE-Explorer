package dev.nphil.blestudio.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The advertising budget is unforgiving: one byte over and the stack answers
 * ADVERTISE_FAILED_DATA_TOO_LARGE with no hint about which field was to blame. These tests pin the
 * arithmetic and the placement decisions the relay makes before it ever touches the adapter.
 */
class AdvertisePlannerTest {

    private val heartRate = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
    private val batteryService = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    private val thirtyTwoBit = UUID.fromString("1234ABCD-0000-1000-8000-00805F9B34FB")
    private val nordicUart = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val vendorSecond = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FA")

    private fun assigned(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

    @Test
    fun `uuid widths follow the base uuid rules`() {
        assertEquals(2, AdvertisePlanner.uuidWidthBytes(heartRate))
        assertEquals(4, AdvertisePlanner.uuidWidthBytes(thirtyTwoBit))
        assertEquals(16, AdvertisePlanner.uuidWidthBytes(nordicUart))
        // Same short form, one byte off the base suffix: not an assigned 16-bit UUID.
        assertEquals(16, AdvertisePlanner.uuidWidthBytes(vendorSecond))
    }

    @Test
    fun `each uuid width gets its own ad structure`() {
        assertEquals(0, AdvertisePlanner.serviceUuidFieldBytes(emptyList()))
        assertEquals(2 + 2, AdvertisePlanner.serviceUuidFieldBytes(listOf(heartRate)))
        assertEquals(2 + 4, AdvertisePlanner.serviceUuidFieldBytes(listOf(heartRate, batteryService)))
        assertEquals(2 + 16, AdvertisePlanner.serviceUuidFieldBytes(listOf(nordicUart)))
        // 16-bit and 128-bit travel in two structures, so the 2-byte overhead is paid twice.
        assertEquals((2 + 2) + (2 + 16), AdvertisePlanner.serviceUuidFieldBytes(listOf(heartRate, nordicUart)))
        assertEquals(
            (2 + 2) + (2 + 4) + (2 + 16),
            AdvertisePlanner.serviceUuidFieldBytes(listOf(heartRate, thirtyTwoBit, nordicUart)),
        )
    }

    @Test
    fun `local name field costs two bytes plus its utf8 bytes`() {
        assertEquals(0, AdvertisePlanner.localNameFieldBytes(null))
        assertEquals(0, AdvertisePlanner.localNameFieldBytes(""))
        assertEquals(2 + 6, AdvertisePlanner.localNameFieldBytes("BedJet"))
        // Two UTF-8 bytes per degree sign, not two chars.
        assertEquals(2 + 4, AdvertisePlanner.localNameFieldBytes("A\u00B0B"))
    }

    @Test
    fun `short name and 16 bit uuid both fit the advertisement`() {
        val planned = AdvertisePlanner.plan("BedJet", listOf(heartRate)) as AdvertisePlanResult.Planned
        val plan = planned.plan
        assertTrue(plan.includeNameInAdvertisement)
        assertFalse(plan.includeNameInScanResponse)
        assertEquals(listOf(heartRate), plan.advertisementServiceUuids)
        assertEquals(3 + (2 + 6) + (2 + 2), plan.advertisementBytes)
        assertEquals(0, plan.scanResponseBytes)
        assertFalse(plan.usesScanResponse)
    }

    @Test
    fun `service uuids keep the advertisement and the long name moves to the scan response`() {
        val name = "Vendor Gadget Model 9000"
        val planned = AdvertisePlanner.plan(name, listOf(nordicUart)) as AdvertisePlanResult.Planned
        val plan = planned.plan
        assertEquals(listOf(nordicUart), plan.advertisementServiceUuids)
        assertEquals(3 + (2 + 16), plan.advertisementBytes)
        assertFalse(plan.includeNameInAdvertisement)
        assertTrue(plan.includeNameInScanResponse)
        assertEquals(2 + name.length, plan.scanResponseBytes)
        assertTrue(plan.notes.isNotEmpty())
    }

    @Test
    fun `uuids that overflow the advertisement move to the scan response`() {
        // (2 + 16) + (2 + 5*2) = 30 bytes: too much for the 28 left beside the flags, fine in a
        // scan response, which carries no flags.
        val uuids = listOf(nordicUart, heartRate, batteryService, assigned("1809"), assigned("1810"), assigned("181A"))
        val planned = AdvertisePlanner.plan(null, uuids) as AdvertisePlanResult.Planned
        val plan = planned.plan
        assertTrue(plan.advertisementServiceUuids.isEmpty())
        assertEquals(uuids, plan.scanResponseServiceUuids)
        assertEquals(3, plan.advertisementBytes)
        assertEquals(30, plan.scanResponseBytes)
        assertTrue(plan.notes.single().contains("passive scanners"))
    }

    @Test
    fun `two 128 bit uuids are refused instead of truncated`() {
        val result = AdvertisePlanner.plan("X", listOf(nordicUart, vendorSecond))
        val rejected = result as AdvertisePlanResult.Rejected
        assertTrue(rejected.reason.contains("34 bytes"))
        assertTrue(rejected.reason.contains("at most 31"))
        assertTrue(rejected.reason.contains("128-bit"))
    }

    @Test
    fun `a name that fits nowhere is refused with the exact allowance`() {
        val result = AdvertisePlanner.plan("A".repeat(30), emptyList())
        val rejected = result as AdvertisePlanResult.Rejected
        assertTrue(rejected.reason.contains("32 bytes"))
        assertTrue(rejected.reason.contains("at most 29 characters"))
    }

    @Test
    fun `no name and no services still yields a plan for the flags only`() {
        val planned = AdvertisePlanner.plan(null, emptyList()) as AdvertisePlanResult.Planned
        assertEquals(3, planned.plan.advertisementBytes)
        assertFalse(planned.plan.usesScanResponse)
    }
}
