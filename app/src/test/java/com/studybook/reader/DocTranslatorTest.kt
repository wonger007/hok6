package com.studybook.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which language a page is translated from. */
class DocTranslatorTest {
    @Test
    fun thePageDecidesTheLanguageNotTheLearner() {
        assertEquals("ko", DocTranslator.detectSourceLanguage("학교에 갑니다", "zh"))
        assertEquals("ja", DocTranslator.detectSourceLanguage("学校に行きます", "zh"))
        assertEquals("ja", DocTranslator.detectSourceLanguage("カタカナ", "ko"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("我哋去學校", "zh"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("我们去学校"))
        // A Korean sentence with hanja in it is still Korean.
        assertEquals("ko", DocTranslator.detectSourceLanguage("學校에 갑니다", "ja"))
    }

    @Test
    fun charactersAloneFollowTheLanguageBeingLearnt() {
        assertEquals("ja", DocTranslator.detectSourceLanguage("学校", "ja"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("學校", "ko"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("學校", "zh"))
    }

    @Test
    fun textInNoneOfTheScriptsFollowsTheLanguageBeingLearnt() {
        assertEquals("ko", DocTranslator.detectSourceLanguage("Lesson 1", "ko"))
        assertEquals("ja", DocTranslator.detectSourceLanguage("Lesson 1", "ja"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("Lesson 1", "zh"))
        assertEquals("zh", DocTranslator.detectSourceLanguage("", null))
    }

    /** Characters 10 points square on a line starting at [left], [top], each followed by the gap given with it. */
    private fun line(left: Float, top: Float, vararg chars: Pair<String, Float>): List<PdfGlyph> {
        var x = left
        return chars.map { (text, gap) -> PdfGlyph(text, x, top, x + 10, top + 10).also { x += 10 + gap } }
    }

    @Test
    fun scannedCharactersJoinUpAcrossLines() {
        val page = line(20f, 20f, "我" to 1f, "哋" to 1f, "去" to 1f) + line(20f, 34f, "學" to 1f, "校" to 1f)
        assertEquals("我哋去學校", DocTranslator.glyphText(page, "zh"))
        assertEquals("", DocTranslator.glyphText(emptyList(), "zh"))
    }

    @Test
    fun scannedWordsKeepTheirSpaces() {
        val page = line(20f, 20f, "학" to 1f, "교" to 1f, "에" to 6f, "갑" to 1f) + line(20f, 34f, "니" to 1f, "다" to 1f)
        assertEquals("학교에 갑 니다", DocTranslator.glyphText(page, "ko"))
        // Characters written down the page, each under the last, are one sentence.
        val column = listOf(PdfGlyph("学", 50f, 10f, 60f, 20f), PdfGlyph("校", 50f, 21f, 60f, 31f))
        assertEquals("学校", DocTranslator.glyphText(column, "ja"))
    }
}
