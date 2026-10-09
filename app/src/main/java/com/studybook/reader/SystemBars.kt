package com.studybook.reader

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Android 15 and later draw an app under the status and navigation bars once it targets them. This keeps Hok6's
 * screens where they always were: each one is inset by the bars (and by the keyboard, for screens that resize for
 * it), with the status bar strip painted red as before. Older Android versions are left alone.
 */
object SystemBars {
    fun install(app: Application) {
        if (Build.VERSION.SDK_INT < 35) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = fit(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Colours the status bar: [Activity.getWindow]'s own colour on older Android, the painted strip on 15+. */
    fun setColor(activity: Activity, color: Int) {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = color
        val decor = activity.window.decorView
        decor.setTag(R.id.status_bar_color, color)
        (decor.getTag(R.id.status_bar_strip) as? Strip)?.color = color
    }

    /** The status bar's current colour. */
    fun color(activity: Activity): Int =
        activity.window.decorView.getTag(R.id.status_bar_color) as? Int ?: themeColor(activity)

    private fun fit(activity: Activity) {
        val decor = activity.window.decorView as ViewGroup
        // The window's top view holding the title bar and the screen itself.
        var root: View = activity.findViewById(android.R.id.content) ?: return
        while (root.parent !== decor) root = root.parent as? View ?: return
        val strip = Strip(color(activity))
        decor.setTag(R.id.status_bar_strip, strip)
        root.background = strip
        val resizes = activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST ==
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = if (resizes) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            val bottom = max(bars.bottom, keyboard)
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            strip.height = bars.top
            // Pass on what's left rather than consuming them, for the title bar below.
            insets.inset(bars.left, bars.top, bars.right, bottom)
        }
        // Drawn edge to edge, AppCompat's title bar no longer pushes the screen down; it sends its own height to
        // the screen as an inset instead (nothing on screens without one).
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(android.R.id.content)) { view, insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bar.left, bar.top, bar.right, bar.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun themeColor(activity: Activity): Int {
        val a = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.statusBarColor))
        return try { a.getColor(0, activity.getColor(R.color.bar_status)) } finally { a.recycle() }
    }

    /** Paints the top [height] pixels (behind the status bar); the rest stays clear so the screen shows as before. */
    private class Strip(color: Int) : Drawable() {
        private val paint = Paint().apply { this.color = color }
        var color: Int
            get() = paint.color
            set(value) { paint.color = value; invalidateSelf() }
        var height = 0
            set(value) { field = value; invalidateSelf() }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), height.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
