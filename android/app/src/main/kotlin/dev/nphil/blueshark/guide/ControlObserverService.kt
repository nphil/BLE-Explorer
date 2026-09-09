package dev.nphil.blueshark.guide

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.BlueSharkApp
import dev.nphil.blueshark.MainActivity
import dev.nphil.blueshark.model.ControlRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A whole-tree rebuild is not free, and content changes arrive in floods while an app animates. */
private const val INVENTORY_THROTTLE_MS = 400L

/** Guards against a pathological or cyclic tree; the pure walk is bounded as well. */
private const val SNAPSHOT_MAX_DEPTH = 40
private const val SNAPSHOT_MAX_NODES = 1_500

/** How many ancestors a control may borrow a label from, and how deep a sibling is searched. */
private const val LABEL_ANCESTORS = 2
private const val SIBLING_DEPTH = 2

/** Elapsed time, the target's presence and the observer's own liveness all move without events. */
private const val COCKPIT_TICK_MS = 1_000L

/**
 * Watches the one vendor app the operator selected while they drive the gadget: it drops a capture
 * marker for every control they touch and keeps the per-screen inventory of controls that are still
 * unmapped.
 *
 * Privacy, by construction:
 * - only the selected package is observed ([AccessibilityServiceInfo.packageNames] plus a per-event
 *   check), and nothing at all while no package is selected;
 * - [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED] is not in the subscribed set, so the operator's
 *   keystrokes are never delivered to this process;
 * - what is recorded is labels, resource ids, class names, screen rectangles - and, for a control
 *   the operator drove, the state it was left in: a switch position, a slider percentage, or the
 *   value already visible in a field, clipped to [MARKER_TEXT_MAX] characters.
 */
class ControlObserverService : AccessibilityService() {

    private val container: AppContainer?
        get() = (application as? BlueSharkApp)?.container

    private val labeler = ControlLabeler()
    private val main = Handler(Looper.getMainLooper())

    private var scope: CoroutineScope? = null
    private var overlay: GuideOverlay? = null

    private var screen: String = ""
    private var foreground: String? = null
    private var lastInventoryAt = 0L

    /**
     * Interactions waiting for their control to settle, keyed by control identity: the state it
     * lands on wins. Each holds the platform node it will re-read, and owns releasing it.
     */
    private val settling = HashMap<String, Settling>()

    /** Last activity class name seen in front for the target package; the screen is keyed by it. */
    private var activity: String? = null

    /** The vendor app's own name, resolved once per package so the strip can show it. */
    private var appLabel: Pair<String, String>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = container ?: return
        val guide = container.guide
        container.debug.log(
            "guide",
            "observer connected on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        )
        guide.onServiceConnected()
        // A reconnect without a teardown would otherwise leak the previous windows.
        runCatching { overlay?.destroy() }
        overlay = GuideOverlay(
            context = this,
            debug = container.debug,
            onMark = guide::emitManualMarker,
            onToggleHighlights = { guide.setHighlights(!guide.state.value.highlights) },
            onFinish = ::finishLearning,
        )
        scope?.cancel()
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = started
        started.launch {
            guide.state.collect { state ->
                applyTarget(state.targetPackage)
                refreshOverlay()
            }
        }
        // Nothing about the session clock, the target's presence or the observer's own silence is
        // event-driven, so the cockpit is also pushed on a slow tick.
        started.launch {
            while (true) {
                delay(COCKPIT_TICK_MS)
                refreshOverlay()
            }
        }
        started.launch { guide.targetStatus.collect { refreshOverlay() } }
        val target = guide.state.value.targetPackage
        applyTarget(target)
        if (target != null) primeForeground(target)
    }

