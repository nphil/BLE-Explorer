package dev.nphil.blueshark.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellResultTest {
    @Test
    fun `long stderr keeps both the head and the tail`() {
        val head = "HEAD-CAUSE " + "a".repeat(1_500)
        val tail = "b".repeat(1_500) + " TAIL-SUMMARY"
        val r = ShellResult(exitCode = 3, stdout = "", stderr = "$head\n$tail", timedOut = false)
        val f = r.failure
        assertTrue(f.startsWith("exit 3: HEAD-CAUSE"))
        assertTrue(f.endsWith("TAIL-SUMMARY"))
        assertTrue(f.contains("chars elided"))
        assertTrue(f.length < 2_200)
    }

    @Test
    fun `short stderr is verbatim with the exit code`() {
        val r = ShellResult(exitCode = 1, stdout = "", stderr = "Permission denied\n", timedOut = false)
        assertEquals("exit 1: Permission denied", r.failure)
    }
}
