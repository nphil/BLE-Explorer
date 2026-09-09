package dev.nphil.blueshark.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A claim must outlive its run and not one instant longer.
 *
 * This is the sharp edge of [LinkExclusivity]: the primitive itself is trivially correct, but a
 * claim bound to a run by the obvious means - releasing in the coroutine's own `finally` - is
 * stranded whenever the scope dies before the body starts. Nothing can then release it, because
 * claims are per-caller and the caller is gone, so every write in the app is refused for the rest
 * of the process. Two separate screens wrote that bug independently before it was caught, which is
 * why the binding is a named function with these tests rather than a line at each call site.
 */
class LinkClaimLifetimeTest {

    @Test
    fun `a claim is released when its run finishes normally`() = runTest {
        val links = LinkExclusivity()
        val claim = requireNotNull(links.claim("A Command Prober sweep"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        val job = scope.launch { }
        links.releaseWhenComplete(claim, job)
        advanceUntilIdle()

        assertNull("the link is free again", links.holder.value)
        assertNotNull("and the next page can take it", links.claim("Identify"))
    }

    @Test
    fun `a cancelled run leaves the link free`() = runTest {
        val links = LinkExclusivity()
        val claim = requireNotNull(links.claim("A Command Prober sweep"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()

        val job = scope.launch {
            started.complete(Unit)
            // Stand in for a sweep waiting on the device; cancellation lands here.
            CompletableDeferred<Unit>().await()
        }
        links.releaseWhenComplete(claim, job)
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        assertNull("a stopped sweep must not keep the link", links.holder.value)
        assertNotNull(links.claim("Identify"))
    }

    /**
     * The case a `finally` inside the coroutine cannot cover: the scope is already dead, so the
     * body never runs at all. Before this binding existed that stranded the claim permanently.
     */
    @Test
    fun `a run whose scope died before it started still frees the link`() = runTest {
        val links = LinkExclusivity()
        val claim = requireNotNull(links.claim("A Command Prober sweep"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var bodyRan = false

        scope.cancel()
        val job = scope.launch { bodyRan = true }
        links.releaseWhenComplete(claim, job)
        advanceUntilIdle()

        assertNull("the body never ran, so only completion could have freed the link", links.holder.value)
        assertNotNull("and the app is not wedged", links.claim("Identify"))
        // Guarding the premise: if the body had run, a `finally` would have sufficed and this test
        // would be proving nothing.
        assert(!bodyRan) { "the coroutine body must not have run for this case to be meaningful" }
    }

    @Test
    fun `releasing on completion cannot free a claim someone else now holds`() = runTest {
        val links = LinkExclusivity()
        val mine = requireNotNull(links.claim("A Command Prober sweep"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val job: Job = scope.launch { }
        links.releaseWhenComplete(mine, job)
        advanceUntilIdle()

        // My run ended and the funnel took the link. My completion callback has already fired, but
        // a second, later fire must not evict the new owner.
        val theirs = requireNotNull(links.claim("A device-project identify"))
        links.release(mine)

        assertNotNull("the new owner keeps the link", links.holder.value)
        assert(links.claim("Third page") == null) { "the link must still be held by the funnel" }
        links.release(theirs)
        assertNull(links.holder.value)
    }
}
