package dev.nphil.blueshark.guide

import dev.nphil.blueshark.model.ControlRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Naming is the whole value of the guided take-over: a marker that says "btn power" is evidence,
 * one that says "AppCompatImageButton#3" is noise. Each case below is a layout shape real vendor
 * apps ship.
 */
class ControlLabelerTest {

    private val labeler = ControlLabeler()

    @Test
    fun `own text wins over every other source`() {
        val node = NodeSnapshot(
            text = "Power",
            contentDescription = "toggle the lamp",
            viewId = "com.vendor.app:id/btn_power",
            className = "android.widget.Button",
            clickable = true,
            children = listOf(NodeSnapshot(text = "ignored caption")),
        )

        assertEquals("Power", labeler.label(node))
    }

    @Test
    fun `an icon button falls back to its content description`() {
        val node = NodeSnapshot(
            contentDescription = "Night mode",
            viewId = "com.vendor.app:id/ib_night",
            className = "android.widget.ImageButton",
            clickable = true,
        )

        assertEquals("Night mode", labeler.label(node))
    }

    @Test
    fun `a clickable container borrows the text of its labelled descendant`() {
        val tile = NodeSnapshot(
            className = "android.widget.FrameLayout",
            clickable = true,
            children = listOf(
                NodeSnapshot(className = "android.widget.ImageView"),
                NodeSnapshot(
                    className = "android.widget.LinearLayout",
                    children = listOf(NodeSnapshot(text = "Fan speed", className = "android.widget.TextView")),
                ),
            ),
        )

        assertEquals("Fan speed", labeler.label(tile))
    }

    @Test
    fun `a slider borrows the label sitting next to it`() {
        val row = NodeSnapshot(
            className = "android.widget.LinearLayout",
            children = listOf(
                NodeSnapshot(text = "Brightness", className = "android.widget.TextView"),
                NodeSnapshot(
                    viewId = "com.vendor.app:id/seek_brightness",
                    className = "android.widget.SeekBar",
                    rangeValue = 42f,
                ),
            ),
        )
        val slider = row.children[1]

        assertEquals("Brightness", labeler.label(slider))
    }

    @Test
    fun `a switch two levels down borrows the group description`() {
        val group = NodeSnapshot(
            contentDescription = "Child lock",
            className = "android.widget.LinearLayout",
            children = listOf(
                NodeSnapshot(
                    className = "android.widget.FrameLayout",
                    children = listOf(NodeSnapshot(className = "android.widget.Switch", checkable = true)),
                ),
            ),
        )
        val switch = group.children[0].children[0]

        assertEquals("Child lock", labeler.label(switch))
    }

    @Test
    fun `an unlabelled control humanises its resource id`() {
        val node = NodeSnapshot(
            viewId = "com.vendor.app:id/btn_power",
            className = "android.widget.Button",
            clickable = true,
        )

        assertEquals("btn power", labeler.label(node))
    }

    @Test
    fun `a control with nothing at all is named by class and position`() {
        val row = NodeSnapshot(
            className = "android.widget.LinearLayout",
            children = listOf(
                NodeSnapshot(className = "android.widget.ImageView"),
                NodeSnapshot(className = "android.view.View", clickable = true),
            ),
        )

        assertEquals("View#1", labeler.label(row.children[1]))
    }

    @Test
    fun `identity comes from the resource id when the app ships one`() {
        val key = labeler.key(
            ControlRef(
                packageName = "com.vendor.app",
                screen = "MainActivity",
                viewId = "com.vendor.app:id/btn_power",
                className = "android.widget.Button",
                bounds = listOf(10, 20, 100, 60),
            ),
        )

        assertEquals("btn_power", key)
    }

    @Test
    fun `without a resource id identity tolerates layout jitter but not a different control`() {
        val base = ControlRef(
            packageName = "com.vendor.app",
            screen = "MainActivity",
            className = "android.view.View",
            contentDescription = "Preset 1",
            bounds = listOf(16, 200, 96, 280),
        )

        assertEquals(labeler.key(base), labeler.key(base.copy(bounds = listOf(18, 203, 98, 283))))
        assertNotEquals(labeler.key(base), labeler.key(base.copy(bounds = listOf(160, 200, 240, 280))))
        assertNotEquals(labeler.key(base), labeler.key(base.copy(contentDescription = "Preset 2")))
    }

    @Test
    fun `a recorded control still describes itself without its tree`() {
        val control = ControlRef(
            packageName = "com.vendor.app",
            viewId = "com.vendor.app:id/switch_child_lock",
            className = "android.widget.Switch",
        )

        assertEquals("switch child lock", labeler.describe(control))
        assertEquals("Timer", labeler.describe(control.copy(text = " Timer ")))
    }
}
