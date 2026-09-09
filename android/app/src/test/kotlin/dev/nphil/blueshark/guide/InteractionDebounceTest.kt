package dev.nphil.blueshark.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One tap on a vendor button reaches an accessibility service more than once (the click event, then
 * the content change that follows it), and a dragged slider fires dozens of times a second. Without
 * this guard the timeline fills with duplicates of the same control.
 */
class InteractionDebounceTest {

    private var now = 10_000L
    private val debounce = InteractionDebounce { now }

    @Test
    fun `a repeat of the same control inside the window is dropped`() {
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))

        now += 40
        assertFalse(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
        now += 200
        assertFalse(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
    }

    @Test
    fun `another control is never blocked by its neighbour's burst`() {
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))

        now += 10
        assertTrue(debounce.accept("btn_light", CLICK_DEBOUNCE_MS))
        assertFalse(debounce.accept("btn_light", CLICK_DEBOUNCE_MS))
    }

    @Test
    fun `pressing the same control again after the window is real input`() {
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))

        now += CLICK_DEBOUNCE_MS
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
        now += CLICK_DEBOUNCE_MS - 1
        assertFalse(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
    }

    @Test
    fun `a slider gets the longer settle window`() {
        assertTrue(debounce.accept("seek_brightness", RANGE_SETTLE_MS))

        now += CLICK_DEBOUNCE_MS + 1
        assertFalse(debounce.accept("seek_brightness", RANGE_SETTLE_MS))
        now += RANGE_SETTLE_MS
        assertTrue(debounce.accept("seek_brightness", RANGE_SETTLE_MS))
    }

    @Test
    fun `a clock that jumped backwards does not lock a control out`() {
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))

        now -= 60_000
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
    }

    @Test
    fun `clearing forgets the history, as pointing the guide at another app does`() {
        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
        debounce.clear()

        assertTrue(debounce.accept("btn_power", CLICK_DEBOUNCE_MS))
    }

    @Test
    fun `the marker text carries a slider's value and nothing else`() {
        assertEquals("Brightness = 42.0", markerLabel("Brightness", 42f))
        assertEquals("Power", markerLabel("Power", null))
    }
}
