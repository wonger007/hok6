package com.studybook.reader

import android.content.SharedPreferences
import android.graphics.Paint
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.min

private const val KEY_DOCX_ZOOM = "docx_zoom"
/** Text size picked with the zoom buttons before Hok6 1.8, in sp; turned into a zoom the first time. */
private const val KEY_DOCX_SIZE = "docx_text_size"
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 4f

/**
 * Shows a Word document like a page: its lines are wrapped for a column as wide as the screen's narrow side (the same
 * when the device is turned), and zooming makes the column, its text and its margins bigger or smaller together, so the
 * lines wrap the same way at every zoom. Tracing is kept in widths of the column, so it stays on the words.
 */
class DocxViewer(
    private val frame: ZoomPanView,
    private val scroll: ScrollView,
    private val text: InkTextView,
    private val prefs: SharedPreferences,
) {
    private val res = text.resources
    private val density = res.displayMetrics.density
    /** The column at zoom 1, before fitting it to the screen: the screen's narrow side. */
    private val columnPx = res.configuration.smallestScreenWidthDp * density
    private val textPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, res.getInteger(R.integer.docx_text_sp).toFloat(), res.displayMetrics)
    private val sidePx = res.getDimension(R.dimen.docx_padding)
    private val topPx = 32 * density
    private val bottomPx = 64 * density

    var zoom = savedZoom()
        private set

    init {
        // Text that grows in exact proportion to its size, so the lines break in the same places at any zoom.
        text.paintFlags = text.paintFlags or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        frame.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            // Turned, or the screen changed size: same zoom, same place in the document.
            if (r - l != or - ol) frame.post { applyKeepingPlace() }
        }
        frame.pinchListener = object : ZoomPanView.PinchListener {
            override fun onPinch(scale: Float, focusX: Float, focusY: Float) {
                val s = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom
                scroll.pivotX = focusX + frame.scrollX
                scroll.pivotY = focusY
                scroll.scaleX = s
                scroll.scaleY = s
            }

            override fun onPinchEnd(scale: Float, focusX: Float, focusY: Float) {
                scroll.scaleX = 1f
                scroll.scaleY = 1f
                setZoom(zoom * scale, focusX, focusY)
            }

            override fun onPan(dx: Float, dy: Float) {
                scroll.scrollBy(0, dy.toInt())
                frame.scrollBy(dx.toInt(), 0)
            }
        }
        apply()
    }

    fun zoomBy(factor: Float) = setZoom(zoom * factor, viewport() / 2f, frame.height / 2f)

    private fun viewport() = frame.width - frame.paddingLeft - frame.paddingRight

    /** How much bigger than the column at zoom 1 the document is shown: the zoom, fitted to a narrower screen. */
    private fun scale(z: Float = zoom): Float {
        val viewport = viewport()
        val fit = if (viewport > 0) min(1f, viewport / columnPx) else 1f
        return z * fit
    }

    private fun columnWidth(z: Float = zoom) = (columnPx * scale(z)).toInt().coerceAtLeast(1)

    /** Lays the document out for the current zoom. */
    private fun apply() {
        val s = scale()
        val width = columnWidth()
        (text.layoutParams as FrameLayout.LayoutParams).width = width
        text.layoutParams = text.layoutParams
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * s)
        text.setPadding((sidePx * s).toInt(), (topPx * s).toInt(), (sidePx * s).toInt(), (bottomPx * s).toInt())
        // Wider than the screen: scroll sideways; narrower: centred.
        frame.contentWidth = if (width > viewport()) width else 0
    }

    private fun applyKeepingPlace() {
        val height = text.height.coerceAtLeast(1)
        val fraction = scroll.scrollY.toFloat() / height
        apply()
        scroll.post { scroll.scrollTo(0, (fraction * text.height).toInt()) }
    }

    /** Zooms to [requested], keeping the point of the document at ([focusX], [focusY]) on screen where it is. */
    private fun setZoom(requested: Float, focusX: Float, focusY: Float) {
        val newZoom = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(newZoom - zoom) < 0.01f) return
        val ratio = scale(newZoom) / scale()
        // The point under the focus, in the column's own coordinates.
        val x = frame.scrollX + focusX - text.left
        val y = scroll.scrollY + focusY - text.top
        zoom = newZoom
        prefs.edit().putFloat(KEY_DOCX_ZOOM, zoom).apply()
        apply()
        frame.post {
            val left = ((viewport() - columnWidth()) / 2).coerceAtLeast(0)
            frame.scrollTo((x * ratio + left - focusX).toInt(), 0)
            scroll.scrollTo(0, (y * ratio - focusY).toInt())
        }
    }

    private fun savedZoom(): Float {
        if (prefs.contains(KEY_DOCX_ZOOM)) return prefs.getFloat(KEY_DOCX_ZOOM, 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val oldSize = prefs.getFloat(KEY_DOCX_SIZE, 0f)
        if (oldSize <= 0f) return 1f
        return (oldSize / res.getInteger(R.integer.docx_text_sp)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
