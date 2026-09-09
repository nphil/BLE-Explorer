package dev.nphil.blueshark.ui

import dev.nphil.blueshark.hci.ConnectionSummary
import dev.nphil.blueshark.hci.PairingMethod
import dev.nphil.blueshark.ui.capture.peerDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collection report's peer line. A capture that only proved a couple of things must not show
 * empty separators or the word "null" where a fact is missing.
 */
class CaptureReportTest {

    @Test
    fun `every proven fact appears once, in order`() {
        val detail = peerDetail(
            ConnectionSummary(
                address = "AA:BB:CC:DD:EE:FF",
                addressType = "Random",
                handles = listOf(0x0040, 0x0041),
                intervalMs = 48.75,
                supervisionTimeoutMs = 5_000,
                mtu = 185,
                encrypted = true,
                pairingMethod = PairingMethod.NUMERIC_COMPARISON,
                bonded = true,
                creditBasedChannels = true,
                disconnectReasons = listOf("Connection Timeout"),
            ),
        )

        assertEquals(
            "random · interval 48.75 ms · timeout 5000 ms · MTU 185 · encrypted · numeric comparison · " +
                "bonding · credit-based channel · handles 0x0040,0x0041 · Connection Timeout",
            detail,
        )
    }

    @Test
    fun `a capture that proved almost nothing says almost nothing`() {
        assertEquals("", peerDetail(ConnectionSummary(address = "AA:BB:CC:DD:EE:FF")))
        assertEquals(
            "MTU 23",
            peerDetail(ConnectionSummary(address = "AA:BB:CC:DD:EE:FF", mtu = 23)),
        )
    }
}
