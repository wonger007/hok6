package com.studybook.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(Updates.newer("1.15", "1.14"))
        assertTrue(Updates.newer("1.14.1", "1.14"))
        assertTrue(Updates.newer("2.0", "1.14"))
        // 1.14 is newer than 1.9 (numbers, not text).
        assertTrue(Updates.newer("1.14", "1.9"))
        assertFalse(Updates.newer("1.9", "1.14"))
        assertFalse(Updates.newer("1.14", "1.14"))
        assertFalse(Updates.newer("", "1.14"))
    }
}
