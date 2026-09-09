package dev.nphil.blueshark.guide

import dev.nphil.blueshark.model.ControlRef

/** Stateless, so one instance can serve every coverage record. */
private val LABELER = ControlLabeler()

/** What a screen is called when the app offers no name at all. */
const val UNNAMED_SCREEN = "screen"

/**
 * What to key one screen's inventory by.
 *
 * The activity class name comes first: it is stable, it survives the app changing what it writes
 * into the window title, and it is what a developer reading the export will recognise. A window
 * title is the fall-back (Compose single-activity apps often set one), then whatever the screen was
 * already called - a content change with no name of its own must not rename the screen underneath
 * the checklist.
 */
fun screenName(activity: String?, windowTitle: String?, previous: String): String =
    activity?.trim()?.takeIf { it.isNotEmpty() }
        ?: windowTitle?.trim()?.takeIf { it.isNotEmpty() }
        ?: previous.trim().takeIf { it.isNotEmpty() }
        ?: UNNAMED_SCREEN

/**
 * What one screen of the vendor app offers, and how much of it the operator has already exercised.
 *
 * @param controls every actionable control the observer found, in tree order.
 * @param touched keys of [controls] that have been interacted with at some point in the session.
 */
data class ScreenCoverage(
    val screen: String,
    val controls: List<ControlRef>,
    val touched: Set<String>,
) {
    /** Controls nobody has touched yet: the remaining checklist for this screen. */
    val untouched: List<ControlRef> = controls.filterNot { LABELER.key(it) in touched }

    val mapped: Int = controls.size - untouched.size
}

/** Coverage across every screen the observer has seen since the target package was selected. */
data class GuideProgress(val screens: List<ScreenCoverage> = emptyList()) {

    /** Actionable controls known across all screens. A control seen on two screens counts twice. */
    val total: Int = screens.sumOf { it.controls.size }

    val touchedCount: Int = screens.sumOf { it.mapped }

    fun untouched(screen: String): List<ControlRef> = coverage(screen)?.untouched ?: emptyList()

    fun coverage(screen: String): ScreenCoverage? = screens.firstOrNull { it.screen == screen }
}

/**
 * The session's control checklist: one inventory per screen plus the session-wide set of keys that
 * have been interacted with.
 *
 * Not thread-safe by design - the accessibility service drives it from the main thread only.
 */
class ControlInventory(private val labeler: ControlLabeler = ControlLabeler()) {

    private val screens = LinkedHashMap<String, List<ControlRef>>()
    private val touched = LinkedHashSet<String>()

    /**
     * Replaces the inventory of [screen]. Returns true when the set of controls actually changed,
     * so the caller can skip republishing state on the many no-op content-change events.
     */
    fun observe(screen: String, controls: List<ControlRef>): Boolean {
        val deduped = controls.distinctBy { labeler.key(it) }.take(MAX_CONTROLS_PER_SCREEN)
        val previous = screens.put(screen, deduped)
        evictOldestScreens(keep = screen)
        return previous != deduped
    }

    /** Records an interaction. Returns true when this control had not been touched before. */
    fun touch(control: ControlRef): Boolean {
        if (touched.size >= MAX_TOUCHED) touched.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        return touched.add(labeler.key(control))
    }

    fun isTouched(control: ControlRef): Boolean = labeler.key(control) in touched

    /** Forgets everything: called when the operator points the guide at a different app. */
    fun clear() {
        screens.clear()
        touched.clear()
    }

    fun progress(): GuideProgress = GuideProgress(
        screens.map { (screen, controls) ->
            val keys = controls.mapTo(HashSet(controls.size)) { labeler.key(it) }
            ScreenCoverage(screen = screen, controls = controls, touched = touched.intersect(keys))
        },
    )

    private fun evictOldestScreens(keep: String) {
        while (screens.size > MAX_SCREENS) {
            val oldest = screens.keys.firstOrNull { it != keep } ?: return
            screens.remove(oldest)
        }
    }

    private companion object {
        const val MAX_SCREENS = 32
        const val MAX_CONTROLS_PER_SCREEN = 400
        const val MAX_TOUCHED = 4_000
    }
}

/**
 * Every actionable control under [root], in tree order.
 *
 * "Actionable" is what the operator can drive: something clickable, checkable, long-clickable, or a
 * range. Invisible nodes and nodes without a drawable rectangle are skipped - they cannot be
 * highlighted and the operator cannot reach them. [rangeValue] is deliberately left null here: the
 * inventory records which controls exist, not what a slider happened to read while it was built.
 */
fun actionableControls(
    root: NodeView,
    packageName: String,
    screen: String,
    labeler: ControlLabeler = ControlLabeler(),
): List<ControlRef> {
    val found = ArrayList<ControlRef>()
    var budget = MAX_VISITED_NODES
    fun walk(node: NodeView, depth: Int) {
        if (budget <= 0 || depth > MAX_TREE_DEPTH) return
        budget--
        if (isActionable(node)) {
            found += ControlRef(
                packageName = packageName,
                screen = screen,
                viewId = node.viewId,
                className = node.className,
                text = node.text.trim(),
                contentDescription = node.contentDescription.trim(),
                bounds = node.bounds,
            )
        }
        // A hidden container can still hold visible children on some vendor layouts, so the walk
        // never prunes a subtree; only the node itself is filtered.
        node.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return found.distinctBy { labeler.key(it) }
}

private fun isActionable(node: NodeView): Boolean {
    if (!node.visible) return false
    val b = node.bounds
    if (b.size != 4) return false
    if (b[2] <= b[0] || b[3] <= b[1]) return false
    return node.clickable || node.checkable || node.longClickable || node.rangeValue != null
}

/** Enough for any real screen; a guard against a pathological or recursive tree. */
private const val MAX_VISITED_NODES = 1_500
private const val MAX_TREE_DEPTH = 40
