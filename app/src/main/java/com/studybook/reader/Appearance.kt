package com.studybook.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Hok6 itself: applies the chosen light or dark look before any screen opens. */
class HokApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.apply(this)
    }
}

/** Light or dark: the same as the tablet (the default), or always one of them. */
object Appearance {
    private const val KEY = "appearance"
    private val MODES = listOf(
        "system" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        "light" to AppCompatDelegate.MODE_NIGHT_NO,
        "dark" to AppCompatDelegate.MODE_NIGHT_YES,
    )

    fun apply(context: Context) {
        val chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system")
        AppCompatDelegate.setDefaultNightMode(MODES.firstOrNull { it.first == chosen }?.second ?: MODES[0].second)
    }

    /** Asks which look to use; open screens change straight away. */
    fun choose(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = MODES.indexOfFirst { it.first == prefs.getString(KEY, "system") }.coerceAtLeast(0)
        val names = arrayOf(R.string.appearance_system, R.string.appearance_light, R.string.appearance_dark)
            .map { activity.getString(it) }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.appearance_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                prefs.edit().putString(KEY, MODES[which].first).apply()
                apply(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * Hok6's splash: the name, the tag line and a moving progress bar over the screen while it loads (see
 * splash_overlay.xml). [done] fades it out, after at least [minMs] so it doesn't just flash, and runs [onDone].
 */
class Splash(private val view: View, private val minMs: Long = 0, private val onDone: () -> Unit = {}) {
    private val shownAt = SystemClock.uptimeMillis()
    private var finished = false
    private val window = (view.context as? Activity)?.window
    /** The status bar matches the splash's red while it shows. */
    @Suppress("DEPRECATION")
    private val statusBarColor = window?.statusBarColor

    init {
        @Suppress("DEPRECATION")
        window?.statusBarColor = view.context.getColor(R.color.brand)
        view.alpha = 1f
        view.isVisible = true
        // Never in the way for long, even if loading gets stuck.
        view.postDelayed({ done() }, 5000)
    }

    fun done() {
        if (finished) return
        finished = true
        val wait = (shownAt + minMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        view.postDelayed({
            onDone()
            @Suppress("DEPRECATION")
            if (statusBarColor != null) window?.statusBarColor = statusBarColor
            view.animate().alpha(0f).setDuration(250).withEndAction { view.isVisible = false }.start()
        }, wait)
    }
}
