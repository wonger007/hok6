package com.studybook.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the text of a .docx file (word/document.xml) as styled text: headings, bold, italic,
 * underline, font size, tables (one row per line) and ruby annotations such as pinyin.
 * Images and complex layout are not shown; use "Open in another app" for those.
 */
object DocxReader {

    /** Text styles found in the document, as character ranges of [DocxText.text]. */
    sealed class Style {
        object Bold : Style()
        object Italic : Style()
        object Underline : Style()
        data class Size(val scale: Float) : Style()
        /** Ruby annotation (e.g. pinyin) shown small and grey after the characters it belongs to. */
        object Annotation : Style()
    }

    data class Span(val style: Style, val start: Int, val end: Int)

    data class DocxText(val text: String, val spans: List<Span>)

    fun read(context: Context, uri: Uri): CharSequence {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open file")
        return toStyledText(read(input, Xml.newPullParser()))
    }

    /** Reads a .docx stream with the given XML parser (Android's in the app, any XmlPullParser in tests). */
    fun read(input: InputStream, parser: XmlPullParser): DocxText {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return parse(zip, parser)
            }
        }
        throw IOException("not a Word (.docx) document")
    }

    private fun toStyledText(doc: DocxText): CharSequence {
        val out = SpannableStringBuilder(doc.text)
        for ((style, start, end) in doc.spans) {
            val what: Any = when (style) {
                Style.Bold -> StyleSpan(Typeface.BOLD)
                Style.Italic -> StyleSpan(Typeface.ITALIC)
                Style.Underline -> UnderlineSpan()
                is Style.Size -> RelativeSizeSpan(style.scale)
                Style.Annotation -> ForegroundColorSpan(Color.GRAY)
            }
            if (end > start) out.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (style == Style.Annotation && end > start) {
                out.setSpan(RelativeSizeSpan(0.6f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun parse(stream: InputStream, p: XmlPullParser): DocxText {
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(stream, null)

        val out = StringBuilder()
        val spans = ArrayList<Span>()
        fun span(style: Style, start: Int, end: Int) {
            if (end > start) spans += Span(style, start, end)
        }
        var paraStart = 0
        var heading = -1
        var inParaProps = false
        var inRunProps = false
        var inText = false
        var bold = false
        var italic = false
        var underline = false
        var halfPoints = 0
        var tableDepth = 0
        var inRubyText = false
        val rubyText = StringBuilder()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p" -> { paraStart = out.length; heading = -1 }
                    "pPr" -> inParaProps = true
                    "pStyle" -> if (inParaProps) heading = headingLevel(p.attr("val"))
                    "r" -> { bold = false; italic = false; underline = false; halfPoints = 0 }
                    "rPr" -> inRunProps = true
                    "b" -> if (inRunProps) bold = p.isOn()
                    "i" -> if (inRunProps) italic = p.isOn()
                    "u" -> if (inRunProps) underline = p.attr("val").let { it != null && it != "none" }
                    "sz" -> if (inRunProps) halfPoints = p.attr("val")?.toIntOrNull() ?: 0
                    "t" -> inText = true
                    "tab" -> if (!inParaProps) out.append('\t')
                    "br", "cr" -> out.append('\n')
                    "tbl" -> tableDepth++
                    "ruby" -> rubyText.setLength(0)
                    "rt" -> inRubyText = true
                }

                XmlPullParser.TEXT -> if (inText) {
                    val text = p.text
                    if (inRubyText) {
                        rubyText.append(text)
                    } else {
                        val start = out.length
                        out.append(text)
                        val end = out.length
                        if (bold) span(Style.Bold, start, end)
                        if (italic) span(Style.Italic, start, end)
                        if (underline) span(Style.Underline, start, end)
                        if (halfPoints > 0) span(Style.Size((halfPoints / 22f).coerceIn(0.6f, 3f)), start, end)
                    }
                }

                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "pPr" -> inParaProps = false
                    "rPr" -> inRunProps = false
                    "rt" -> inRubyText = false
                    "ruby" -> if (rubyText.isNotBlank()) {
                        val start = out.length
                        out.append("(").append(rubyText.trim()).append(")")
                        span(Style.Annotation, start, out.length)
                    }
                    "p" -> {
                        if (heading >= 0 && out.length > paraStart) {
                            span(Style.Bold, paraStart, out.length)
                            span(Style.Size(headingScale(heading)), paraStart, out.length)
                        }
                        out.append(if (tableDepth > 0) " " else "\n")
                    }
                    "tc" -> out.append("    ")
                    "tr" -> out.append('\n')
                    "tbl" -> { tableDepth--; out.append('\n') }
                }
            }
            event = p.next()
        }
        return DocxText(out.toString(), spans)
    }

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.isOn() = attr("val").let { it == null || it !in setOf("0", "false", "off") }

    private fun headingLevel(style: String?): Int = when {
        style == null -> -1
        style.equals("Title", ignoreCase = true) -> 0
        style.equals("Subtitle", ignoreCase = true) -> 2
        style.startsWith("Heading", ignoreCase = true) -> style.drop(7).toIntOrNull() ?: 1
        else -> -1
    }

    private fun headingScale(level: Int) = when (level) {
        0 -> 1.8f
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        else -> 1.05f
    }
}
