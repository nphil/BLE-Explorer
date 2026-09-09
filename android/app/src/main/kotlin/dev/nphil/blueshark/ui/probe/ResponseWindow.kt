package dev.nphil.blueshark.ui.probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one response window that is open, tagged with the step it belongs to.
 *
 * [offer], [await]'s timeout and [close] all race the same compare-and-set and exactly one of them
 * wins, which is what makes "one response per step, and never the wrong step" a property of the
 * code rather than of the timing. See [ProbeViewModel] for the full four-clause contract.
 *
 * Deliberately its own file, and free of any `android.*` reference: this is the nucleus of the
 * Command Prober's only real correctness claim, it is the one part of the runner that can be
 * exercised without a radio, and `ResponseWindowTest` holds it to its contract.
 */
internal class ResponseWindow(val stepIndex: Int, val stepLabel: String) {

    val openedAtNanos: Long = System.nanoTime()

    private val gate = AtomicBoolean(true)
    private val arrived = CompletableDeferred<Unit>()

    @Volatile
    private var frame: ByteArray? = null

    /**
     * The frame this window took, or null if it never took one.
     *
     * Non-suspending, and never itself decides anything: [await] is what closes the window.
     */
    val accepted: ByteArray? get() = frame

    /** @return true when [bytes] became this step's one and only response. */
    fun offer(bytes: ByteArray): Boolean {
        if (!gate.compareAndSet(true, false)) return false
        frame = bytes
        arrived.complete(Unit)
        return true
    }

    /** Closes the window for good; every frame from now on is late by definition. */
    fun close() {
        gate.set(false)
    }

    /**
     * Waits up to [timeoutMs] for this step's response, and shuts the window on the way out.
     *
     * Closing belongs here rather than in the caller. If a timeout merely *returned* and the
     * caller closed afterwards, a frame arriving in that gap would win [offer], be recorded as
     * this step's answer by the collector, and then be thrown away by a caller that had already
     * read null - the frame would be neither the answer nor a late arrival, it would simply
     * vanish, and the vanished frame is the evidence. Making the timeout itself the losing side
     * of the same compare-and-set removes the gap: whoever wins decides, and the loser is always
     * accounted for.
     */
    suspend fun await(timeoutMs: Long): ByteArray? {
        if (withTimeoutOrNull(timeoutMs) { arrived.await() } != null) return frame
        // Timed out. Win the gate and the window is shut: every later frame is late by definition.
        if (gate.compareAndSet(true, false)) return null
        // Lost it, so a frame beat the deadline by a hair and offer() is mid-flight. It publishes
        // the bytes before completing the signal, so waiting for the signal is what makes them
        // visible; without this the winner would still be read as null.
        withTimeoutOrNull(HANDOFF_GRACE_MS) { arrived.await() }
        return frame
    }

    private companion object {
        /**
         * Only ever elapses if [offer] were descheduled between winning the gate and publishing,
         * which is a few instructions. It is a bound, not a wait.
         */
        const val HANDOFF_GRACE_MS = 50L
    }
}
