package com.studybook.reader

import com.studybook.reader.DocxReader.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxReaderTest {
    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(body: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document $w><w:body>$body</w:body></w:document>".toByteArray())
        }
        return bytes.toByteArray()
    }

    private fun read(body: String) = DocxReader.read(ByteArrayInputStream(docx(body)), KXmlParser())

    private fun DocxReader.DocxText.styledText(style: Style) =
        spans.filter { it.style == style }.map { text.substring(it.start, it.end) }

    @Test
    fun paragraphsBecomeLines() {
        val doc = read("<w:p><w:r><w:t>第一課</w:t></w:r></w:p><w:p><w:r><w:t>你好</w:t></w:r></w:p>")
        assertEquals("第一課\n你好\n", doc.text)
    }

    @Test
    fun boldItalicAndUnderlineRunsAreKept() {
        val doc = read(
            "<w:p><w:r><w:t xml:space=\"preserve\">Normal </w:t></w:r>" +
                "<w:r><w:rPr><w:b/></w:rPr><w:t>粗體</w:t></w:r>" +
                "<w:r><w:rPr><w:i/></w:rPr><w:t>italic</w:t></w:r>" +
                "<w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t>under</w:t></w:r>" +
                "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t>notbold</w:t></w:r></w:p>"
        )
        assertEquals("Normal 粗體italicundernotbold\n", doc.text)
        assertEquals(listOf("粗體"), doc.styledText(Style.Bold))
        assertEquals(listOf("italic"), doc.styledText(Style.Italic))
        assertEquals(listOf("under"), doc.styledText(Style.Underline))
    }

    @Test
    fun headingsAreBoldAndLarger() {
        val doc = read("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>第一課 你好</w:t></w:r></w:p>")
        assertEquals(listOf("第一課 你好"), doc.styledText(Style.Bold))
        assertTrue(doc.spans.any { it.style == Style.Size(1.5f) })
    }

    @Test
    fun rubyPinyinFollowsItsCharacter() {
        val doc = read(
            "<w:p><w:r><w:ruby><w:rt><w:r><w:t>nǐ</w:t></w:r></w:rt>" +
                "<w:rubyBase><w:r><w:t>你</w:t></w:r></w:rubyBase></w:ruby></w:r></w:p>"
        )
        assertEquals("你(nǐ)\n", doc.text)
        assertEquals(listOf("(nǐ)"), doc.styledText(Style.Annotation))
    }

    @Test
    fun tableRowsBecomeLinesWithCellsSideBySide() {
        val doc = read(
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>漢字</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Pinyin</w:t></w:r></w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:p><w:r><w:t>謝謝</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>xièxie</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        )
        val lines = doc.text.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("漢字") && lines[0].contains("Pinyin"))
        assertTrue(lines[1].startsWith("謝謝") && lines[1].contains("xièxie"))
    }

    @Test
    fun fontSizeBecomesRelativeSize() {
        val doc = read("<w:p><w:r><w:rPr><w:sz w:val=\"44\"/></w:rPr><w:t>大字</w:t></w:r></w:p>")
        assertEquals(listOf(Style.Size(2f)), doc.spans.map { it.style })
    }

    @Test(expected = IOException::class)
    fun notAWordDocumentIsRejected() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("other.txt")); it.write(1) }
        DocxReader.read(ByteArrayInputStream(bytes.toByteArray()), KXmlParser())
    }
}
