package com.studybook.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import java.util.Locale

/** The Chinese voices Hok6 speaks with, best first: [lang] "yue" (Cantonese) or "cmn" (Mandarin). */
object Voices {
    const val GOOGLE_TTS = "com.google.android.tts"
    private const val KEY_ENGINE = "voice_engine"
    private const val KEY_NOTICED = "voice_engine_noticed"

    fun hasGoogle(context: Context) = runCatching { context.packageManager.getPackageInfo(GOOGLE_TTS, 0) }.isSuccess

    /** Whether Hok6 speaks with Google's engine (the default) rather than the tablet's preferred one. */
    fun useGoogle(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, "google") != "device"

    fun setUseGoogle(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_ENGINE, if (on) "google" else "device").apply()

    /**
     * The engine Hok6 speaks with: Google's when it's installed, so Cantonese works without changing the tablet's
     * preferred engine (Samsung's often has none), unless the tablet's own was chosen in Settings.
     */
    fun engine(context: Context): String? = when {
        useGoogle(context) && hasGoogle(context) -> GOOGLE_TTS
        else -> tabletEngine(context)
    }

    /** The tablet's preferred voice engine, as set in Android's voice settings. */
    fun tabletEngine(context: Context): String? = Settings.Secure.getString(context.contentResolver, "tts_default_synth")

    /** An engine's name as the tablet shows it, e.g. "Samsung text-to-speech engine". */
    fun label(context: Context, engine: String?): String {
        val pm = context.packageManager
        return engine?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
            ?: context.getString(R.string.voice_engine_device)
    }

    /** Whether [noticeSwitch] still has something to say. */
    fun needsNotice(context: Context) =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTICED, false) &&
            engine(context) == GOOGLE_TTS && tabletEngine(context).let { it != null && it != GOOGLE_TTS }

    /**
     * Once: when Hok6 speaks with Google's engine while the tablet prefers another (often Samsung's), says so and
     * offers the tablet's instead. [then] runs once it's closed (or straight away when there's nothing to say).
     */
    fun noticeSwitch(activity: AppCompatActivity, then: () -> Unit = {}) {
        if (!needsNotice(activity) || activity.isFinishing) return then()
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTICED, true).apply()
        val google = label(activity, GOOGLE_TTS)
        val tablet = label(activity, tabletEngine(activity))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.voice_switch_title)
            .setMessage(activity.getString(R.string.voice_switch, google, tablet))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.voice_switch_use) { _, _ -> setUseGoogle(activity, false) }
            .setOnDismissListener { then() }
            .show()
    }

    /** Starts the voice engine Hok6 speaks with ([engine]). */
    fun open(context: Context, onInit: TextToSpeech.OnInitListener): TextToSpeech =
        engine(context)?.let { TextToSpeech(context, onInit, it) } ?: TextToSpeech(context, onInit)

    fun locales(lang: String) =
        if (lang == "yue") listOf(Locale("yue", "HK"), Locale("zh", "HK"))
        else listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)

    fun find(engine: TextToSpeech, lang: String) =
        locales(lang).firstOrNull { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }

    /**
     * Whether [lang] can be spoken without the internet. Google's engine reports a language as available when it can
     * stream it, so this looks for a voice that is installed on the device. Engines that don't list voices count as
     * installed.
     */
    fun installed(engine: TextToSpeech, lang: String): Boolean {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return true
        val wanted = locales(lang)
        return voices.any { v ->
            wanted.any { it.language == v.locale.language && it.country == v.locale.country } &&
                !v.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
        }
    }

    /**
     * Opens the screen where voices can be added: Google's voice download (when Hok6 speaks with Google's engine),
     * Android's voice settings (when it uses the tablet's own engine), or the Play Store (to get Google's).
     * Android doesn't let apps install voices themselves.
     */
    fun install(activity: AppCompatActivity) {
        val hasGoogle = hasGoogle(activity)
        val (intent, tip) = when {
            !hasGoogle -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TTS")) to R.string.voice_get_google
            engine(activity) != GOOGLE_TTS -> Intent("com.android.settings.TTS_SETTINGS") to R.string.voice_pick_google
            else -> Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(GOOGLE_TTS) to R.string.voice_pick_voices
        }
        val fallback = if (hasGoogle) Intent("com.android.settings.TTS_SETTINGS")
            else Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_TTS"))
        Toast.makeText(activity, tip, Toast.LENGTH_LONG).show()
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { activity.startActivity(fallback) }
        }
    }
}

