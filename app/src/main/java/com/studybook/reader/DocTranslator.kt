package com.studybook.reader

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation (Google ML Kit) to English for textbook instructions and doc content.
 * Keeps user data strictly on device and downloads language models on-demand.
 */
object DocTranslator {
    private val translators = ConcurrentHashMap<String, Translator>()

    /**
     * The language to translate [text] from, by what it is written in: Korean when it has hangul, Japanese when it
     * has kana, else Chinese. Text in characters alone could be any of them, so it follows [defaultLang] (the script
     * of the language being learnt: "zh", "ja" or "ko") when that is Japanese; Korean books' characters read as
     * Chinese. Text with none of these scripts follows [defaultLang] too.
     */
    fun detectSourceLanguage(text: String, defaultLang: String? = null): String {
        var hangulCount = 0
        var kanaCount = 0
        var hanCount = 0

        for (cp in text.codePoints().toArray()) {
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HANGUL -> hangulCount++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kanaCount++
                Character.UnicodeScript.HAN -> hanCount++
                else -> {}
            }
        }

        return when {
            hangulCount > 0 && hangulCount >= kanaCount -> TranslateLanguage.KOREAN
            kanaCount > 0 -> TranslateLanguage.JAPANESE
            defaultLang == "ja" -> TranslateLanguage.JAPANESE
            hanCount > 0 -> TranslateLanguage.CHINESE
            defaultLang == "ko" -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.CHINESE
        }
    }

    private fun getTranslator(sourceLanguage: String): Translator {
        return translators.getOrPut(sourceLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }
    }

    /** [text] in English, translated from the language it is written in ([detectSourceLanguage]). */
    suspend fun translateToEnglish(text: String, defaultLang: String? = null): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val lang = detectSourceLanguage(trimmed, defaultLang)
        val translator = getTranslator(lang)
        val conditions = DownloadConditions.Builder().build()

        // Ensure model is downloaded
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        // Translate text
        return suspendCancellableCoroutine { cont ->
            translator.translate(trimmed)
                .addOnSuccessListener { translated -> cont.resume(translated) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    fun close() {
        for (translator in translators.values) {
            runCatching { translator.close() }
        }
        translators.clear()
    }
}
