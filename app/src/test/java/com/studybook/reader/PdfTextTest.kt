package com.studybook.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finding the character under a tap on a PDF page (boxes are in PDF points from the top-left). */
class PdfTextTest {
    // "第一課 A." laid out on one line, 20pt characters.
    private val line = listOf(
        PdfGlyph("第", 100f, 50f, 120f, 70f),
        PdfGlyph("一", 120f, 50f, 140f, 70f),
        PdfGlyph("課", 140f, 50f, 160f, 70f),
        PdfGlyph("A", 170f, 50f, 180f, 70f),
        PdfGlyph(".", 180f, 50f, 184f, 70f),
        PdfGlyph("課", 300f, 200f, 320f, 220f),
    )

    @Test
    fun tapInsideACharacterFindsIt() {
        assertEquals("一", PdfText.glyphAt(line, 130f, 60f, slop = 6f)?.text)
        assertEquals("第", PdfText.glyphAt(line, 101f, 69f, slop = 6f)?.text)
    }

    @Test
    fun tapJustOutsideACharacterStillFindsTheNearest() {
        assertEquals("課", PdfText.glyphAt(line, 163f, 60f, slop = 6f)?.text)
    }

    @Test
    fun tapOnLatinTextSelectsNothing() {
        // "A" is never offered, and the nearest Chinese character (課) is too far away to be meant.
        assertNull(PdfText.glyphAt(line, 175f, 60f, slop = 6f))
    }

    @Test
    fun tapFarFromAnyCharacterFindsNothing() {
        assertNull(PdfText.glyphAt(line, 500f, 500f, slop = 6f))
        assertNull(PdfText.glyphAt(emptyList(), 10f, 10f, slop = 6f))
    }

    @Test
    fun pageCharactersAreChineseOnlyInReadingOrderWithoutRepeats() {
        assertEquals(listOf("第", "一", "課"), PdfText.practiceCharacters(line))
    }

    @Test
    fun multiCharacterGlyphTextIsSplit() {
        val glyphs = listOf(PdfGlyph("你好", 0f, 0f, 40f, 20f), PdfGlyph("你", 40f, 0f, 60f, 20f))
        assertEquals(listOf("你", "好"), PdfText.practiceCharacters(glyphs))
    }
}