/**
 * What Hok6 needs from the internet, and whether to ask for it. The dictionary and character data are inside the app;
 * only the handwriting models (downloaded here) and the voices (added in Android's settings) come from outside.
 */
object Downloads {
    val LANGS = listOf("yue", "cmn")
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_NO_REMIND = "no_download_reminders"

    enum class Need { HANDWRITING, VOICE }

    /** Handwriting downloads under way, by language, with who to tell when each ends (null error = done). */
    private val downloading = HashMap<String, MutableList<(Throwable?) -> Unit>>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the welcome screen has been through (finished or skipped). */
    fun setupDone(context: Context) = prefs(context).getBoolean(KEY_SETUP_DONE, false)
    fun setSetupDone(context: Context) = prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()

    /** Whether to offer a download when a feature needs one; off once the user ticks "Don't remind me again". */
    fun remind(context: Context) = !prefs(context).getBoolean(KEY_NO_REMIND, false)
    fun setRemind(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_NO_REMIND, !on).apply()

    fun isDownloading(lang: String) = lang in downloading

    /** Calls [onReady] with whether the handwriting model for [lang] is on the device. */
    fun handwritingReady(lang: String, onReady: (Boolean) -> Unit) {
        val model = Handwriting.model(lang) ?: return onReady(false)
        RemoteModelManager.getInstance().isModelDownloaded(model)
            .addOnSuccessListener { onReady(it) }
            .addOnFailureListener { onReady(false) }
    }

    /** Downloads the handwriting model for [lang] (once, however many ask); [onDone] gets null or the error. */
    fun downloadHandwriting(lang: String, onDone: (Throwable?) -> Unit = {}) {
        downloading[lang]?.let { it += onDone; return }
        val model = Handwriting.model(lang) ?: return onDone(IllegalStateException("no handwriting model for Chinese"))
        downloading[lang] = mutableListOf(onDone)
        fun finish(error: Throwable?) = downloading.remove(lang)?.forEach { it(error) }
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener { finish(null) }
            .addOnFailureListener { finish(it) }
    }

    /**
     * When a feature finds [need] missing: asks whether to get it now, with "Don't remind me again". [onDownload]
     * runs if the user says yes. Does nothing once reminders are off.
     */
    fun ask(activity: AppCompatActivity, need: Need, lang: String, onDownload: () -> Unit) {
        if (!remind(activity) || activity.isFinishing) return
        val language = activity.getString(if (lang == "yue") R.string.cantonese else R.string.mandarin)
        val (title, message, button) = when (need) {
            Need.HANDWRITING -> Triple(R.string.ask_hand_title, R.string.ask_hand, R.string.ask_download)
            Need.VOICE -> Triple(R.string.ask_voice_title, R.string.ask_voice, R.string.ask_add_voice)
        }
        val box = CheckBox(activity).apply { setText(R.string.ask_never) }
        val pad = activity.dp(20)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(title, language))
            .setMessage(activity.getString(message, language))
            .setView(LinearLayout(activity).apply { setPadding(pad, activity.dp(8), pad, 0); addView(box) })
            .setPositiveButton(button) { _, _ ->
                if (box.isChecked) setRemind(activity, false)
                onDownload()
            }
            .setNegativeButton(R.string.ask_not_now) { _, _ -> if (box.isChecked) setRemind(activity, false) }
            .show()
    }
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/**
 * The list of what Hok6 needs, each with whether it's ready, shown in [container] (on the welcome screen and in
 * Settings). [downloadAll] gets every missing piece. It checks again when the user comes back from Android's
 * voice settings or the Play Store. [onChange] hears whether all is ready, whether it's still checking what's on
 * the device, and whether a download is running.
 */
