package dev.nphil.blueshark.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.nphil.blueshark.debug.DebugLog
import dev.nphil.blueshark.model.ControlRef

/** Which edge the cockpit sits on. */
enum class Dock { TOP, BOTTOM }

/** One frame of the guide overlay: everything the two windows draw, already resolved. */
data class OverlayFrame(
    val screen: String,
    val mapped: Int,
    val total: Int,
    val highlights: Boolean,
    val lastLabel: String,
    val untouched: List<Rect>,
    val touched: List<Rect>,
    /** "Learning · Mi Home · 2:07". */
    val sessionLine: String = "",
    /** "iLedClock · held by a central (the app?)", or why there is nothing to say. */
    val targetLine: String = "",
    /** "taps 7 · writes: after collection". */
    val countersLine: String = "",
    /** Empty while the observer is healthy. */
    val quietLine: String = "",
    val dock: Dock = Dock.TOP,
)

/**
 * The edge with the fewest actionable controls behind it, so the cockpit never sits on top of the
 * buttons the operator has to drive. A control spanning both bands counts in both: it is behind
 * either dock.
 *
 * Ties, unknown geometry and an empty screen go to [Dock.TOP]. Moving the strip is only worth it
 * when the layout gives a clear reason, and a strip that hops on every inventory rebuild is worse
 * than one that sits still.
 */
fun chooseDock(controls: List<ControlRef>, screenHeight: Int): Dock {
    if (screenHeight <= 0) return Dock.TOP
    val band = screenHeight / DOCK_BANDS
    var top = 0
    var bottom = 0
    for (control in controls) {
        val bounds = control.bounds
        if (bounds.size != BOUNDS_FIELDS) continue
        if (bounds[1] < band) top++
        if (bounds[3] > screenHeight - band) bottom++
    }
    return if (bottom < top) Dock.BOTTOM else Dock.TOP
}

/** `2:07` inside the first hour, `1:02:07` after it; a clock that makes no sense reads `0:00`. */
fun elapsedText(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val rest = seconds % 60L
    return if (hours > 0L) "$hours:${pad(minutes)}:${pad(rest)}" else "$minutes:${pad(rest)}"
}

/** "Learning · Mi Home · 2:07"; the app's name is dropped when nobody knew it. */
fun sessionLine(appLabel: String, elapsedMs: Long): String =
    listOf("Learning", appLabel.trim(), elapsedText(elapsedMs)).filter { it.isNotEmpty() }.joinToString(" · ")

/** "iLedClock · advertising", falling back to the address, then to saying there is no target. */
fun targetLine(status: TargetStatus, sessionStartedAtMs: Long): String {
    val who = status.name.trim().ifEmpty { status.address }
    if (who.isEmpty()) return "no target device picked"
    return "$who · ${presenceText(status, sessionStartedAtMs)}"
}

/**
 * "taps 7 · writes: after collection".
 *
 * The write count is deliberately not a number: HCI writes are only readable once the snoop log has
 * been collected, and a plausible-looking 0 would be a lie the operator would act on.
 */
fun countersLine(taps: Int): String = "taps $taps · writes: after collection"

/** "observer quiet for 42 s" once the platform has gone silent on us; empty while it has not. */
fun quietLine(quietSeconds: Long): String =
    if (quietSeconds < OBSERVER_QUIET_WARN_S) "" else "observer quiet for $quietSeconds s"

/** Below this a gap is just an operator reading the screen; above it, something ate the service. */
const val OBSERVER_QUIET_WARN_S = 30L

private const val DOCK_BANDS = 4
private const val BOUNDS_FIELDS = 4

private fun pad(value: Long): String = if (value < 10L) "0$value" else "$value"

/**
 * The cockpit the operator sees on top of the vendor app: a non-touchable canvas that outlines the
 * controls still missing from the checklist, and a draggable pill carrying the progress, the last
 * marker, the session status strip and the actions.
 *
 * It docks to the edge of the screen where the vendor app has fewest controls ([chooseDock]) until
 * the operator drags it somewhere else, after which their placement is respected.
 *
 * Plain views on purpose. These windows are added by the accessibility service with
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], which needs no overlay permission - and
 * a Compose view cannot be hosted from a service without a lifecycle owner and a saved-state
 * registry, neither of which a service has.
 *
 * Every window-manager call is guarded: a failed overlay must degrade to "no overlay", never take
 * the accessibility service down with it.
 */