    /**
     * The vendor app may already be in front when the observer is enabled, and a static screen
     * emits no events at all - so the first inventory is read from the active window directly
     * instead of waiting for a window change that may never come.
     */
    private fun primeForeground(target: String) {
        val root = rootInActiveWindow ?: return
        val from = root.packageName?.toString()
        root.release()
        foreground = from
        if (from == target) rebuildInventory(target, force = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown("unbound")
        return super.onUnbind(intent)
    }

    /** Called when the platform tears the service down - including an OEM task killer. */
    override fun onDestroy() {
        teardown("service killed or stopped")
        super.onDestroy()
    }

    override fun onInterrupt() {
        container?.debug?.log("guide", "observer interrupted")
    }

    private fun teardown(reason: String) {
        settling.keys.toList().forEach(::cancelSettle)
        scope?.cancel()
        scope = null
        runCatching { overlay?.destroy() }
        overlay = null
        foreground = null
        container?.guide?.onServiceDisconnected(reason)
    }

    // ------------------------------------------------------------------ events

    /**
     * Restricts delivery to the selected package.
     *
     * An *empty* package list means "every package" to the platform, so "observe nothing" is
     * expressed as "observe only ourselves": this process emits nothing of interest, and the
     * per-event package check in [onAccessibilityEvent] is the real guarantee.
     */
    private fun applyTarget(target: String?) {
        val info = serviceInfo ?: return
        val wanted = arrayOf(target ?: packageName)
        if (info.packageNames?.contentEquals(wanted) != true) {
            info.packageNames = wanted
            runCatching { serviceInfo = info }
                .onFailure { container?.debug?.log("guide", "could not narrow observation: ${it.message}") }
        }
        if (target == null) {
            screen = ""
            activity = null
            foreground = null
            runCatching { overlay?.hide() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val guide = container?.guide ?: return
        val target = guide.state.value.targetPackage ?: return
        val from = event.packageName?.toString()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foreground = from
            if (from != target) {
                // The operator left the vendor app: the overlay must not sit over anything else.
                runCatching { overlay?.hide() }
                return
            }
        }
        if (from != target) return
        if (foreground == null) foreground = from
        guide.noteEvent()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                activityNameOf(event)?.let { activity = it }
                rebuildInventory(target, force = true)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                rebuildInventory(target, force = false)

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            -> onInteraction(event, target, rangeOnly = false)

            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                onInteraction(event, target, rangeOnly = true)

            else -> Unit
        }
    }

    /**
     * Marks the control that was driven off the checklist and emits its marker, carrying the state
     * the control was left in.
     *
     * A control whose state the interaction changed is settled instead of emitted at once: a drag
     * fires continuously and only the value the operator lands on matters, and a switch still
     * reports the state it is *leaving* at the moment its click event is delivered.
     *
     * @param rangeOnly the event is a scroll, which is a control interaction only on a range node.
     */
    private fun onInteraction(event: AccessibilityEvent, target: String, rangeOnly: Boolean) {
        val guide = container?.guide ?: return
        val source = event.source ?: return
        // The moment of the tap, kept because the marker may only be emitted once it has settled.
        val at = System.currentTimeMillis()
        var handedOver = false
        try {
            val node = snapshotWithAncestors(source) ?: return
            // A plain list scroll is not an interaction with a control; the content change that
            // follows rebuilds the inventory anyway.
            if (rangeOnly && node.rangeValue == null) return
            if (screen.isBlank()) rebuildInventory(target, force = true)

            val outcome = outcomeOf(node)
            val control = ControlRef(
                packageName = event.packageName?.toString() ?: target,
                screen = screen.ifBlank { UNNAMED_SCREEN },
                viewId = node.viewId,
                className = node.className,
                text = node.text.trim(),
                contentDescription = node.contentDescription.trim(),
                bounds = node.bounds,
                rangeValue = node.rangeValue,
            )
            val label = labeler.label(node)
            val settleMs = when {
                node.rangeValue != null -> RANGE_SETTLE_MS
                node.checkable || node.editable -> CHECK_SETTLE_MS
                else -> 0L
            }
            if (settleMs <= 0L) {
                guide.recordInteraction(control, label, outcome, at)
                refreshOverlay()
            } else {
                settle(control, label, outcome, source, settleMs, at)
                handedOver = true
            }
        } finally {
            if (!handedOver) source.release()
        }
    }

    /**
     * Emits the marker once the interaction has settled, re-reading [node] first so the state that
     * reaches the timeline is the one the operator can see.
     *
     * The checklist is updated immediately - the highlight has to clear under their finger - while
     * the marker waits. [node] is owned by the pending emit from here on.
     */
    private fun settle(
        control: ControlRef,
        label: String,
        outcome: ControlOutcome,
        node: AccessibilityNodeInfo,
        delayMs: Long,
        atMs: Long,
    ) {
        val guide = container?.guide ?: run {
            node.release()
            return
        }
        val key = labeler.key(control)
        cancelSettle(key)
        guide.markTouched(control.copy(rangeValue = null))
        refreshOverlay()
        val emit = Runnable {
            // Removed first, so the release below is the only one this node can get.
            settling.remove(key)
            val settled = settledOutcome(node, outcome)
            node.release()
            guide.recordInteraction(
                control = control.copy(rangeValue = settled.rangeValue ?: control.rangeValue),
                label = label,
                outcome = settled,
                atMs = atMs,
            )
            refreshOverlay()
        }
        settling[key] = Settling(node, emit)
        main.postDelayed(emit, delayMs)
    }

    /** Drops a pending emit and releases the node it was holding. */
    private fun cancelSettle(key: String) {
        val pending = settling.remove(key) ?: return
        main.removeCallbacks(pending.emit)
        pending.node.release()
    }

    /**
     * The state the control holds now. A click event is delivered before the vendor app has applied
     * it, so the node is re-read; when the platform refuses the refresh - the view is gone, the app
     * has moved on - the state read at event time is all there is.
     */
    private fun settledOutcome(node: AccessibilityNodeInfo, fallback: ControlOutcome): ControlOutcome {
        if (!runCatching { node.refresh() }.getOrDefault(false)) return fallback
        val range = runCatching { node.rangeInfo }.getOrNull()
        return ControlOutcome(
            checked = if (runCatching { node.isCheckable }.getOrDefault(false)) {
                runCatching { node.checkedNow() }.getOrDefault(false)
            } else {
                null
            },
            rangeValue = range?.current,
            rangePercent = rangePercent(range?.current, range?.min, range?.max),
            text = if (runCatching { node.isEditable }.getOrDefault(false)) {
                node.text?.toString().orEmpty()
            } else {
                ""
            },
        )
    }

    /** A marker waiting for its control to settle, and the node it will re-read on the way out. */
    private class Settling(val node: AccessibilityNodeInfo, val emit: Runnable)

    /**
     * Re-reads the active window and republishes its actionable controls.
     *
     * @param force bypasses the throttle; used when the window itself changed.
     */
    private fun rebuildInventory(target: String, force: Boolean) {
        val guide = container?.guide ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastInventoryAt < INVENTORY_THROTTLE_MS) return
        lastInventoryAt = now
        val root = rootInActiveWindow ?: return
        val snapshot = try {
            if (root.packageName?.toString() != target) {
                null
            } else {
                screen = screenName(activity, windowTitleOf(root), screen)
                copy(root, 0, intArrayOf(SNAPSHOT_MAX_NODES))
            }
        } finally {
            root.release()
        }
        if (snapshot == null) return
        guide.observeScreen(screen, actionableControls(snapshot, target, screen, labeler))
        refreshOverlay()
    }

