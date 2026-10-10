package com.studybook.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.input.motionprediction.MotionEventPredictor
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

    /** Goes up with every change, so views know when to make their drawing of the tracing again. */
    var version = 0
        private set

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

    /** Clears these pages as one step (one Undo brings them all back). Returns false if there was nothing to clear. */
    fun clear(pageNumbers: Collection<Int>): Boolean {
        val old = pageNumbers.mapNotNull { p -> pages[p]?.takeIf { it.isNotEmpty() }?.let { p to it.toList() } }
        if (old.isEmpty()) return false
        for ((p, _) in old) pages[p]?.clear()
        record { for ((p, strokes) in old) pages.getOrPut(p) { mutableListOf() }.addAll(strokes) }
        return true
    }

    /** A copy of all the tracing, by page, safe to read on another thread. */
    fun snapshot(): Map<Int, List<Stroke>> = pages.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }

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
        version++
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
                    put("w", round(s.width))
                    put("p", JSONArray().apply { s.points.forEach { put(round(it)) } })
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

        /**
         * Positions and widths are fractions of the page's width: 5 decimal places is within a fiftieth of a pixel on a
         * page drawn 2400 pixels wide, and keeps the saved file and the backup about half the size of full precision.
         */
        internal fun round(v: Float): Double = Math.round(v * 100_000.0) / 100_000.0

        private fun fileFor(context: Context, uri: Uri): File {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            val name = digest.joinToString("") { "%02x".format(it) }
            return File(context.filesDir, "ink/$name.json")
        }

        /** Runs [block] on the save thread, after any tracing still being written. */
        fun afterSaves(block: () -> Unit) = writer.execute(block)

        /** Keeps a file's tracing when the file moves to a new address; runs after any save still being written. */
        fun rename(context: Context, from: Uri, to: Uri) {
            val source = fileFor(context, from)
            val target = fileFor(context, to)
            writer.execute { runCatching { if (source.exists()) source.renameTo(target) } }
        }

        /** Forgets a file's tracing (it was replaced by another file); runs after any save still being written. */
        fun delete(context: Context, uri: Uri) {
            val file = fileFor(context, uri)
            writer.execute { file.delete() }
        }

        fun load(context: Context, uri: Uri): InkDocument = load(fileFor(context, uri))

        /** The tracing saved in [file] (none if it isn't there or can't be read). */
        internal fun load(file: File): InkDocument {
            val doc = InkDocument(file)
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

/**
 * Stylus or finger, for writing on pages and in writing practice. On a device whose screen can't take a stylus,
 * fingers always draw. On one that can, the choice is a toggle in the title bar, remembered; the first time a stylus
 * touches the screen Hok6 switches to it (only the stylus writes, fingers scroll, so a hand can rest on the page).
 */
object Stylus {
    private const val KEY_FINGERS = "fingers_draw"
    private const val KEY_USED = "stylus_used"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a stylus can be used: the screen takes one, or one has been used (e.g. a Bluetooth stylus). */
    fun supported(context: Context): Boolean = prefs(context).getBoolean(KEY_USED, false) ||
        InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.supportsSource(InputDevice.SOURCE_STYLUS) == true }

    fun fingersDraw(context: Context) = !supported(context) || prefs(context).getBoolean(KEY_FINGERS, true)

    fun setFingersDraw(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_FINGERS, on).apply()

    /** A stylus touched the screen. The first time ever, switches to stylus mode and returns true. */
    fun used(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY_USED, false)) return false
        prefs(context).edit().putBoolean(KEY_USED, true).putBoolean(KEY_FINGERS, false).apply()
        return true
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
    /** Whether fingers draw too, or only the stylus does and fingers scroll (see [Stylus]). */
    var fingersDraw = Stylus.fingersDraw(context)
    /** A finger touched the page while only the stylus draws, e.g. to say how to draw with fingers again. */
    var onFingerIgnored: (() -> Unit)? = null
    /** The stylus touched the page (the first time ever, Hok6 switches to stylus mode: see [Stylus.used]). */
    var onStylus: (() -> Unit)? = null
    var document: InkDocument? = null

    companion object {
        val PEN_COLORS = intArrayOf(0xE6E53935.toInt(), 0xE61E88E5.toInt(), 0xE6212121.toInt())
        val PEN_SIZES = floatArrayOf(3f, 6f, 11f)
    }
}

