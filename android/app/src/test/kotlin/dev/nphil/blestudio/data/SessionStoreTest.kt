package dev.nphil.blestudio.data

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Four screens write the same session file. What must hold is that a write never publishes a
 * session that predates another writer's records.
 */
class SessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = SessionStore(folder.newFolder("sessions-${counter++}"))

    private fun event(label: String) = BleEvent(
        id = label,
        timestampEpochMicros = 1_000,
        direction = EventDirection.PHONE_TO_DEVICE,
        source = EventSource.LIVE_GATT,
        operation = AttOperation.WRITE_COMMAND,
        payloadHex = "01",
    )

    @Test
    fun `concurrent updates keep every writer's records`() = runBlocking {
        val store = store()
        val session = store.save(CaptureSession(name = "shared"))
        val writers = 8

        withContext(Dispatchers.IO) {
            (1..writers).map { index ->
                async {
                    store.update(session.id) { current ->
                        // Widens the load-modify-save window: without the store's per-id lock this
                        // is exactly where a second writer reads the pre-append session.
                        Thread.sleep(5)
                        current.copy(events = current.events + event("event-$index"))
                    }
                }
            }.awaitAll()
        }

        val stored = requireNotNull(store.load(session.id))
        assertEquals(writers, stored.events.size)
        assertEquals((1..writers).map { "event-$it" }.toSet(), stored.events.map { it.id }.toSet())
    }

    @Test
    fun `an update sees what another writer appended first`() = runBlocking {
        val store = store()
        val session = store.save(CaptureSession(name = "shared"))
        val stale = session.copy(name = "renamed by a stale editor")

        store.update(session.id) { it.copy(events = it.events + event("from-capture")) }
        // The second writer holds a snapshot from before the append, but only patches its own field.
        val merged = store.update(session.id) { it.copy(name = stale.name) }

        assertEquals("renamed by a stale editor", merged.name)
        assertEquals(listOf("from-capture"), merged.events.map { it.id })
    }

    @Test
    fun `updating a session that is gone is reported, not silently created`() = runBlocking {
        val store = store()
        val session = store.save(CaptureSession(name = "doomed"))
        store.delete(session.id)

        assertThrows(MissingSessionException::class.java) {
            runBlocking { store.update(session.id) { it.copy(name = "back from the dead") } }
        }
        assertEquals(emptyList<CaptureSession>(), store.list())
    }

    private companion object {
        var counter = 0
    }
}
