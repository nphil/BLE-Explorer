package dev.nphil.blueshark.hci

import dev.nphil.blueshark.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoopModeClassificationTest {

    private fun ok(stdout: String = "") = ShellResult(exitCode = 0, stdout = stdout, stderr = "", timedOut = false)

    @Test
    fun `selinux refusal is reported as a policy denial pointing at developer options`() {
        // Exactly what init prints back through setprop on a user build for bluetooth_prop.
        val refused = ShellResult(
            exitCode = 1,
            stdout = "",
            stderr = "Failed to set property 'persist.bluetooth.btsnooplogmode' to 'full'.\nSee dmesg for error reason.",
            timedOut = false,
        )
        val result = classifySnoopWrite(SnoopMode.FULL, refused, ok("disabled"))

        assertFalse(result.applied)
        assertTrue(result.deniedByPolicy)
        assertEquals("disabled", result.observed)
        assertTrue(result.detail, result.detail.contains("Developer options"))
        assertTrue(result.detail, result.detail.contains("Failed to set property"))
    }

    @Test
    fun `successful write is applied and not a denial`() {
        val result = classifySnoopWrite(SnoopMode.FULL, ok(), ok("full"))

        assertTrue(result.applied)
        assertFalse(result.deniedByPolicy)
        assertEquals("persist.bluetooth.btsnooplogmode = full", result.detail)
    }

    @Test
    fun `accepted write that does not stick is neither applied nor a denial`() {
        val result = classifySnoopWrite(SnoopMode.FULL, ok(), ok("filtered"))

        assertFalse(result.applied)
        assertFalse(result.deniedByPolicy)
        assertTrue(result.detail.contains("still reads \"filtered\""))
    }
}
