package com.studybook.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortingAndTypesTest {
    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun chaptersSortByNumberNotText() {
        assertEquals(
            listOf("Chapter_1", "Chapter_2", "Chapter_9", "Chapter_10"),
            sorted("Chapter_10", "Chapter_2", "Chapter_1", "Chapter_9"),
        )
    }

    @Test
    fun sortingIgnoresCaseAndLeadingZeros() {
        assertEquals(listOf("chapter 1", "Chapter 02", "CHAPTER 3"), sorted("CHAPTER 3", "chapter 1", "Chapter 02"))
    }

    @Test
    fun filesWithinAChapterSortNaturally() {
        assertEquals(
            listOf("K1 Ch.9 Homework July 2022.pdf", "K1 Ch.10 Homework July 2022.pdf", "K1_Ch09.mp3"),
            sorted("K1_Ch09.mp3", "K1 Ch.10 Homework July 2022.pdf", "K1 Ch.9 Homework July 2022.pdf"),
        )
    }

    @Test
    fun chineseNamesSortWithoutCrashing() {
        assertEquals(listOf("第1課", "第2課", "第10課"), sorted("第10課", "第2課", "第1課"))
    }

    @Test
    fun fileKindsComeFromExtensionOrMimeType() {
        assertEquals(Kind.PDF, kindOf("K1 Ch.1 Homework.PDF", ""))
        assertEquals(Kind.DOCX, kindOf("Vocabulary.docx", ""))
        assertEquals(Kind.AUDIO, kindOf("K1_Ch01.mp3", ""))
        assertEquals(Kind.AUDIO, kindOf("lesson", "audio/mpeg"))
        assertEquals(Kind.PDF, kindOf("scan", "application/pdf"))
        assertEquals(Kind.OTHER, kindOf("notes.txt", "text/plain"))
        assertEquals(Kind.OTHER, kindOf("old.doc", ""))
    }

    @Test
    fun chineseDetection() {
        assertTrue(isChinese("你"))
        assertTrue(isChinese("咗"))
        assertTrue(isChinese("𠝹")) // outside the Basic Multilingual Plane
        assertFalse(isChinese("A"))
        assertFalse(isChinese("，"))
        assertFalse(isChinese("1"))
    }
}
