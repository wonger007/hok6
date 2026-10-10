package com.studybook.reader

import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import org.json.JSONArray

/**
 * Handwritten Chinese → characters, with ML Kit Digital Ink Recognition (on device). The recognition model for the
 * language is downloaded once (see [Downloads]), then works offline. Recognition doesn't download it by itself:
 * it answers [Result.Missing], and the screen offers the download.
 */
class Handwriting {
    sealed interface Result {
        class Candidates(val texts: List<String>) : Result
        /** The model is being downloaded; recognition follows when it's done. */
        object Downloading : Result
        /** The model isn't on the device and isn't being downloaded. */
        object Missing : Result
        class Failed(val message: String) : Result
    }

    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val ready = HashSet<String>()

    /**
     * [strokes] is `[[x, y, t, x, y, t, …], …]` (t in milliseconds) on a writing area [width] × [height];
     * [lang] "yue" (traditional, Hong Kong), "cmn" (simplified), "ja" or "ko". Calls [onResult] on the main thread.
     */
    fun recognize(lang: String, strokes: JSONArray, width: Float, height: Float, onResult: (Result) -> Unit) {
        val model = model(lang) ?: return onResult(Result.Failed("handwriting recognition isn't available for this language"))
        val tag = model.modelIdentifier.languageTag
        if (tag in ready) return run(tag, model, strokes, width, height, onResult)
        if (Downloads.isDownloading(lang)) {
            onResult(Result.Downloading)
            Downloads.downloadHandwriting(lang) { error ->
                if (error == null) recognize(lang, strokes, width, height, onResult)
                else onResult(Result.Failed("download: ${error.message}"))
            }
            return
        }
        Downloads.handwritingReady(lang) { downloaded ->
            if (downloaded) {
                ready += tag
                run(tag, model, strokes, width, height, onResult)
            } else onResult(Result.Missing)
        }
    }

    private fun run(tag: String, model: DigitalInkRecognitionModel, strokes: JSONArray, width: Float, height: Float,
                    onResult: (Result) -> Unit) {
        val recognizer = recognizers.getOrPut(tag) {
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        }
        val ink = Ink.builder()
        for (i in 0 until strokes.length()) {
            val p = strokes.getJSONArray(i)
            val stroke = Ink.Stroke.builder()
            var j = 0
            while (j + 2 < p.length()) {
                stroke.addPoint(Ink.Point.create(p.getDouble(j).toFloat(), p.getDouble(j + 1).toFloat(), p.getLong(j + 2)))
                j += 3
            }
            ink.addStroke(stroke.build())
        }
        if (ink.isEmpty) return onResult(Result.Candidates(emptyList()))
        val context = RecognitionContext.builder().setPreContext("").setWritingArea(WritingArea(width, height)).build()
        recognizer.recognize(ink.build(), context)
            .addOnSuccessListener { result -> onResult(Result.Candidates(result.candidates.map { it.text }.distinct())) }
            .addOnFailureListener { onResult(Result.Failed(it.message ?: it.javaClass.simpleName)) }
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    companion object {
        /** Models to try, best first: Hong Kong traditional characters for Cantonese, simplified for Mandarin; Japanese
         *  (kanji and kana) and Korean (hangul and hanja) have one each. */
        private fun tags(lang: String) = when (lang) {
            "cmn" -> listOf("zh-Hani-CN", "zh-Hani", "zh-Hani-TW")
            "ja" -> listOf("ja")
            "ko" -> listOf("ko")
            else -> listOf("zh-Hani-HK", "zh-Hani-TW", "zh-Hani")
        }

        fun model(lang: String): DigitalInkRecognitionModel? = tags(lang).firstNotNullOfOrNull { tag ->
            runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()
        }?.let { DigitalInkRecognitionModel.builder(it).build() }
    }
}
