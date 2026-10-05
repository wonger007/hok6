package com.studybook.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.HorizontalScrollView

/**
 * Horizontal scroller whose single child is measured at [contentWidth] (or the viewport width when 0),
 * so a vertically scrolling page list can be made wider than the screen and panned sideways.
 * Also reports pinch gestures, swallowing the touch stream from children while a pinch is going on.
 */
class ZoomPanView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {

    interface PinchListener {
        fun onPinch(scale: Float, focusX: Float, focusY: Float)
        fun onPinchEnd(scale: Float, focusX: Float, focusY: Float)
        fun onPan(dx: Float, dy: Float)
    }

    var contentWidth = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var pinchListener: PinchListener? = null

    private var scale = 1f
    private var focusX = 0f
    private var focusY = 0f
    private var swallowing = false
    private var lastX = 0f
    private var lastY = 0f

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scale = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale *= detector.scaleFactor
            focusX = detector.focusX
            focusY = detector.focusY
            pinchListener?.onPinch(scale, focusX, focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinchListener?.onPinchEnd(scale, focusX, focusY)
        }
    })

    init {
        isFillViewport = false
        // Double-tap-and-drag zoom would hijack quick successive pen strokes.
        detector.isQuickScaleEnabled = false
    }

    override fun measureChildWithMargins(
        child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int,
    ) {
        val viewport = MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight
        val width = if (contentWidth > 0) contentWidth else viewport
        val heightSpec = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height)
        child.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY), heightSpec)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallowing = false
        detector.onTouchEvent(ev)
        if (!swallowing && (ev.pointerCount > 1 || detector.isInProgress)) {
            swallowing = true
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (swallowing) {
            // Two-finger drag scrolls, so the page can be moved while tracing with one finger.
            val skip = if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1
            var sx = 0f
            var sy = 0f
            var n = 0
            for (i in 0 until ev.pointerCount) if (i != skip) {
                sx += ev.getX(i)
                sy += ev.getY(i)
                n++
            }
            if (n > 0) {
                sx /= n
                sy /= n
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && n > 1) pinchListener?.onPan(lastX - sx, lastY - sy)
                lastX = sx
                lastY = sy
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
