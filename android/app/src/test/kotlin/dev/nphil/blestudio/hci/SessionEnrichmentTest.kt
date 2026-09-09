package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.ConnectAttempt
import dev.nphil.blestudio.model.ConnectionFacts
import dev.nphil.blestudio.model.DeviceIdentity
import dev.nphil.blestudio.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding a capture's facts into a session, and refusing to fold in what it did not prove. */
class SessionEnrichmentTest {

    private val peer = "AA:BB:CC:DD:EE:FF"
    private val other = "11:22:33:44:55:66"

    private fun session(
        device: DeviceIdentity = DeviceIdentity(),
        connection: ConnectionFacts = ConnectionFacts(),
    ) = CaptureSession(name = "test", device = device, connection = connection)

    private fun summary(
        connection: ConnectionSummary = ConnectionSummary(address = peer),
        advertiser: AdvertiserSummary? = null,
        extraPeer: ConnectionSummary? = null,
    ): ParseSummary {
        val connections = LinkedHashMap<String, ConnectionSummary>()
        connections[connection.address] = connection
        extraPeer?.let { connections[it.address] = it }
        return ParseSummary(
            connectionsByPeer = connections,
            advertisers = advertiser?.let { mapOf(it.address to it) } ?: emptyMap(),
        )
    }

    @Test
    fun `the only connected peer is chosen and fills the device identity`() {
        val enriched = enrich(
            session(),
            summary(
                connection = ConnectionSummary(address = peer, addressType = "Random", mtu = 185),
                advertiser = AdvertiserSummary(
                    address = peer,
                    addressType = "Random",
                    count = 4,
                    lastRssi = -62,
                    names = setOf("Gadget"),
                    serviceUuids = setOf("0000fa00-0000-1000-8000-00805f9b34fb"),
                    manufacturerIds = setOf(0x004C),
                    manufacturerData = mapOf(0x004C to "0215"),
                ),
            ),
        )

        assertEquals(peer, enriched.device.address)
        assertEquals("Random", enriched.device.addressType)
        assertEquals("Gadget", enriched.device.name)
        assertEquals(listOf("0000fa00-0000-1000-8000-00805f9b34fb"), enriched.device.advertisedServiceUuids)
        assertEquals(mapOf(0x004C to "0215"), enriched.device.manufacturerData)
        assertEquals(185, enriched.connection.negotiatedMtu)
    }

    @Test
    fun `an MTU the session already knows is not overwritten`() {
        val enriched = enrich(
            session(connection = ConnectionFacts(negotiatedMtu = 517)),
            summary(ConnectionSummary(address = peer, mtu = 185)),
        )

        assertEquals(517, enriched.connection.negotiatedMtu)
    }

    @Test
    fun `a name the operator already recorded wins over the advertised one`() {
        val enriched = enrich(
            session(device = DeviceIdentity(name = "Kitchen light")),
            summary(advertiser = AdvertiserSummary(address = peer, names = setOf("Gadget"))),
        )

        assertEquals("Kitchen light", enriched.device.name)
    }

    @Test
    fun `pairing is only ever raised to true`() {
        val paired = enrich(session(), summary(ConnectionSummary(address = peer, pairingSeen = true)))
        assertEquals(true, paired.connection.pairingRequired)
        assertEquals(true, paired.protocol.requiresPairing)

        val unproven = enrich(session(), summary(ConnectionSummary(address = peer, pairingSeen = false)))
        assertNull("no SMP traffic proves nothing either way", unproven.connection.pairingRequired)
        assertNull(unproven.protocol.requiresPairing)

        val alreadyKnown = enrich(
            session(connection = ConnectionFacts(pairingRequired = true)),
            summary(ConnectionSummary(address = peer, pairingSeen = false)),
        )
        assertEquals(true, alreadyKnown.connection.pairingRequired)
    }

    @Test
    fun `connect attempts arrive once, however often the enrichment runs`() {
        val facts = summary(
            ConnectionSummary(
                address = peer,
                attempts = listOf(
                    ConnectionAttemptFact(startedEpochMicros = 5_000_000, durationMicros = 250_000, success = true),
                    ConnectionAttemptFact(
                        startedEpochMicros = 9_000_000,
                        durationMicros = -1,
                        success = false,
                        error = "Connection Timeout",
                    ),
                ),
            ),
        )

        val once = enrich(session(), facts)
        val twice = enrich(once, facts)

        assertEquals(2, twice.connection.connectAttempts.size)
        val first = twice.connection.connectAttempts.first()
        assertEquals(5_000L, first.startedEpochMs)
        assertEquals(250L, first.durationMs)
        assertEquals(EventSource.HCI_SNOOP, first.source)
        val second = twice.connection.connectAttempts.last()
        assertEquals(0L, second.durationMs)
        assertEquals("Connection Timeout", second.error)
    }

    @Test
    fun `an attempt the session already holds is kept`() {
        val existing = ConnectAttempt(
            startedEpochMs = 1_000,
            durationMs = 40,
            success = true,
            source = EventSource.LIVE_GATT,
        )

        val enriched = enrich(
            session(connection = ConnectionFacts(connectAttempts = listOf(existing))),
            summary(ConnectionSummary(address = peer)),
        )

        assertEquals(listOf(existing), enriched.connection.connectAttempts)
    }

    // ------------------------------------------------------------------ idle disconnect evidence

    @Test
    fun `three agreeing idle gaps set the idle disconnect timeout`() {
        val enriched = enrich(
            session(),
            summary(ConnectionSummary(address = peer, idleDisconnectSamplesMs = listOf(10_400, 10_000, 11_000))),
        )

        assertEquals(10_000L, enriched.connection.idleDisconnectMs)
        assertEquals("", enriched.connection.notes)
    }

    @Test
    fun `two gaps are not enough`() {
        val enriched = enrich(
            session(),
            summary(ConnectionSummary(address = peer, idleDisconnectSamplesMs = listOf(10_000, 10_100))),
        )

        assertNull(enriched.connection.idleDisconnectMs)
        assertTrue(enriched.connection.notes.contains("HCI idle gaps before disconnect: 10000, 10100 ms"))
    }

    @Test
    fun `gaps that disagree stay evidence instead of becoming a fact`() {
        val enriched = enrich(
            session(),
            summary(ConnectionSummary(address = peer, idleDisconnectSamplesMs = listOf(10_000, 20_000, 45_000))),
        )

        assertNull(enriched.connection.idleDisconnectMs)
        assertTrue(enriched.connection.notes.contains("10000, 20000, 45000 ms"))
        assertEquals(enriched.connection.notes, enrich(enriched, summary(ConnectionSummary(address = peer, idleDisconnectSamplesMs = listOf(10_000, 20_000, 45_000)))).connection.notes)
    }

    // ------------------------------------------------------------------ choosing the peer

    @Test
    fun `more than one connected peer waits for the operator to pick`() {
        val facts = summary(
            connection = ConnectionSummary(address = peer, mtu = 185),
            extraPeer = ConnectionSummary(address = other, mtu = 23),
        )

        val untouched = enrich(session(), facts)
        assertEquals("", untouched.device.address)
        assertNull(untouched.connection.negotiatedMtu)

        val chosen = enrich(session(), facts, other)
        assertEquals(other, chosen.device.address)
        assertEquals(23, chosen.connection.negotiatedMtu)
    }

    @Test
    fun `an address the capture never saw changes nothing`() {
        val enriched = enrich(session(), summary(ConnectionSummary(address = peer)), "99:99:99:99:99:99")

        assertEquals("", enriched.device.address)
    }
}
