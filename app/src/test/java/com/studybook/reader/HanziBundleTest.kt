package com.studybook.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** The packed stroke data in src/main/assets/hanzi.bin (built by tools/build_assets.py). */
class HanziBundleTest {
    private val bundle = HanziBundle(RandomAccessFile(File("src/main/assets/hanzi.bin"), "r").channel, 0)

    @Test
    fun containsTheFullCharacterSet() {
        assertTrue("only ${bundle.size} characters", bundle.size > 9000)
    }

    @Test
    fun readsSimplifiedAndTraditionalCharacters() {
        for (ch in listOf("你", "好", "謝", "谢", "學", "学", "龍", "唔", "嘅")) {
            val json = bundle.json(ch.codePointAt(0))?.toString(Charsets.UTF_8)
            assertTrue("$ch missing", json != null && json.contains("\"strokes\"") && json.contains("\"medians\""))
        }
    }

    @Test
    fun strokeCountsAreRight() {
        fun strokes(ch: String) = Regex("\"M ").findAll(bundle.json(ch.codePointAt(0))!!.toString(Charsets.UTF_8)).count()
        assertEquals(7, strokes("你"))
        assertEquals(1, strokes("一"))
        assertEquals(17, strokes("謝"))
    }

    @Test
    fun missingCharactersReturnNull() {
        assertNull(bundle.json('A'.code))
        assertNull(bundle.json("咗".codePointAt(0))) // colloquial Cantonese: assembled by the app instead
    }
}