/** Touch handling and drawing of tracing on top of one view (a PDF page or the Word text). */
class InkSurface(private val view: View, private val ink: Ink) {
    var page = 0
    private var current: ArrayList<Float>? = null
    private var erasing = false
    /** The stroke being drawn, in pixels, up to its last point but one (see [draw]); made for [liveWidth]. */
    private val livePath = Path()
    private var livePoints = 0
    private var liveWidth = 0f
    private val scratch = Path()
    /** Guesses where the pen is going, so the line being drawn keeps up with its tip. */
    private val predictor by lazy { MotionEventPredictor.newInstance(view) }
    /**
     * The page's finished strokes as paths, made for one document version, page and view width, so drawing doesn't
     * work them out again each frame. Strokes are kept in widths of the page, so a new width means making them again.
     */
    private var cached: List<Path> = emptyList()
    private var cachedDoc: InkDocument? = null
    private var cachedVersion = -1
    private var cachedPage = -1
    private var cachedWidth = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        val doc = ink.document
        if (!ink.active || doc == null || view.width == 0) return false
        val toolType = ev.getToolType(0)
        val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (stylus && ev.actionMasked == MotionEvent.ACTION_DOWN) ink.onStylus?.invoke()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !ink.fingersDraw && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            ink.onFingerIgnored?.invoke()
            return false
        }

        val w = view.width.toFloat()
        predictor.record(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                erasing = ink.tool == InkTool.ERASER || toolType == MotionEvent.TOOL_TYPE_ERASER ||
                    (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (erasing) erase(doc, ev.x, ev.y, w) else current = arrayListOf(ev.x / w, ev.y / w)
                livePoints = 0
                livePath.rewind()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means "scroll or zoom" (ZoomPanView does it), not "draw".
                current = null
                erasing = false
            }
            MotionEvent.ACTION_MOVE -> when {
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

    fun draw(canvas: Canvas) {
        val doc = ink.document ?: return
        val w = view.width.toFloat()
        if (w <= 0f) return
        val strokes = doc.strokes(page)
        if (doc !== cachedDoc || doc.version != cachedVersion || page != cachedPage || w != cachedWidth || strokes.size != cached.size) {
            cached = strokes.map { s -> Path().also { buildPath(it, s.points, s.points.size, w) } }
            cachedDoc = doc
            cachedVersion = doc.version
            cachedPage = page
            cachedWidth = w
        }
        for ((i, s) in strokes.withIndex()) {
            paint.color = s.color
            paint.strokeWidth = s.width * w
            canvas.drawPath(cached[i], paint)
        }
        current?.let { drawLive(canvas, it, w) }
    }

    /**
     * The stroke being drawn. Its path grows by the points added since the last frame; the end that changes with each
     * new point is added on a copy, so the stroke is one path (no darker spots where pieces of it would overlap).
     */
    private fun drawLive(canvas: Canvas, p: ArrayList<Float>, w: Float) {
        if (p.size < 2) return
        if (w != liveWidth || p.size < livePoints) {
            livePath.rewind()
            livePoints = 0
            liveWidth = w
        }
        if (livePoints == 0) {
            livePath.moveTo(p[0] * w, p[1] * w)
            livePoints = 2
        }
        while (livePoints + 1 < p.size) {
            val px = p[livePoints - 2] * w
            val py = p[livePoints - 1] * w
            livePath.quadTo(px, py, (px + p[livePoints] * w) / 2, (py + p[livePoints + 1] * w) / 2)
            livePoints += 2
        }
        scratch.set(livePath)
        scratch.lineTo(p[p.size - 2] * w + 0.1f, p[p.size - 1] * w)
        // Where the pen is about to be: drawn this frame only, never saved.
        predictor.predict()?.let { predicted ->
            scratch.lineTo(predicted.x, predicted.y)
            predicted.recycle()
        }
        paint.color = ink.color
        paint.strokeWidth = ink.widthDp * ink.density
        canvas.drawPath(scratch, paint)
    }

    /** A stroke's path at view width [w]: smooth curves through the midpoints, ending at the last point. */
    private fun buildPath(path: Path, p: FloatArray, size: Int, w: Float) {
        if (size < 2) return
        var px = p[0] * w
        var py = p[1] * w
        path.moveTo(px, py)
        var i = 2
        while (i + 1 < size) {
            val x = p[i] * w
            val y = p[i + 1] * w
            path.quadTo(px, py, (px + x) / 2, (py + y) / 2)
            px = x
            py = y
            i += 2
        }
        path.lineTo(px + 0.1f, py)
    }
}

/** A rendered PDF page that can be traced on. */
class InkPageView(context: Context, private val ink: Ink) : AppCompatImageView(context) {
    val surface = InkSurface(this, ink)

    /**
     * The page's height divided by its width. The view takes its height from its own width when it's laid out, so the
     * page is never stretched: tracing is kept in widths of the page, and must land on the same spot of the picture.
     */
    var aspect = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Called when the view's width changes, so the page can be drawn again at the new size. */
    var onWidthChanged: ((width: Int) -> Unit)? = null

    /** Tap in practise mode, with the point in this view's coordinates. */
    var onTapAt: ((x: Float, y: Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    init {
        setOnClickListener { if (ink.practising) onTapAt?.invoke(downX, downY) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * aspect).toInt().coerceAtLeast(1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && oldw != 0) onWidthChanged?.invoke(w)
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
