package dev.nphil.blueshark.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoopEntryRankTest {
    private fun rank(name: String, entryTime: Long = 0L) =
        snoopEntryStampMs(name, entryTime)

    @Test
    fun `a later adapter start outranks an earlier one in the same bugreport`() {
        val older = rank("FS/data/misc/bluetooth/logs/btsnoop_hci_260909_163625.log")
        val newer = rank("FS/data/misc/bluetooth/logs/btsnoop_hci_260909_172025.log")
        assertTrue("$newer should outrank $older", newer > older)
        // Exactly the 44 minutes between the two adapter starts.
        assertEquals(44 * 60 * 1000L, newer - older)
    }

    @Test
    fun `the zip entry time is used when the name carries no stamp`() {
        assertEquals(1_700_000_000_000L, rank("FS/data/misc/bluetooth/logs/btsnoop_hci.log", 1_700_000_000_000L))
    }

    @Test
    fun `a nameless undated entry never outranks a dated one`() {
        assertTrue(rank("FS/data/misc/bluetooth/logs/btsnoop_hci_260909_163625.log") > rank("btsnoop_hci.log", 0L))
    }
}

class BugreportSnoopFileNameTest {
    @Test
    fun `two captures in one bugreport get two files`() {
        val a = bugreportSnoopFileName(1L, "FS/data/misc/bluetooth/logs/btsnoop_hci_260909_163625.log", false)
        val b = bugreportSnoopFileName(1L, "FS/data/misc/bluetooth/logs/btsnoop_hci_260909_172025.log", false)
        assertTrue("$a must differ from $b", a != b)
        assertTrue(a.contains("163625") && b.contains("172025"))
    }

    @Test
    fun `a rotation is distinguishable from the live file of the same name`() {
        val live = bugreportSnoopFileName(1L, "FS/data/misc/bluetooth/logs/btsnoop_hci.log", false)
        val last = bugreportSnoopFileName(1L, "FS/data/misc/bluetooth/logs/btsnoop_hci.log", true)
        assertTrue(live != last)
    }

    @Test
    fun `path separators and spaces never escape the cache directory`() {
        val name = bugreportSnoopFileName(1L, "FS/../../etc/bt snoop.log", false)
        assertTrue(name, !name.contains('/') && !name.contains(' '))
    }
}

/**
 * The zip lists entries in whatever order the OEM wrote them, so recency must be decided after the
 * pass, never by skipping entries during it.
 */
class SnoopCandidateOrderTest {
    private data class Candidate(val entry: String, val rotated: Boolean) {
        val stamp = snoopEntryStampMs(entry, 0L)
    }

    private fun pick(candidates: List<Candidate>): Pair<String, List<String>> {
        val ordered = candidates.sortedWith(compareBy({ it.rotated }, { -it.stamp }))
        return ordered.first().entry to ordered.drop(1).sortedBy { it.stamp }.map { it.entry }
    }

    @Test
    fun `the newest capture wins even when the zip lists it first`() {
        val newestFirst = pick(
            listOf(
                Candidate("logs/btsnoop_hci_260909_172025.log", false),
                Candidate("logs/btsnoop_hci_260909_163625.log", false),
            ),
        )
        val newestLast = pick(
            listOf(
                Candidate("logs/btsnoop_hci_260909_163625.log", false),
                Candidate("logs/btsnoop_hci_260909_172025.log", false),
            ),
        )
        assertEquals("logs/btsnoop_hci_260909_172025.log", newestFirst.first)
        assertEquals(newestFirst, newestLast)
        // The loser is merged, not dropped: it holds the traffic from before the restart.
        assertEquals(listOf("logs/btsnoop_hci_260909_163625.log"), newestFirst.second)
    }

    @Test
    fun `a live file outranks a rotation regardless of stamps`() {
        val (primary, older) = pick(
            listOf(
                Candidate("logs/btsnoop_hci_260909_172025.log.last", true),
                Candidate("logs/btsnoop_hci_260909_163625.log", false),
            ),
        )
        assertEquals("logs/btsnoop_hci_260909_163625.log", primary)
        assertEquals(1, older.size)
    }

    @Test
    fun `older captures are merged oldest first`() {
        val (_, older) = pick(
            listOf(
                Candidate("logs/btsnoop_hci_260909_172025.log", false),
                Candidate("logs/btsnoop_hci_260909_163625.log", false),
                Candidate("logs/btsnoop_hci_260909_150000.log", false),
            ),
        )
        assertEquals(
            listOf("logs/btsnoop_hci_260909_150000.log", "logs/btsnoop_hci_260909_163625.log"),
            older,
        )
    }
}
