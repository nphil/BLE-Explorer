package dev.nphil.blueshark.guide

import dev.nphil.blueshark.model.ControlRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checklist the operator works through. Its only promise: a control the operator has driven
 * disappears from "still unmapped", on every screen, and nothing else does.
 */
class ControlInventoryTest {

    private val labeler = ControlLabeler()

    private fun control(id: String, screen: String, bounds: List<Int> = listOf(0, 0, 40, 40)) = ControlRef(
        packageName = PACKAGE,
        screen = screen,
        viewId = "$PACKAGE:id/$id",
        className = "android.widget.Button",
        bounds = bounds,
    )

    @Test
    fun `each screen keeps its own checklist and touches cross the session`() {
        val inventory = ControlInventory(labeler)
        val power = control("btn_power", HOME)
        val light = control("btn_light", HOME)
        val start = control("btn_start", TIMER)
        val cancel = control("btn_cancel", TIMER)

        inventory.observe(HOME, listOf(power, light))
        inventory.observe(TIMER, listOf(start, cancel))

        var progress = inventory.progress()
        assertEquals(4, progress.total)
        assertEquals(0, progress.touchedCount)
        assertEquals(listOf(HOME, TIMER), progress.screens.map { it.screen })

        assertTrue(inventory.touch(power))
        assertTrue(inventory.touch(start))
        // The same tap delivered twice must not count as a second mapped control.
        assertFalse(inventory.touch(power))

        progress = inventory.progress()
        assertEquals(2, progress.touchedCount)
        assertEquals(listOf("btn_light"), progress.untouched(HOME).map { labeler.key(it) })
        assertEquals(listOf("btn_cancel"), progress.untouched(TIMER).map { labeler.key(it) })
        assertEquals(setOf("btn_power"), progress.coverage(HOME)?.touched)
        assertEquals(1, progress.coverage(TIMER)?.mapped)
        assertTrue(inventory.isTouched(power))
        assertFalse(inventory.isTouched(light))
    }

    @Test
    fun `a screen rebuilt from the same tree reports no change`() {
        val inventory = ControlInventory(labeler)
        val controls = listOf(control("btn_power", HOME), control("btn_light", HOME))

        assertTrue(inventory.observe(HOME, controls))
        assertFalse(inventory.observe(HOME, controls))
        assertTrue(inventory.observe(HOME, controls + control("btn_timer", HOME)))
    }

    @Test
    fun `a control that moved keeps its mapped state, a new control does not`() {
        val inventory = ControlInventory(labeler)
        val power = control("btn_power", HOME, bounds = listOf(0, 0, 40, 40))
        inventory.observe(HOME, listOf(power))
        inventory.touch(power)

        inventory.observe(HOME, listOf(power.copy(bounds = listOf(0, 120, 40, 160)), control("btn_new", HOME)))

        val progress = inventory.progress()
        assertEquals(2, progress.total)
        assertEquals(1, progress.touchedCount)
        assertEquals(listOf("btn_new"), progress.untouched(HOME).map { labeler.key(it) })
    }

    @Test
    fun `pointing the guide at another app starts an empty checklist`() {
        val inventory = ControlInventory(labeler)
        inventory.observe(HOME, listOf(control("btn_power", HOME)))
        inventory.touch(control("btn_power", HOME))

        inventory.clear()

        assertEquals(GuideProgress(), inventory.progress())
        assertEquals(0, inventory.progress().total)
    }

    @Test
    fun `only reachable actionable nodes make it into the inventory`() {
        val root = NodeSnapshot(
            className = "android.widget.FrameLayout",
            bounds = listOf(0, 0, 1080, 1920),
            children = listOf(
                NodeSnapshot(
                    text = "Power",
                    viewId = "$PACKAGE:id/btn_power",
                    className = "android.widget.Button",
                    bounds = listOf(0, 0, 200, 80),
                    clickable = true,
                ),
                // A label, not a control.
                NodeSnapshot(text = "Brightness", className = "android.widget.TextView", bounds = listOf(0, 90, 200, 130)),
                // Off-screen: collapsed to a zero-area rectangle.
                NodeSnapshot(
                    viewId = "$PACKAGE:id/btn_hidden",
                    className = "android.widget.Button",
                    bounds = listOf(0, 0, 0, 0),
                    clickable = true,
                ),
                // Present in the tree but not shown to the user.
                NodeSnapshot(
                    viewId = "$PACKAGE:id/btn_invisible",
                    className = "android.widget.Button",
                    bounds = listOf(0, 140, 200, 220),
                    clickable = true,
                    visible = false,
                ),
                NodeSnapshot(
                    viewId = "$PACKAGE:id/seek_brightness",
                    className = "android.widget.SeekBar",
                    bounds = listOf(0, 230, 400, 300),
                    rangeValue = 42f,
                ),
                NodeSnapshot(
                    viewId = "$PACKAGE:id/switch_lock",
                    className = "android.widget.Switch",
                    bounds = listOf(0, 310, 200, 380),
                    checkable = true,
                ),
            ),
        )

        val controls = actionableControls(root, PACKAGE, HOME, labeler)

        assertEquals(listOf("btn_power", "seek_brightness", "switch_lock"), controls.map { labeler.key(it) })
        // The inventory records geometry and identity, never a live slider reading.
        assertEquals(listOf(0, 230, 400, 300), controls[1].bounds)
        assertEquals(null, controls[1].rangeValue)
        assertEquals(HOME, controls[0].screen)
        assertEquals(PACKAGE, controls[0].packageName)
    }

    private companion object {
        const val PACKAGE = "com.vendor.app"
        const val HOME = "MainActivity"
        const val TIMER = "TimerActivity"
    }
}
