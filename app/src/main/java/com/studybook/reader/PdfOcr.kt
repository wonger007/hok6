package com.studybook.reader

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device text recognition (Google ML Kit) for scanned PDF pages that have no text layer: Chinese, Japanese or
 * Korean, following the languages being learned. Results use the same [PdfGlyph] boxes as real PDF text, so taps work
 * the same way.
 */
object PdfOcr {
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /**
     * Recognises the characters in [bitmap]; [pointsPerPixel] converts bitmap pixels to PDF points. With more than one
     * script among [languages], each is tried and the reading that finds the most characters to practise is kept.
     */
    suspend fun recognise(bitmap: Bitmap, pointsPerPixel: Float, languages: List<String>): List<PdfGlyph> {
        val recognizers = languages.map {
            when (it) {
                "ja" -> japanese
                "ko" -> korean
                else -> chinese
            }
        }.distinct()
        return recognizers.map { recognise(it, bitmap, pointsPerPixel) }
            .maxByOrNull { glyphs -> PdfText.practiceCharacters(glyphs).size }.orEmpty()
    }

    private suspend fun recognise(recognizer: TextRecognizer, bitmap: Bitmap, pointsPerPixel: Float): List<PdfGlyph> =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text -> cont.resume(toGlyphs(text, pointsPerPixel)) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private fun toGlyphs(text: Text, scale: Float): List<PdfGlyph> {
        val out = ArrayList<PdfGlyph>()
        for (block in text.textBlocks) for (line in block.lines) for (element in line.elements) {
            val symbols = element.symbols
            if (symbols.isNotEmpty()) {
                for (s in symbols) {
                    val box = s.boundingBox ?: continue
                    out += PdfGlyph(s.text, box.left * scale, box.top * scale, box.right * scale, box.bottom * scale)
                }
            } else {
                // No per-character boxes: share the element's box out evenly between its characters.
                val box = element.boundingBox ?: continue
                val chars = element.text.codePoints().toArray().map { String(Character.toChars(it)) }
                val w = box.width().toFloat() / chars.size.coerceAtLeast(1)
                chars.forEachIndexed { i, ch ->
                    val left = box.left + i * w
                    out += PdfGlyph(ch, left * scale, box.top * scale, (left + w) * scale, box.bottom * scale)
                }
            }
        }
        return out
    }
}