class GuideOverlay(
    private val context: Context,
    private val debug: DebugLog,
    private val onMark: (String) -> Unit,
    private val onToggleHighlights: () -> Unit,
    private val onFinish: () -> Unit,
) {

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var highlightView: HighlightView? = null
    private var pill: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var progressLabel: TextView? = null
    private var lastLabel: TextView? = null
    private var highlightsButton: TextView? = null
    private var statusButton: TextView? = null
    private var strip: LinearLayout? = null
    private var sessionLabel: TextView? = null
    private var targetLabel: TextView? = null
    private var countersLabel: TextView? = null
    private var quietLabel: TextView? = null
    private var editorRow: LinearLayout? = null
    private var editor: EditText? = null
    private var shown = false

    /** The strip is the point of the cockpit, so it starts open; Hide collapses it to the pill. */
    private var expanded = true

    private var dock = Dock.TOP

    /** Set once the operator drags the pill: their placement outranks any automatic dock. */
    private var placedByHand = false

    /** Adds the windows if needed, then pushes [frame] into them. */
    fun show(frame: OverlayFrame) {
        if (!shown) {
            if (!attach()) return
            shown = true
        }
        render(frame)
    }

    fun hide() {
        if (!shown) return
        shown = false
        closeEditor()
        highlightView?.let { remove(it) }
        pill?.let { remove(it) }
        highlightView = null
        pill = null
        pillParams = null
        progressLabel = null
        lastLabel = null
        highlightsButton = null
        statusButton = null
        strip = null
        sessionLabel = null
        targetLabel = null
        countersLabel = null
        quietLabel = null
        editorRow = null
        editor = null
        placedByHand = false
    }

    fun destroy() = hide()

    // ------------------------------------------------------------------ windows

    private fun attach(): Boolean {
        val insetTop = statusBarInset()
        val highlights = HighlightView(context, insetTop)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val bar = buildPill()
        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f)
            // Never over the status bar: the operator still needs the clock and the battery.
            y = insetTop + dp(12f)
        }

        if (!add(highlights, canvasParams)) return false
        if (!add(bar, barParams)) {
            remove(highlights)
            return false
        }
        highlightView = highlights
        pill = bar
        pillParams = barParams
        bar.setOnTouchListener(DragListener(barParams))
        // The dock is only knowable once the pill has a height, and it changes with the strip.
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyDock() }
        return true
    }

    private fun render(frame: OverlayFrame) {
        highlightView?.submit(frame)
        progressLabel?.text = "${frame.mapped}/${frame.total} mapped · ${frame.screen}"
        lastLabel?.apply {
            text = if (frame.lastLabel.isBlank()) "" else "last: ${frame.lastLabel}"
            visibility = if (frame.lastLabel.isBlank()) View.GONE else View.VISIBLE
        }
        highlightsButton?.text = if (frame.highlights) "Hide boxes" else "Show boxes"
        sessionLabel?.text = frame.sessionLine
        targetLabel?.text = frame.targetLine
        countersLabel?.text = frame.countersLine
        quietLabel?.apply {
            text = frame.quietLine
            visibility = if (frame.quietLine.isEmpty()) View.GONE else View.VISIBLE
        }
        if (frame.dock != dock) {
            dock = frame.dock
            applyDock()
        }
    }

    /**
     * Moves the pill to the docked edge. Driven from the layout listener because the height is only
     * known after a measure pass, and it changes whenever the strip opens or closes.
     */
    private fun applyDock() {
        if (placedByHand) return
        val bar = pill ?: return
        val params = pillParams ?: return
        val height = bar.height
        if (height <= 0) return
        val margin = dp(12f)
        val top = statusBarInset() + margin
        val wanted = when (dock) {
            Dock.TOP -> top
            Dock.BOTTOM -> (screenHeight() - navigationBarInset() - height - margin).coerceAtLeast(top)
        }
        if (params.y == wanted) return
        params.y = wanted
        update()
    }

    private fun setExpanded(open: Boolean) {
        expanded = open
        strip?.visibility = if (open) View.VISIBLE else View.GONE
        statusButton?.visibility = if (open) View.GONE else View.VISIBLE
    }

    private fun add(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { windowManager.addView(view, params) }
            .onFailure { debug.log("guide", "overlay add failed: ${it.message}") }
            .isSuccess

    private fun remove(view: View) {
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { debug.log("guide", "overlay remove failed: ${it.message}") }
    }

    private fun update() {
        val bar = pill ?: return
        val params = pillParams ?: return
        runCatching { windowManager.updateViewLayout(bar, params) }
            .onFailure { debug.log("guide", "overlay update failed: ${it.message}") }
    }

    private fun statusBarInset(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
    }.getOrDefault(0)

    private fun navigationBarInset(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
    }.getOrDefault(0)

    private fun screenHeight(): Int =
        runCatching { windowManager.currentWindowMetrics.bounds.height() }.getOrDefault(0)

    // ------------------------------------------------------------------ the pill

    private fun buildPill(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(PILL_BACKGROUND)
                setStroke(dp(1f), ACCENT)
            }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }

        val progress = label(textSize = 13f, color = Color.WHITE).apply { text = "0/0 mapped" }
        val last = label(textSize = 11f, color = MUTED).apply { visibility = View.GONE }
        val statusStrip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, 0)
        }
        val session = label(textSize = 12f, color = Color.WHITE)
        val target = label(textSize = 11f, color = MUTED)
        val counters = label(textSize = 11f, color = MUTED)
        val quiet = label(textSize = 11f, color = WARNING).apply { visibility = View.GONE }
        val stripActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
            addView(pillButton("Finish") { onFinish() })
            addView(pillButton("Hide") { setExpanded(false) }, marginStart(dp(6f)))
        }
        statusStrip.addView(session)
        statusStrip.addView(target)
        statusStrip.addView(counters)
        statusStrip.addView(quiet)
        statusStrip.addView(stripActions)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        val mark = pillButton("Mark") { openEditor() }
        val highlights = pillButton("Hide boxes") { onToggleHighlights() }
        // Only a way back into the strip, so it is hidden while the strip is already open.
        val status = pillButton("Status") { setExpanded(true) }.apply { visibility = View.GONE }
        actions.addView(mark)
        actions.addView(highlights, marginStart(dp(6f)))
        actions.addView(status, marginStart(dp(6f)))

        val field = EditText(context).apply {
            hint = "What are you about to do?"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            minWidth = dp(150f)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(FIELD_BACKGROUND)
            }
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(6f), 0, 0)
            addView(field)
            addView(pillButton("Save") { saveEditor() }, marginStart(dp(6f)))
            addView(pillButton("Cancel") { closeEditor() }, marginStart(dp(6f)))
        }

        root.addView(progress)
        root.addView(last)
        root.addView(statusStrip)
        root.addView(actions)
        root.addView(row)

        progressLabel = progress
        lastLabel = last
        highlightsButton = highlights
        statusButton = status
        strip = statusStrip
        sessionLabel = session
        targetLabel = target
        countersLabel = counters
        quietLabel = quiet
        editorRow = row
        editor = field
        // A hide/show cycle (the operator left the app and came back) keeps their choice.
        setExpanded(expanded)
        return root
    }

    private fun label(textSize: Float, color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        maxWidth = dp(240f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun pillButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(BUTTON_BACKGROUND)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun marginStart(px: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = px }

    /**
     * The pill is the only touchable window, so typing needs the focus flag dropped and the IME
     * asked to come up; it is restored on close so the vendor app keeps its own focus afterwards.
     */
    private fun openEditor() {
        val params = pillParams ?: return
        editorRow?.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        update()
        editor?.let { field ->
            field.requestFocus()
            runCatching {
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun saveEditor() {
        val text = editor?.text?.toString().orEmpty().trim()
        if (text.isNotEmpty()) onMark(text)
        closeEditor()
    }

    private fun closeEditor() {
        val params = pillParams ?: return
        editor?.let { field ->
            field.setText("")
            runCatching {
                context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
            }
        }
        editorRow?.visibility = View.GONE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        update()
    }

    /** Drags the pill by its background; the buttons consume their own touches. */
    private inner class DragListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    dragging = true
                    // From here on the operator owns the position; the automatic dock stands down.
                    placedByHand = true
                }
                if (dragging) {
                    val metrics = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
                    val maxX = ((metrics?.width() ?: 0) - view.width).coerceAtLeast(0)
                    val maxY = ((metrics?.height() ?: 0) - view.height).coerceAtLeast(0)
                    params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                    params.y = (startY + dy.toInt()).coerceIn(statusBarInset(), maxY.coerceAtLeast(statusBarInset()))
                    update()
                }
                true
            }
            else -> dragging
        }
    }

    private fun dp(value: Float): Int = TypedValue
        .applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
        .toInt()

    private companion object {
        val ACCENT = 0xFF4DD0E1.toInt()
        val PILL_BACKGROUND = 0xE60B1B2E.toInt()
        val BUTTON_BACKGROUND = 0x33FFFFFF
        val FIELD_BACKGROUND = 0x33000000
        val MUTED = 0xB3FFFFFF.toInt()

        /** Only used for "the platform has stopped talking to us", which is worth an amber line. */
        val WARNING = 0xFFFFB74D.toInt()
    }
}

