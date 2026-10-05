package com.studybook.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.hypot

enum class InkTool { PEN, ERASER }

/**
 * A traced line. Coordinates and width are divided by the width of the view it was drawn on,
 * so the line stays on the same spot of the page at any zoom level.
 */
class Stroke(val color: Int, val width: Float, val points: FloatArray) {
    fun hits(x: Float, y: Float, radius: Float): Boolean {
        val r = radius + width / 2
        val p = points
        if (p.size < 4) return hypot(p[0] - x, p[1] - y) <= r
        var i = 0
        while (i + 3 < p.size) {
            if (segmentDistance(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= r) return true
            i += 2
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/** All tracing for one file, keyed by page number (always 0 for Word documents). Saved in app storage. */
class InkDocument private constructor(private val file: File) {
    private val pages = HashMap<Int, MutableList<Stroke>>()
    private val undoStack = ArrayDeque<() -> Unit>()
    private var dirty = false
    private val saveLater = Runnable { save() }

    fun strokes(page: Int): List<Stroke> = pages[page] ?: emptyList()

    fun add(page: Int, stroke: Stroke) {
        val list = pages.getOrPut(page) { mutableListOf() }
        list += stroke
        record { list.remove(stroke) }
    }

    fun eraseAt(page: Int, x: Float, y: Float, radius: Float): Boolean {
        val list = pages[page] ?: return false
        val hit = list.filter { it.hits(x, y, radius) }
        if (hit.isEmpty()) return false
        list.removeAll(hit)
        record { list.addAll(hit) }
        return true
    }

    fun clear(page: Int) {
        val list = pages[page] ?: return
        if (list.isEmpty()) return
        val old = list.toList()
        list.clear()
        record { list.addAll(old) }
    }

    fun undo(): Boolean {
        val action = undoStack.removeLastOrNull() ?: return false
        action()
        changed()
        return true
    }

    private fun record(undo: () -> Unit) {
        undoStack.addLast(undo)
        if (undoStack.size > 200) undoStack.removeFirst()
        changed()
    }

    /** Saves shortly after each change, so tracing survives the app being killed. */
    private fun changed() {
        dirty = true
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, 1000)
    }

    fun save() {
        main.removeCallbacks(saveLater)
        if (!dirty) return
        val json = JSONObject()
        for ((page, strokes) in pages) {
            if (strokes.isEmpty()) continue
            json.put(page.toString(), JSONArray().apply {
                for (s in strokes) put(JSONObject().apply {
                    put("c", s.color)
                    put("w", s.width.toDouble())
                    put("p", JSONArray().apply { s.points.forEach { put(it.toDouble()) } })
                })
            })
        }
        val text = if (json.length() == 0) null else json.toString()
        dirty = false
        writer.execute {
            runCatching {
                if (text == null) {
                    file.delete()
                } else {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(text)
                    tmp.renameTo(file)
                }
            }
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        // One thread, so saves of the same file are written in order.
        private val writer = Executors.newSingleThreadExecutor()

        fun load(context: Context, uri: Uri): InkDocument {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            val name = digest.joinToString("") { "%02x".format(it) }
            val doc = InkDocument(File(context.filesDir, "ink/$name.json"))
            runCatching {
                if (!doc.file.exists()) return@runCatching
                val json = JSONObject(doc.file.readText())
                for (key in json.keys()) {
                    val arr = json.getJSONArray(key)
                    doc.pages[key.toInt()] = MutableList(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        val p = o.getJSONArray("p")
                        Stroke(o.getInt("c"), o.getDouble("w").toFloat(), FloatArray(p.length()) { p.getDouble(it).toFloat() })
                    }
                }
            }
            return doc
        }
    }
}

/** Shared tracing settings for the chapter screen. */
class Ink(context: Context) {
    val density = context.resources.displayMetrics.density
    var active = false
    /** Practise mode: a tap on a character opens writing practice instead of drawing or scrolling. */
    var practising = false
    var tool = InkTool.PEN
    var color = PEN_COLORS[0]
    var widthDp = PEN_SIZES[1]
    /** Once a stylus is used, fingers go back to scrolling and only the stylus draws. */
    var stylusSeen = false
    var document: InkDocument? = null

    companion object {
        val PEN_COLORS = intArrayOf(0xE6E53935.toInt(), 0xE61E88E5.toInt(), 0xE6212121.toInt())
        val PEN_SIZES = floatArrayOf(3f, 6f, 11f)
    }
}

/** Touch handling and drawing of tracing on top of one view (a PDF page or the Word text). */
class InkSurface(private val view: View, private val ink: Ink, private val pan: ((Float) -> Unit)? = null) {
    var page = 0
    private var current: ArrayList<Float>? = null
    private var erasing = false
    private var panning = false
    private var lastPanY = 0f
    private val location = IntArray(2)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        val doc = ink.document
        if (!ink.active || doc == null || view.width == 0) return false
        val toolType = ev.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) ink.stylusSeen = true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ink.stylusSeen && toolType == MotionEvent.TOOL_TYPE_FINGER) return false

        val w = view.width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                panning = false
                erasing = ink.tool == InkTool.ERASER || toolType == MotionEvent.TOOL_TYPE_ERASER ||
                    (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (erasing) erase(doc, ev.x, ev.y, w) else current = arrayListOf(ev.x / w, ev.y / w)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means "scroll", not "draw".
                current = null
                erasing = false
                panning = pan != null
                lastPanY = screenY(ev)
            }
            MotionEvent.ACTION_MOVE -> when {
                panning -> {
                    val y = screenY(ev)
                    pan?.invoke(lastPanY - y)
                    lastPanY = y
                }
                erasing -> {
                    for (h in 0 until ev.historySize) erase(doc, ev.getHistoricalX(h), ev.getHistoricalY(h), w)
                    erase(doc, ev.x, ev.y, w)
                }
                else -> current?.let {
                    for (h in 0 until ev.historySize) {
                        it += ev.getHistoricalX(h) / w
                        it += ev.getHistoricalY(h) / w
                    }
                    it += ev.x / w
                    it += ev.y / w
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (panning) lastPanY = screenY(ev, skip = ev.actionIndex)
            MotionEvent.ACTION_UP -> {
                current?.let { doc.add(page, Stroke(ink.color, ink.widthDp * ink.density / w, it.toFloatArray())) }
                current = null
            }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        view.invalidate()
        return true
    }

    private fun erase(doc: InkDocument, x: Float, y: Float, w: Float) {
        if (doc.eraseAt(page, x / w, y / w, 12 * ink.density / w)) view.invalidate()
    }

    private fun screenY(ev: MotionEvent, skip: Int = -1): Float {
        view.getLocationOnScreen(location)
        var sum = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) if (i != skip) {
            sum += ev.getY(i)
            n++
        }
        return location[1] + if (n == 0) 0f else sum / n
    }

    fun draw(canvas: Canvas) {
        val doc = ink.document ?: return
        val w = view.width.toFloat()
        if (w <= 0f) return
        for (s in doc.strokes(page)) drawStroke(canvas, s.points, s.color, s.width * w, w)
        current?.let { drawStroke(canvas, it.toFloatArray(), ink.color, ink.widthDp * ink.density, w) }
    }

    private fun drawStroke(canvas: Canvas, p: FloatArray, color: Int, widthPx: Float, w: Float) {
        if (p.size < 2) return
        path.rewind()
        var px = p[0] * w
        var py = p[1] * w
        path.moveTo(px, py)
        var i = 2
        while (i + 1 < p.size) {
            val x = p[i] * w
            val y = p[i + 1] * w
            path.quadTo(px, py, (px + x) / 2, (py + y) / 2)
            px = x
            py = y
            i += 2
        }
        path.lineTo(px + 0.1f, py)
        paint.color = color
        paint.strokeWidth = widthPx
        canvas.drawPath(path, paint)
    }
}

/** A rendered PDF page that can be traced on. */
class InkPageView(context: Context, private val ink: Ink) : AppCompatImageView(context) {
    val surface = InkSurface(this, ink)

    /** Tap in practise mode, with the point in this view's coordinates. */
    var onTapAt: ((x: Float, y: Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    init {
        setOnClickListener { if (ink.practising) onTapAt?.invoke(downX, downY) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        surface.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
        }
        return surface.onTouchEvent(event) || super.onTouchEvent(event)
    }
}

/** The Word document text, which can be traced on. */
class InkTextView(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {
    var surface: InkSurface? = null
    var ink: Ink? = null

    /** Tap in practise mode, with the text offset under the finger. */
    var onTapAt: ((offset: Int) -> Unit)? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        surface?.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ink?.practising == true) {
            // Taps pick a character; drags are left to the parent ScrollView.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> if (kotlin.math.hypot(event.x - downX, event.y - downY) < touchSlop) {
                    onTapAt?.invoke(getOffsetForPosition(event.x, event.y))
                }
            }
            return true
        }
        return surface?.onTouchEvent(event) == true || super.onTouchEvent(event)
    }
}
