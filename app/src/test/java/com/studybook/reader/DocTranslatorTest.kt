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
}