/**
 * The highlight canvas. Not touchable, so it never intercepts a tap meant for the vendor app.
 *
 * Control rectangles arrive in screen coordinates while this view is laid out inside the content
 * area, so the canvas is translated by the view's own position on screen instead of assuming the
 * two origins coincide.
 */
private class HighlightView(context: Context, private val insetTop: Int) : View(context) {

    private var frame: OverlayFrame? = null
    private val origin = IntArray(2)
    private val scratch = RectF()

    private val pending = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF4DD0E1.toInt()
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2233C759
    }

    fun submit(next: OverlayFrame) {
        frame = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = frame ?: return
        if (!current.highlights) return
        getLocationOnScreen(origin)
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        // Anything level with the status bar stays untouched; the operator needs to read it.
        canvas.clipRect(origin[0], maxOf(origin[1], insetTop), origin[0] + width, origin[1] + height)
        current.touched.forEach { draw(canvas, it, done, RADIUS) }
        current.untouched.forEach { draw(canvas, it, pending, RADIUS) }
        canvas.restore()
    }

    private fun draw(canvas: Canvas, rect: Rect, paint: Paint, radius: Float) {
        scratch.set(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        canvas.drawRoundRect(scratch, radius, radius, paint)
    }

    private companion object {
        const val RADIUS = 8f
    }
}
