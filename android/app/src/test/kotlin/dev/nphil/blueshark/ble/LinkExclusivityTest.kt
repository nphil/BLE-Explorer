package dev.nphil.blueshark.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The promise a Command Prober verdict rests on: while a sweep runs, nothing else writes.
 *
 * Both failure directions are load-bearing and neither is observable from a screen. Letting a
 * second claim through corrupts evidence - the device's answer to somebody else's write is
 * offered to the step in flight, turning a nonexistent opcode into an accepted one. Letting a
 * claim wedge is worse in a different way: it blocks every write in the app for the rest of the
 * process, with nothing an operator can do about it.
 */
class LinkExclusivityTest {

    @Test
    fun `only one holder at a time, and the holder is named`() {
        val links = LinkExclusivity()

        val sweep = links.claim("A Command Prober sweep")

        assertNotNull(sweep)
        assertEquals("A Command Prober sweep", links.holder.value)
        assertNull("a second claim must be refused, not queued", links.claim("A command replay"))
        assertEquals("the refused claim must not rename the holder", "A Command Prober sweep", links.holder.value)
    }

    @Test
    fun `releasing frees the link for the next holder`() {
        val links = LinkExclusivity()
        val sweep = links.claim("A Command Prober sweep")

        links.release(sweep)

        assertNull(links.holder.value)
        assertNotNull(links.claim("A command replay"))
        assertEquals("A command replay", links.holder.value)
    }

    /**
     * A claim that was refused holds nothing, so releasing it must not free the link. Without
     * this, a screen that failed to claim and then ran its own cleanup would hand the link away
     * from underneath the sweep that owns it.
     */
    @Test
    fun `a would-be holder cannot release the claim in force`() {
        val links = LinkExclusivity()
        val sweep = links.claim("A Command Prober sweep")
        val refused = links.claim("A command replay")

        links.release(refused)

        assertEquals("A Command Prober sweep", links.holder.value)
        assertNull(links.claim("A command replay"))
        links.release(sweep)
        assertNull(links.holder.value)
    }

    /** Cleanup runs more than once - on completion and again on teardown - so it has to be safe. */
    @Test
    fun `releasing twice, and releasing nothing, are both harmless`() {
        val links = LinkExclusivity()
        val sweep = links.claim("A Command Prober sweep")
        links.release(sweep)

        links.release(sweep)
        links.release(null)

        assertNull(links.holder.value)
        assertNotNull("the link must still be claimable", links.claim("A command replay"))
    }

    /**
     * A stale claim must not free the claim that replaced it: the sweep's release firing late
     * would otherwise unlock a replay that had already legitimately taken the link.
     */
    @Test
    fun `a stale claim cannot release its successor`() {
        val links = LinkExclusivity()
        val first = links.claim("A Command Prober sweep")
        links.release(first)
        val second = links.claim("A command replay")

        links.release(first)

        assertEquals("A command replay", links.holder.value)
        links.release(second)
        assertNull(links.holder.value)
    }
}
