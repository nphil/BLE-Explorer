package dev.nphil.blueshark.ble

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide exclusive claim on the one GATT link, for operations whose *correctness* depends on
 * nobody else writing.
 *
 * [GattClient] already serialises requests, so this is not about ATT ordering - the stack handles
 * that. It is about attribution. A Command Prober verdict is the correlation between a frame this
 * app wrote and the notification that came back inside that step's window, so a write issued by
 * another screen mid-sweep does not merely interleave: the device's answer to *that* write is
 * offered to the step in flight, turning a nonexistent opcode into an accepted one. Tearing the
 * link down is worse still.
 *
 * A flag on any one screen cannot express this. Navigation keeps view models alive on the back
 * stack, so the sweep that matters may belong to an instance the writer has never heard of - the
 * device-project funnel hosts its own runner while the full probe page keeps another. So ownership
 * lives here, beside the client both of them share, and is claimed rather than announced:
 * [claim] is a compare-and-set, and a caller that gets null has been told, authoritatively, that
 * it must not write. Checking [holder] first is for the UI; it is not the guard.
 *
 * Claims are opaque and per-caller, so a second holder-to-be cannot release the claim in force,
 * and a stale claim cannot release the one that replaced it.
 */
class LinkExclusivity {

    /** Proof of ownership. Identity, not equality: two claims for the same reason are distinct. */
    class Claim internal constructor(val reason: String)

    private val current = AtomicReference<Claim?>(null)

    private val _holder = MutableStateFlow<String?>(null)

    /** Human-readable reason the link is held, for anything that has to explain a refusal. */
    val holder: StateFlow<String?> = _holder.asStateFlow()

    /**
     * Takes exclusive ownership, or returns null because somebody else already has it - in which
     * case [holder] names them.
     *
     * @param reason what is holding the link, phrased for the operator ("A Command Prober sweep").
     */
    fun claim(reason: String): Claim? {
        val claim = Claim(reason)
        if (!current.compareAndSet(null, claim)) return null
        _holder.value = reason
        return claim
    }

    /** Idempotent, and safe to call with null or with a claim that has already been released. */
    fun release(claim: Claim?) {
        if (claim != null && current.compareAndSet(claim, null)) _holder.value = null
    }
}

/**
 * Ties [claim]'s lifetime to [job], releasing it whenever that job ends for any reason.
 *
 * Use this instead of releasing in the job's own `finally`. A coroutine launched on a scope that
 * was already cancelled - a view model cleared in the same frame the operator started a run - never
 * executes its body at all, so a `finally` there never runs and the claim is stranded. Nothing can
 * then release it: claims are per-caller and the holder is gone, so every write in the app is
 * refused for the rest of the process, with no way back short of a restart. `invokeOnCompletion`
 * fires for that job too.
 *
 * It is also correctly ordered for teardown, which a caller-side release is not: completion is
 * strictly after the job's own `finally`, so work that restores device state on the way out - the
 * Command Prober's CCCD teardown, for one - has finished before the next claimant can take the
 * link.
 */
fun LinkExclusivity.releaseWhenComplete(claim: LinkExclusivity.Claim?, job: Job) {
    job.invokeOnCompletion { release(claim) }
}
