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
