package com.studybook.reader

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray

/**
 * Test build only: plays a gesture with any number of fingers into the open screen, through the same path as real
 * touches, for checks that adb's one-finger `input` can't do (pinch, two-finger scroll). Sent from tools/ with
 *
 *   adb shell am broadcast -n com.studybook.reader.debug/com.studybook.reader.TestGestures --es frames '<json>'
 *
 * where the JSON is a list of frames, [waitMs, [[x, y] or null, …]]: one entry per finger, in screen pixels, null
 * when that finger isn't touching.
 */
class TestGestures : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val frames = JSONArray(intent.getStringExtra("frames") ?: return)
        val main = Handler(Looper.getMainLooper())
        val downTime = SystemClock.uptimeMillis()
        var at = 0L
        var previous = arrayOfNulls<FloatArray>(0)
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONArray(i)
            at += frame.getLong(0)
            val fingers = frame.getJSONArray(1)
            val now = Array(fingers.length()) { f ->
                fingers.optJSONArray(f)?.let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
            }
            val before = previous
            main.postDelayed({ play(downTime, before, now) }, at)
            previous = now
        }
    }

    /** Sends the events that take the fingers from [before] to [now]: fingers lifting, moving, then touching down. */
    private fun play(downTime: Long, before: Array<FloatArray?>, now: Array<FloatArray?>) {
        val window = resumed?.window ?: return
        val decor = window.decorView
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val count = maxOf(before.size, now.size)
        val down = BooleanArray(count) { before.getOrNull(it) != null }
        val pos = Array(count) { before.getOrNull(it) ?: now.getOrNull(it) ?: floatArrayOf(0f, 0f) }
        fun send(action: Int, index: Int = 0) {
            val ids = (0 until count).filter { down[it] }
            if (ids.isEmpty()) return
            val props = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = ids.map { id ->
                MotionEvent.PointerCoords().apply { x = pos[id][0] - origin[0]; y = pos[id][1] - origin[1]; pressure = 1f; size = 0.1f }
            }
            val masked = action or (ids.indexOf(index).coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), masked, ids.size, props.toTypedArray(),
                coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            decor.dispatchTouchEvent(ev)
            ev.recycle()
        }
        // Lift first, with the positions they had.
        for (id in 0 until count) if (down[id] && now.getOrNull(id) == null) {
            val last = (0 until count).count { down[it] } == 1
            send(if (last) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id)
            down[id] = false
        }
        // Move the fingers still down.
        var moved = false
        for (id in 0 until count) if (down[id]) now.getOrNull(id)?.let { if (!it.contentEquals(pos[id])) { pos[id] = it; moved = true } }
        if (moved) send(MotionEvent.ACTION_MOVE)
        // Then new fingers.
        for (id in 0 until count) if (!down[id] && now.getOrNull(id) != null) {
            pos[id] = now[id]!!
            val first = (0 until count).none { down[it] }
            down[id] = true
            send(if (first) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
        }
    }

    companion object {
        @Volatile var resumed: Activity? = null
    }

    /** Starts with the app, to know which screen is open. */
    class Starter : ContentProvider() {
        override fun onCreate(): Boolean {
            (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { resumed = activity }
                override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            return true
        }

        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }
}
