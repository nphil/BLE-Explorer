package dev.nphil.blueshark.guide

import dev.nphil.blueshark.model.ControlRef

/**
 * A framework-free view of one node in an accessibility tree.
 *
 * The guided take-over's logic is written against this instead of `AccessibilityNodeInfo` for two
 * reasons: it is unit-testable on the JVM, and it forces the service to snapshot the volatile
 * platform nodes (which must be recycled below API 33) before any thinking happens.
 */
interface NodeView {
    val text: String
    val contentDescription: String

    /** Resource id as the platform reports it, e.g. `com.vendor.app:id/btn_power`. */
    val viewId: String
    val className: String
    val children: List<NodeView>
    val parent: NodeView?

    /** Screen rectangle as `[left, top, right, bottom]`; empty when the node has no geometry. */
    val bounds: List<Int>
    val clickable: Boolean
    val checkable: Boolean

    /** Current state of a [checkable] node; meaningless, and always false, on any other node. */
    val checked: Boolean
    val longClickable: Boolean

    /** True when the node holds text the operator can change (a field, not a caption). */
    val editable: Boolean
    val visible: Boolean

    /** Current value when the node models a range (slider, seek bar, volume), else null. */
    val rangeValue: Float?

    /** The ends a range node moves between; null on anything else, and on a broken range. */
    val rangeMin: Float?
    val rangeMax: Float?
}

/**
 * Immutable [NodeView]: what the service snapshots a platform node tree into, and what the tests
 * build by hand. [parent] is wired by [attach] because a tree cannot be built bottom-up and
 * top-down at once.
 */
class NodeSnapshot(
    override val text: String = "",
    override val contentDescription: String = "",
    override val viewId: String = "",
    override val className: String = "",
    override val bounds: List<Int> = emptyList(),
    override val clickable: Boolean = false,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val longClickable: Boolean = false,
    override val editable: Boolean = false,
    override val visible: Boolean = true,
    override val rangeValue: Float? = null,
    override val rangeMin: Float? = null,
    override val rangeMax: Float? = null,
    children: List<NodeSnapshot> = emptyList(),
) : NodeView {

    override val children: List<NodeView> = children

    override var parent: NodeView? = null
        private set

    init {
        children.forEach { it.attach(this) }
    }

    private fun attach(node: NodeSnapshot) {
        parent = node
    }
}

/**
 * Turns a control into the words the operator already sees on screen.
 *
 * Vendor apps label controls in every possible way: text on the button, a content description for
 * an icon, a `TextView` sitting next to a switch, or nothing at all. The precedence below is
 * ordered by how likely each source is to match what a human would call the control.
 */
class ControlLabeler {

    /**
     * Precedence: own text, own content description, the text of a labelled descendant, the text of
     * the nearest labelled sibling or ancestor within two levels, the humanised resource id, and
     * finally `Class#index` so a label is never empty.
     */
    fun label(node: NodeView): String =
        own(node)
            ?: descendantLabel(node, DESCENDANT_DEPTH)
            ?: relativeLabel(node)
            ?: idLabel(node.viewId)
            ?: "${simpleName(node.className)}#${indexInParent(node)}"

    /**
     * Stable identity of a control across inventory rebuilds: the resource id when the app ships
     * one, otherwise the shape of the node plus its position rounded into [BOUNDS_BUCKET]-pixel
     * cells, so a few pixels of layout jitter do not mint a second control.
     */
    fun key(control: ControlRef): String {
        val id = idLeaf(control.viewId)
        if (id.isNotEmpty()) return id
        val cell = control.bounds
            .takeIf { it.size == BOUNDS_FIELDS }
            ?.joinToString(",") { (it / BOUNDS_BUCKET).toString() }
            .orEmpty()
        return listOf(
            simpleName(control.className),
            control.text.trim(),
            control.contentDescription.trim(),
            cell,
        ).joinToString("|")
    }

    /**
     * Display name of a control the observer already recorded. Same precedence as [label] minus the
     * tree walks, which a [ControlRef] cannot reproduce - it is a snapshot, not a node.
     */
    fun describe(control: ControlRef): String =
        control.text.trim().ifBlank { control.contentDescription.trim() }
            .ifBlank { idLabel(control.viewId).orEmpty() }
            .ifBlank { simpleName(control.className) }
            .ifBlank { "control" }

    private fun own(node: NodeView): String? =
        node.text.trim().takeIf { it.isNotEmpty() } ?: node.contentDescription.trim().takeIf { it.isNotEmpty() }

    private fun descendantLabel(node: NodeView, depth: Int): String? {
        if (depth <= 0) return null
        for (child in node.children) {
            own(child)?.let { return it }
        }
        for (child in node.children) {
            descendantLabel(child, depth - 1)?.let { return it }
        }
        return null
    }

    /**
     * Walks up at most two levels. At each level the siblings come first (a `TextView` beside a
     * switch is the label), then their shallow descendants, then the container's own description.
     */
    private fun relativeLabel(node: NodeView): String? {
        var child: NodeView = node
        var ancestor: NodeView? = node.parent
        var level = 0
        while (ancestor != null && level < ANCESTOR_LEVELS) {
            val siblings = ancestor.children.filter { it !== child }
            for (sibling in siblings) {
                own(sibling)?.let { return it }
            }
            for (sibling in siblings) {
                descendantLabel(sibling, SIBLING_DEPTH)?.let { return it }
            }
            own(ancestor)?.let { return it }
            child = ancestor
            ancestor = ancestor.parent
            level++
        }
        return null
    }

    /** `com.vendor.app:id/btn_power` becomes "btn power"; a node without an id yields null. */
    private fun idLabel(viewId: String): String? {
        val leaf = idLeaf(viewId)
        if (leaf.isEmpty()) return null
        return leaf.replace(ID_SEPARATORS, " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun idLeaf(viewId: String): String =
        viewId.substringAfterLast('/').substringAfterLast(':').trim()

    private fun simpleName(className: String): String = className.substringAfterLast('.').trim()

    private fun indexInParent(node: NodeView): Int =
        node.parent?.children?.indexOfFirst { it === node }?.takeIf { it >= 0 } ?: 0

    private companion object {
        /** How deep a control's own subtree is searched for a label ("icon + caption" buttons). */
        const val DESCENDANT_DEPTH = 4

        /** How far up the tree a borrowed label may come from. */
        const val ANCESTOR_LEVELS = 2

        /** How deep a sibling's subtree is searched; deeper than this is somebody else's label. */
        const val SIBLING_DEPTH = 2

        const val BOUNDS_BUCKET = 8
        const val BOUNDS_FIELDS = 4
        val ID_SEPARATORS = Regex("[_\\-.]+")
    }
}
