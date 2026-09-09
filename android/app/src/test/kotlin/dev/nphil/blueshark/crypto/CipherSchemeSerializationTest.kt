package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.ByteSource
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherByteOrder
import dev.nphil.blueshark.model.EvidenceBundle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session with schemes has to survive the round trip through `sessions/`.
 *
 * [ByteSource] is a sealed hierarchy holding data classes, data objects and nested sources, and
 * `SessionStore` decodes with `ignoreUnknownKeys = false` - so a serialiser that cannot express
 * one variant does not degrade, it makes the whole session unreadable and loses the capture.
 * These use the store's exact `Json` configuration for that reason.
 */
class CipherSchemeSerializationTest {

    /** Byte-for-byte the configuration in `SessionStore`. */
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private fun roundTrip(session: CaptureSession): CaptureSession {
        val text = json.encodeToString(
            EvidenceBundle.serializer(),
            EvidenceBundle(appVersion = "test", session = session),
        )
        return json.decodeFromString(EvidenceBundle.serializer(), text).session
    }

    @Test
    fun `every shipped preset survives the store's json`() {
        val session = CaptureSession(
            name = "all presets",
            ciphers = Presets.all.map { it.template },
        )
        val decoded = roundTrip(session)
        assertEquals(Presets.all.size, decoded.ciphers.size)
        for ((original, restored) in session.ciphers.zip(decoded.ciphers)) {
            assertEquals(original.name, original, restored)
        }
    }

    /** The deepest nesting any preset needs: a composite holding a slice of a data-object source. */
    @Test
    fun `a nested byte source survives`() {
        val scheme = Presets.telinkMesh.template.copy(keyHex = "00112233445566778899AABBCCDDEEFF")
        val decoded = roundTrip(CaptureSession(name = "telink", ciphers = listOf(scheme)))
        val nonce = decoded.ciphers.single().nonce as ByteSource.Composite
        assertEquals(5, nonce.parts.size)
        assertEquals(
            ByteSource.Slice(ByteSource.MacAddressReversed, 0, 4),
            nonce.parts[1],
        )
        assertEquals(CipherByteOrder.REVERSED_BLOCKS, decoded.ciphers.single().byteOrder)
    }

    @Test
    fun `every byte source variant survives`() {
        val sources = listOf(
            ByteSource.Constant("A5"),
            ByteSource.FrameBytes(-7, 3),
            ByteSource.MacAddress,
            ByteSource.MacAddressReversed,
            ByteSource.Counter(2, littleEndian = false),
            ByteSource.Counter(4, littleEndian = null),
            ByteSource.Slice(ByteSource.MacAddress, 2, 2),
            ByteSource.Composite(listOf(ByteSource.Constant("00"), ByteSource.MacAddressReversed)),
        )
        val scheme = Presets.genericAesCcm.template.copy(nonce = ByteSource.Composite(sources))
        val decoded = roundTrip(CaptureSession(name = "all sources", ciphers = listOf(scheme)))
        assertEquals(sources, (decoded.ciphers.single().nonce as ByteSource.Composite).parts)
    }

    /** A bundle written before this feature existed must still load. */
    @Test
    fun `a session written without a ciphers field still decodes`() {
        val legacy = """
            {
              "schemaVersion": 1,
              "appVersion": "0.0.0-dev",
              "session": {
                "id": "legacy",
                "name": "Old capture",
                "createdAtEpochMs": 1700000000000,
                "updatedAtEpochMs": 1700000000000
              }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(EvidenceBundle.serializer(), legacy).session
        assertEquals("Old capture", decoded.name)
        assertTrue(decoded.ciphers.isEmpty())
    }
}