    // ------------------------------------------------------------------ overlay

    /** Shown only while the guide wants it and the target app is actually in front. */
    private fun refreshOverlay() {
        val guide = container?.guide ?: return
        val overlay = overlay ?: return
        val state = guide.state.value
        val target = state.targetPackage
        if (!state.overlay || target == null || foreground != target) {
            runCatching { overlay.hide() }
            return
        }
        val coverage = state.progress.coverage(screen)
        val untouched = ArrayList<Rect>()
        val touched = ArrayList<Rect>()
        val pending = coverage?.untouched?.mapTo(HashSet()) { labeler.key(it) }.orEmpty()
        coverage?.controls?.forEach { control ->
            val bounds = control.bounds
            if (bounds.size != 4) return@forEach
            val rect = Rect(bounds[0], bounds[1], bounds[2], bounds[3])
            if (labeler.key(control) in pending) untouched += rect else touched += rect
        }
        val now = System.currentTimeMillis()
        runCatching {
            overlay.show(
                OverlayFrame(
                    screen = screen.ifBlank { UNNAMED_SCREEN },
                    mapped = state.progress.touchedCount,
                    total = state.progress.total,
                    highlights = state.highlights,
                    lastLabel = state.lastInteraction?.let { labeler.describe(it) }.orEmpty(),
                    untouched = untouched,
                    touched = touched,
                    sessionLine = sessionLine(
                        appLabel = state.targetLabel ?: appLabelOf(target),
                        elapsedMs = if (state.sessionStartedAtMs > 0L) now - state.sessionStartedAtMs else 0L,
                    ),
                    targetLine = targetLine(guide.targetStatus.value, state.sessionStartedAtMs),
                    countersLine = countersLine(state.taps),
                    quietLine = quietLine(quietSeconds(state.lastEventAtMs, now)),
                    dock = chooseDock(coverage?.controls.orEmpty(), screenHeight()),
                ),
            )
        }.onFailure { container?.debug?.log("guide", "overlay frame failed: ${it.message}") }
    }

