package dev.nphil.blueshark.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The marker label is a wire format, not decoration: the learn/ slice parses these suffixes back
 * into control state and turns them into command names, so every shape below is a contract.
 */
class MarkerLabelTest {

    private fun field(text: String) = NodeSnapshot(
        text = text,
        className = "android.widget.EditText",
        clickable = true,
        editable = true,
    )

    @Test
    fun `a switch reports the state it landed in`() {
        assertEquals("Power switch -> on", markerLabel("Power switch", ControlOutcome(checked = true)))
        assertEquals("Power switch -> off", markerLabel("Power switch", ControlOutcome(checked = false)))
    }

    @Test
    fun `a slider reports a percentage and keeps the raw reading`() {
        val outcome = outcomeOf(
            NodeSnapshot(className = "android.widget.SeekBar", rangeValue = 155f, rangeMin = 0f, rangeMax = 255f),
        )

        assertEquals("Brightness 61%", markerLabel("Brightness", outcome))
        // A codec needs the device's own units, so the reading survives beside the percentage.
        assertEquals(155f, outcome.rangeValue)
    }

    @Test
    fun `a percentage is taken across the control's own span`() {
        assertEquals(50, rangePercent(current = 30f, min = 20f, max = 40f))
        assertEquals(0, rangePercent(current = 20f, min = 20f, max = 40f))
        assertEquals(100, rangePercent(current = 40f, min = 20f, max = 40f))
        // A reading outside the span the node reported is clamped, never wrapped.
        assertEquals(100, rangePercent(current = 99f, min = 20f, max = 40f))
        // A span of nothing has no percentage; the plain label is emitted instead.
        assertNull(rangePercent(current = 1f, min = 1f, max = 1f))
        assertNull(rangePercent(current = 1f, min = 0f, max = Float.NaN))
        assertNull(rangePercent(current = null, min = 0f, max = 1f))
    }

    @Test
    fun `a field's value is quoted, flattened to one line and clipped`() {
        assertEquals("Name: 'Kitchen lamp'", markerLabel("Name", outcomeOf(field("Kitchen lamp"))))
        // A newline would break a one-line marker.
        assertEquals("Name: 'Kitchen lamp'", markerLabel("Name", outcomeOf(field("Kitchen\n   lamp"))))
        assertEquals(
            "Name: 'Kitchen lamp on the left\u2026'",
            markerLabel("Name", outcomeOf(field("Kitchen lamp on the left shelf"))),
        )
        // The operator's own apostrophe must not close the quote a parser is looking for.
        assertEquals("Name: 'Nate\u2019s lamp'", markerLabel("Name", outcomeOf(field("Nate's lamp"))))
    }

    @Test
    fun `a caption is a label, not a value`() {
        val button = NodeSnapshot(text = "Send", className = "android.widget.Button", clickable = true)

        assertEquals("Send", markerLabel("Send", outcomeOf(button)))
    }

    @Test
    fun `checked beats range beats text`() {
        val everything = ControlOutcome(checked = false, rangeValue = 5f, rangePercent = 50, text = "typed")

        assertEquals("Mode -> off", markerLabel("Mode", everything))
        assertEquals("Mode 50%", markerLabel("Mode", everything.copy(checked = null)))
        assertEquals("Mode: 'typed'", markerLabel("Mode", everything.copy(checked = null, rangePercent = null)))
        assertEquals("Mode", markerLabel("Mode", ControlOutcome()))
    }

    @Test
    fun `state is only read off a node that models it`() {
        assertEquals(true, outcomeOf(NodeSnapshot(checkable = true, checked = true)).checked)
        assertEquals(false, outcomeOf(NodeSnapshot(checkable = true, checked = false)).checked)
        // isChecked on a node that is not checkable is noise; a false there does not mean "off".
        assertNull(outcomeOf(NodeSnapshot(clickable = true, checked = true)).checked)
        // A caption's text is not a value the operator set.
        assertEquals("", outcomeOf(NodeSnapshot(text = "Send", clickable = true)).text)
    }

    @Test
    fun `a control with no label at all yields nothing to parse`() {
        assertEquals("", markerLabel("   ", ControlOutcome(checked = true)))
    }
}
