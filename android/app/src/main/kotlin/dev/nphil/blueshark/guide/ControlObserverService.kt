package dev.nphil.blueshark.guide

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.launch

/** A whole-tree rebuild is not free, and content changes arrive in floods while an app animates. */
private const val INVENTORY_THROTTLE_MS = 400L

/** Guards against a pathological or cyclic tree; the pure walk is bounded as well. */
private const val SNAPSHOT_MAX_DEPTH = 40
private const val SNAPSHOT_MAX_NODES = 1_500

/** How many ancestors a control may borrow a label from, and how deep a sibling is searched. */
private const val LABEL_ANCESTORS = 2
private const val SIBLING_DEPTH = 2

/**
 * Watches the one vendor app the operator selected while they drive the gadget: it drops a capture
 * marker for every control they touch and keeps the per-screen inventory of controls that are still
 * unmapped.
 *
 * Privacy, by construction:
 * - only the selected package is observed ([AccessibilityServiceInfo.packageNames] plus a per-event
 *   check), and nothing at all while no package is selected;
 * - [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED] is not in the subscribed set, so what the operator
 *   types is never delivered to this process;
 * - only labels, resource ids, class names and screen rectangles are recorded.
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

    /** Pending slider settles, keyed by control identity: the value it lands on wins. */
    private val settling = HashMap<String, Runnable>()

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
            onOpenApp = ::openBlueShark,
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
        if (from == target) rebuildInventory(target, force = true, fallbackScreen = null)
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
        settling.values.forEach { main.removeCallbacks(it) }
        settling.clear()
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                rebuildInventory(target, force = true, fallbackScreen = activityNameOf(event))

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                rebuildInventory(target, force = false, fallbackScreen = null)

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
     * Marks the control that was driven off the checklist and emits its marker. A range control is
     * settled instead: a drag fires continuously, and only the value the operator lands on is worth
     * recording.
     *
     * @param rangeOnly the event is a scroll, which is a control interaction only on a range node.
     */
    private fun onInteraction(event: AccessibilityEvent, target: String, rangeOnly: Boolean) {
        val guide = container?.guide ?: return
        val source = event.source ?: return
        val node = try {
            snapshotWithAncestors(source)
        } finally {
            source.release()
        }
        if (node == null) return
        val range = node.rangeValue
        // A plain list scroll is not an interaction with a control; the content change that follows
        // rebuilds the inventory anyway.
        if (rangeOnly && range == null) return
        if (screen.isBlank()) rebuildInventory(target, force = true, fallbackScreen = null)

        val control = ControlRef(
            packageName = event.packageName?.toString() ?: target,
            screen = screen.ifBlank { UNNAMED_SCREEN },
            viewId = node.viewId,
            className = node.className,
            text = node.text.trim(),
            contentDescription = node.contentDescription.trim(),
            bounds = node.bounds,
            rangeValue = range,
        )
        val label = labeler.label(node)
        if (range == null) {
            guide.recordInteraction(control, label, CLICK_DEBOUNCE_MS)
            refreshOverlay()
        } else {
            settle(control, label)
        }
    }

    /**
     * Records a range interaction once the operator stops moving it: the checklist is updated at
     * once, so the highlight clears under their finger, while the marker waits for silence.
     */
    private fun settle(control: ControlRef, label: String) {
        val guide = container?.guide ?: return
        val key = labeler.key(control)
        settling.remove(key)?.let { main.removeCallbacks(it) }
        // Checklist only while the finger is still down; the marker waits for the settled value.
        guide.markTouched(control.copy(rangeValue = null))
        refreshOverlay()
        val emit = Runnable {
            settling.remove(key)
            guide.recordInteraction(control, label, CLICK_DEBOUNCE_MS)
            refreshOverlay()
        }
        settling[key] = emit
        main.postDelayed(emit, RANGE_SETTLE_MS)
    }

    /**
     * Re-reads the active window and republishes its actionable controls.
     *
     * @param force bypasses the throttle; used when the window itself changed.
     * @param fallbackScreen activity name to fall back on when the window has no title.
     */
    private fun rebuildInventory(target: String, force: Boolean, fallbackScreen: String?) {
        val guide = container?.guide ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastInventoryAt < INVENTORY_THROTTLE_MS) return
        lastInventoryAt = now
        val root = rootInActiveWindow ?: return
        val snapshot = try {
            if (root.packageName?.toString() != target) {
                null
            } else {
                screen = windowTitleOf(root) ?: fallbackScreen ?: screen.ifBlank { UNNAMED_SCREEN }
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
                ),
            )
        }.onFailure { container?.debug?.log("guide", "overlay frame failed: ${it.message}") }
    }

    private fun openBlueShark() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        return NodeSnapshot(
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            bounds = if (rect.isEmpty) emptyList() else listOf(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            checkable = node.isCheckable,
            longClickable = node.isLongClickable,
            visible = node.isVisibleToUser,
            rangeValue = runCatching { node.rangeInfo?.current }.getOrNull(),
            children = children,
        )
    }
}

private const val UNNAMED_SCREEN = "screen"

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
