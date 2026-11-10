package com.studybook.reader

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView no taller than its `android:maxHeight`, which a plain ScrollView ignores: longer content scrolls. */
class MaxHeightScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    private val maxHeight: Int

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        maxHeight = a.getDimensionPixelSize(0, 0)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (maxHeight <= 0 || (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && size <= maxHeight)) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}