class DownloadList(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val onChange: (allReady: Boolean, checking: Boolean, downloading: Boolean) -> Unit = { _, _, _ -> },
) : DefaultLifecycleObserver {
    private enum class State { CHECKING, MISSING, ONLINE, DOWNLOADING, READY, FAILED }

    private val hand = Downloads.LANGS.associateWith { State.CHECKING }.toMutableMap()
    private val voice = Downloads.LANGS.associateWith { State.CHECKING }.toMutableMap()
    private var tts: TextToSpeech? = null

    init {
        activity.lifecycle.addObserver(this)
        render()
    }

    val allReady get() = (hand.values + voice.values).all { it == State.READY }
    private val checking get() = (hand.values + voice.values).any { it == State.CHECKING }
    private val downloading get() = hand.values.any { it == State.DOWNLOADING }

    override fun onResume(owner: LifecycleOwner) = check()

    override fun onDestroy(owner: LifecycleOwner) {
        tts?.shutdown()
        tts = null
    }

    fun check() {
        for (lang in Downloads.LANGS) {
            if (Downloads.isDownloading(lang)) {
                hand[lang] = State.DOWNLOADING
                Downloads.downloadHandwriting(lang) { done(lang, it) }
                continue
            }
            Downloads.handwritingReady(lang) { ok ->
                if (hand[lang] != State.DOWNLOADING) hand[lang] = if (ok) State.READY else State.MISSING
                render()
            }
        }
        // A new engine each time, so voices added meanwhile are seen.
        tts?.shutdown()
        Downloads.LANGS.forEach { voice[it] = State.CHECKING }
        var engineHere: TextToSpeech? = null
        engineHere = Voices.open(activity) { status ->
            val e = engineHere ?: return@open
            if (e !== tts) return@open
            for (lang in Downloads.LANGS) {
                voice[lang] = when {
                    status != TextToSpeech.SUCCESS || Voices.find(e, lang) == null -> State.MISSING
                    Voices.installed(e, lang) -> State.READY
                    else -> State.ONLINE
                }
            }
            render()
        }
        tts = engineHere
        render()
    }

    /** Downloads the missing handwriting models, then opens the voice screen if a voice is missing. */
    fun downloadAll() {
        for (lang in Downloads.LANGS) {
            if (hand[lang] != State.MISSING && hand[lang] != State.FAILED) continue
            hand[lang] = State.DOWNLOADING
            Downloads.downloadHandwriting(lang) { done(lang, it) }
        }
        render()
        if (voice.values.any { it == State.MISSING || it == State.ONLINE }) Voices.install(activity)
    }

    private fun done(lang: String, error: Throwable?) {
        hand[lang] = if (error == null) State.READY else State.FAILED
        if (!activity.isDestroyed) render()
    }

    private fun render() {
        container.removeAllViews()
        row(State.READY, R.string.need_dictionary, R.string.need_built_in)
        row(State.READY, R.string.need_characters, R.string.need_built_in)
        row(hand["yue"], R.string.need_hand_yue, handNote(hand["yue"]))
        row(hand["cmn"], R.string.need_hand_cmn, handNote(hand["cmn"]))
        row(voice["yue"], R.string.need_voice_yue, voiceNote(voice["yue"]))
        row(voice["cmn"], R.string.need_voice_cmn, voiceNote(voice["cmn"]))
        onChange(allReady, checking, downloading)
    }

    private fun handNote(state: State?) = when (state) {
        State.READY -> R.string.need_ready
        State.DOWNLOADING -> R.string.need_downloading
        State.FAILED -> R.string.need_failed
        State.MISSING -> R.string.need_hand_missing
        else -> R.string.need_checking
    }

    private fun voiceNote(state: State?) = when (state) {
        State.READY -> R.string.need_ready
        State.MISSING -> R.string.need_voice_missing
        State.ONLINE -> R.string.need_voice_online
        else -> R.string.need_checking
    }

    private fun row(state: State?, title: Int, note: Int) {
        val mark = when (state) {
            State.READY -> "✅"
            State.DOWNLOADING -> "⏳"
            State.MISSING, State.ONLINE -> "⬇️"
            State.FAILED -> "⚠️"
            else -> "…"
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(6), 0, activity.dp(6))
        }
        row.addView(TextView(activity).apply {
            text = mark
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(title)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            })
            addView(TextView(activity).apply {
                setText(note)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_small))
                alpha = 0.7f
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)
    }
}