    /**
     * Display height in the coordinate space control bounds arrive in, so the dock decision and the
     * pill's own placement agree on where "the bottom" is.
     */
    private fun screenHeight(): Int = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
    }.getOrDefault(resources.displayMetrics.heightPixels)

    /**
     * The vendor app's own name, as the launcher shows it. Cached: this is read on every frame, and
     * the package manager lookup is a binder call.
     */
    private fun appLabelOf(packageName: String): String {
        appLabel?.let { (cached, label) -> if (cached == packageName) return label }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: packageName
        appLabel = packageName to label
        return label
    }

    /**
     * Finish: end the session, then hand back to BlueShark with the flag that tells it to collect
     * and correlate this session without asking the operator to press anything else.
     */
    private fun finishLearning() {
        container?.guide?.finishSession()
        openBlueShark(finishLearning = true)
    }

    private fun openBlueShark(finishLearning: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (finishLearning) intent.putExtra(GuideController.EXTRA_FINISH_LEARNING, true)
        runCatching { startActivity(intent) }
            .onFailure { container?.debug?.log("guide", "could not bring BlueShark forward: ${it.message}") }
    }

    // ------------------------------------------------------------------ platform nodes

    private fun activityNameOf(event: AccessibilityEvent): String? =
        event.className?.toString()?.substringAfterLast('.')?.trim()?.takeIf { it.isNotEmpty() }

    private fun windowTitleOf(root: AccessibilityNodeInfo): String? {
        val window: AccessibilityWindowInfo = runCatching { root.window }.getOrNull() ?: return null
        val title = window.title?.toString()?.trim()
        window.release()
        return title?.takeIf { it.isNotEmpty() }
    }

    /**
     * Snapshots the node that was driven, plus up to [LABEL_ANCESTORS] ancestors and their shallow
     * children - that is where a control borrows its label from when it carries none itself. The
     * returned snapshot is the node itself, with the ancestors wired in as parents.
     */
    private fun snapshotWithAncestors(node: AccessibilityNodeInfo): NodeSnapshot? {
        val budget = intArrayOf(SNAPSHOT_MAX_NODES)
        val self = copy(node, 0, budget) ?: return null
        var childSnapshot: NodeSnapshot = self
        var childNode: AccessibilityNodeInfo = node
        var ownsChildNode = false
        var level = 0
        while (level < LABEL_ANCESTORS) {
            val parent = runCatching { childNode.parent }.getOrNull() ?: break
            val siblings = ArrayList<NodeSnapshot>()
            val count = runCatching { parent.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val sibling = runCatching { parent.getChild(index) }.getOrNull() ?: continue
                if (sibling == childNode) {
                    siblings += childSnapshot
                } else {
                    copy(sibling, 0, budget, depthLimit = SIBLING_DEPTH)?.let { siblings += it }
                }
                sibling.release()
            }
            // A parent that does not list the node (a detached or virtual node) still gets to be
            // its parent: the label search must be able to reach it.
            if (siblings.none { it === childSnapshot }) siblings += childSnapshot
            val wrapped = NodeSnapshot(
                text = parent.text?.toString().orEmpty(),
                contentDescription = parent.contentDescription?.toString().orEmpty(),
                viewId = parent.viewIdResourceName.orEmpty(),
                className = parent.className?.toString().orEmpty(),
                children = siblings,
            )
            if (ownsChildNode) childNode.release()
            childSnapshot = wrapped
            childNode = parent
            ownsChildNode = true
            level++
        }
        if (ownsChildNode) childNode.release()
        return self
    }

    /**
     * Copies a platform node tree into plain data, so the pure logic never holds a node whose
     * lifetime it would have to manage. Every node this function obtains is released again.
     */
    private fun copy(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
        depthLimit: Int = SNAPSHOT_MAX_DEPTH,
    ): NodeSnapshot? {
        if (budget[0] <= 0 || depth > depthLimit) return null
        budget[0]--
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val children = ArrayList<NodeSnapshot>()
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until count) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            copy(child, depth + 1, budget, depthLimit)?.let { children += it }
            child.release()
        }
        val range = runCatching { node.rangeInfo }.getOrNull()
        return NodeSnapshot(
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            bounds = if (rect.isEmpty) emptyList() else listOf(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            checkable = node.isCheckable,
            checked = node.checkedNow(),
            longClickable = node.isLongClickable,
            editable = runCatching { node.isEditable }.getOrDefault(false),
            visible = node.isVisibleToUser,
            rangeValue = range?.current,
            rangeMin = range?.min,
            rangeMax = range?.max,
            children = children,
        )
    }
}

/**
 * Whether a checkable node is checked.
 *
 * The tri-state accessor that deprecated this one only exists well above this app's minSdk, and the
 * marker grammar the learn/ slice parses is binary either way, so the boolean is the one code path.
 */
@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.checkedNow(): Boolean = isChecked

/**
 * Recycling is required below API 33 and a no-op afterwards (the platform pools nodes itself), so
 * the calls are version-gated rather than dropped.
 */
@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { recycle() }
}

@Suppress("DEPRECATION")
private fun AccessibilityWindowInfo.release() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { recycle() }
}
