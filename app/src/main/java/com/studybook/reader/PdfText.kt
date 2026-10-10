package com.studybook.reader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.max

/** One character on a PDF page; the box is in PDF points measured from the page's top-left corner. */
class PdfGlyph(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The characters of each page of a PDF, so a long-press on a rendered page can find the character under the finger. */
object PdfText {
    @Volatile private var initialised = false

    /** Reads every page of the PDF. Slow for big files, so call it off the main thread. */
    fun load(context: Context, uri: Uri): List<List<PdfGlyph>> {
        if (!initialised) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { stream ->
            PDDocument.load(stream).use { doc ->
                (1..doc.numberOfPages).map { page -> glyphs(doc, page) }
            }
        }
    }

    private fun glyphs(doc: PDDocument, page: Int): List<PdfGlyph> {
        val out = ArrayList<PdfGlyph>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (p in textPositions) {
                    val size = max(p.widthDirAdj, p.heightDir)
                    out += PdfGlyph(p.unicode ?: continue, p.xDirAdj, p.yDirAdj - size, p.xDirAdj + p.widthDirAdj, p.yDirAdj)
                }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(doc)
        return out
    }

    /** The characters to practise on a page (see [isPracticeChar]) in reading order, without repeats. */
    fun practiceCharacters(glyphs: List<PdfGlyph>): List<String> =
        glyphs.flatMap { g -> g.text.codePoints().toArray().map { String(Character.toChars(it)) } }
            .filter(::isPracticeChar)
            .distinct()

    /** The character whose box contains (or is nearest to) the point, if one is close enough. */
    fun glyphAt(glyphs: List<PdfGlyph>, x: Float, y: Float, slop: Float): PdfGlyph? =
        glyphs.filter { g -> g.text.codePoints().anyMatch { isPracticeChar(String(Character.toChars(it))) } }
            .minByOrNull { g ->
                val dx = max(0f, max(g.left - x, x - g.right))
                val dy = max(0f, max(g.top - y, y - g.bottom))
                dx * dx + dy * dy
            }
            ?.takeIf { g -> x >= g.left - slop && x <= g.right + slop && y >= g.top - slop && y <= g.bottom + slop }
}

/** Whether a character can be practised: Han characters (Chinese, kanji, hanja), kana and hangul. */
fun isPracticeChar(ch: String): Boolean = when (Character.UnicodeScript.of(ch.codePointAt(0))) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}
