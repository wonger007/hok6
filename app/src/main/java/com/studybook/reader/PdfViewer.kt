package com.studybook.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val MAX_RENDER_WIDTH = 2400

/** Shows a PDF as a vertical list of pages rendered with the platform [PdfRenderer]. */
class PdfViewer(
    private val frame: ZoomPanView,
    private val pages: RecyclerView,
    private val indicator: TextView,
    private val ink: Ink,
) {
    private val context = pages.context
    private val main = Handler(Looper.getMainLooper())
    // PdfRenderer is not thread-safe: every renderer call happens on this one thread.
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    @Volatile private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    private var zoom = 1f
    private val gap = (8 * context.resources.displayMetrics.density).toInt()
    private val layoutManager = LinearLayoutManager(context)
    private val adapter = PageAdapter()
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Tap on a page in practise mode: page index and the point in PDF points from the page's top-left corner. */
    var onPageTap: ((page: Int, x: Float, y: Float) -> Unit)? = null

    val currentPage: Int get() = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)

    /** The page in the middle of the screen. */
    val middlePage: Int
        get() = pages.findChildViewUnder(pages.width / 2f, pages.height / 2f)
            ?.let { pages.getChildAdapterPosition(it) }?.takeIf { it >= 0 } ?: currentPage

    /** Pages showing on screen: those filling at least a fifth of it, and always the one in the middle. */
    val pagesOnScreen: List<Int>
        get() {
            val height = pages.height.coerceAtLeast(1)
            val shown = (0 until pages.childCount).map { pages.getChildAt(it) }.filter { child ->
                val visible = minOf(child.bottom, height) - maxOf(child.top, 0)
                visible >= height / 5 || visible >= child.height / 2
            }.mapNotNull { pages.getChildAdapterPosition(it).takeIf { p -> p >= 0 } }
            return (shown + middlePage).distinct().sorted()
        }

    init {
        pages.layoutManager = layoutManager
        pages.adapter = adapter
        pages.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = gap
            }
        })
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })
        // Re-measure pages when the available width changes (rotation, hiding the file list).
        frame.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol && pageSizes.isNotEmpty()) main.post {
                applyWidth()
                adapter.notifyDataSetChanged()
            }
        }
        frame.pinchListener = object : ZoomPanView.PinchListener {
            override fun onPinch(scale: Float, focusX: Float, focusY: Float) {
                val s = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom
                pages.pivotX = focusX + frame.scrollX
                pages.pivotY = focusY
                pages.scaleX = s
                pages.scaleY = s
            }

            override fun onPinchEnd(scale: Float, focusX: Float, focusY: Float) {
                pages.scaleX = 1f
                pages.scaleY = 1f
                setZoom(zoom * scale, focusX, focusY)
            }

            override fun onPan(dx: Float, dy: Float) {
                pages.scrollBy(0, dy.toInt())
                frame.scrollBy(dx.toInt(), 0)
            }
        }
    }

    fun open(uri: Uri, startPage: Int, onError: (Throwable) -> Unit) {
        close()
        val gen = generation.get()
        val resolver = context.contentResolver
        executor.execute {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r") ?: error("cannot open file")
                val r = PdfRenderer(pfd)
                val sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
                val opened = pfd
                main.post {
                    if (generation.get() != gen) {
                        executor.execute { r.close(); opened.close() }
                        return@post
                    }
                    renderer = r
                    fd = opened
                    pageSizes = sizes
                    adapter.notifyDataSetChanged()
                    layoutManager.scrollToPositionWithOffset(startPage.coerceIn(0, (sizes.size - 1).coerceAtLeast(0)), 0)
                    pages.post { updateIndicator() }
                }
            } catch (t: Throwable) {
                runCatching { pfd?.close() }
                main.post { if (generation.get() == gen) onError(t) }
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        val r = renderer
        val f = fd
        renderer = null
        fd = null
        pageSizes = emptyList()
        zoom = 1f
        frame.contentWidth = 0
        frame.scrollTo(0, 0)
        adapter.notifyDataSetChanged()
        cache.evictAll()
        indicator.isVisible = false
        if (r != null || f != null) executor.execute {
            runCatching { r?.close() }
            runCatching { f?.close() }
        }
    }

    fun shutdown() {
        close()
        executor.shutdown()
    }

    /** Width of a page in PDF points, once the document is open. */
    fun pageWidthPoints(page: Int): Int? = pageSizes.getOrNull(page)?.first

    /** Renders a page [width] pixels wide (for text recognition); calls back on the main thread, with null on failure. */
    fun renderPage(page: Int, width: Int, onResult: (Bitmap?) -> Unit) {
        val gen = generation.get()
        executor.execute {
            val r = renderer
            val bitmap = if (r == null || generation.get() != gen) null else runCatching {
                r.openPage(page).use { p ->
                    val height = (width.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
            main.post { onResult(bitmap) }
        }
    }

    fun invalidateInk() {
        for (i in 0 until pages.childCount) pages.getChildAt(i).invalidate()
    }

    fun zoomBy(factor: Float) = setZoom(zoom * factor, frame.width / 2f, frame.height / 2f)

    private fun setZoom(requested: Float, focusX: Float, focusY: Float) {
        val newZoom = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (pageSizes.isEmpty() || abs(newZoom - zoom) < 0.01f) return
        val ratio = newZoom / zoom
        val pos = layoutManager.findFirstVisibleItemPosition()
        val top = layoutManager.findViewByPosition(pos)?.top ?: 0
        val newScrollX = ((frame.scrollX + focusX) * ratio - focusX).toInt()
        zoom = newZoom
        applyWidth()
        adapter.notifyDataSetChanged()
        if (pos >= 0) layoutManager.scrollToPositionWithOffset(pos, ((top - focusY) * ratio + focusY).toInt())
        frame.post {
            frame.scrollTo(newScrollX, 0)
            updateIndicator()
        }
    }

    private fun applyWidth() {
        frame.contentWidth = if (zoom <= 1f) 0 else (frame.width * zoom).toInt()
    }

    private fun updateIndicator() {
        val count = pageSizes.size
        indicator.isVisible = count > 0
        if (count == 0) return
        indicator.text = "${middlePage + 1} / $count"
    }

    private fun render(holder: PageHolder, page: Int, width: Int, gen: Int) {
        executor.execute {
            val r = renderer
            // Skip work for pages that scrolled away or documents that were closed meanwhile.
            if (r == null || generation.get() != gen || holder.page != page) return@execute
            val bitmap = try {
                r.openPage(page).use { p ->
                    val height = (width.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (t: Throwable) {
                return@execute
            }
            main.post {
                if (generation.get() != gen) return@post
                cache.put(key(page, width), bitmap)
                if (holder.page == page) holder.image.setImageBitmap(bitmap)
            }
        }
    }

    private fun key(page: Int, width: Int) = "$page@$width"

    private class PageHolder(val image: InkPageView) : RecyclerView.ViewHolder(image) {
        @Volatile var page = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageSizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = InkPageView(parent.context, ink).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            val holder = PageHolder(view)
            view.onTapAt = { x, y ->
                val page = holder.page
                if (page in pageSizes.indices && view.width > 0) {
                    val pointsPerPixel = pageSizes[page].first.toFloat() / view.width
                    onPageTap?.invoke(page, x * pointsPerPixel, y * pointsPerPixel)
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val viewWidth = pages.width.coerceAtLeast(1)
            val (pw, ph) = pageSizes[position]
            holder.image.layoutParams.height = (viewWidth.toLong() * ph / pw).toInt()
            holder.page = position
            holder.image.surface.page = position
            val renderWidth = min(viewWidth, MAX_RENDER_WIDTH)
            val cached = cache.get(key(position, renderWidth))
            if (cached != null) {
                holder.image.setImageBitmap(cached)
            } else {
                holder.image.setImageDrawable(null)
                render(holder, position, renderWidth, generation.get())
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.page = -1
            holder.image.setImageDrawable(null)
        }
    }
}
