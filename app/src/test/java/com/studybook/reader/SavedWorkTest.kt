package com.studybook.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Writing practice storage and merging a backup into what's already on the device. */
class SavedWorkTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun storeKeepsValuesUnderAnyKey() {
        val store = TrainingStore(folder.newFolder("training"))
        store.set("history", """{"你好":{"count":2}}""")
        store.set("ink:謝謝|large|s", "[]")
        assertEquals("""{"你好":{"count":2}}""", store.get("history"))
        assertEquals("[]", store.get("ink:謝謝|large|s"))
        assertEquals(setOf("history", "ink:謝謝|large|s"), store.keys().toSet())
        store.delete("history")
        assertNull(store.get("history"))
    }

    @Test
    fun historyKeepsTheMostRecentlyPractisedEntry() {
        val local = """{"你好":{"text":"你好","last":200,"count":5},"唔該":{"text":"唔該","last":100,"count":1}}"""
        val backup = JSONObject("""{"你好":{"text":"你好","last":100,"count":2},"唔該":{"text":"唔該","last":300,"count":4},"多謝":{"text":"多謝","last":50,"count":1}}""")
        val merged = JSONObject(Backup.merge("history", local, backup))
        assertEquals(5, merged.getJSONObject("你好").getInt("count"))
        assertEquals(4, merged.getJSONObject("唔該").getInt("count"))
        assertEquals(1, merged.getJSONObject("多謝").getInt("count"))
    }

    @Test
    fun bookmarksAreCombined() {
        val merged = JSONArray(Backup.merge("bookmarks", """["你","好"]""", JSONArray("""["好","學"]""")))
        assertEquals(listOf("你", "好", "學"), List(merged.length()) { merged.getString(it) })
    }

    @Test
    fun quizIsCombined() {
        val merged = JSONArray(Backup.merge("quiz", """["三","學校"]""", JSONArray("""["學校","華"]""")))
        assertEquals(listOf("三", "學校", "華"), List(merged.length()) { merged.getString(it) })
    }

    @Test
    fun otherValuesComeFromTheBackup() {
        assertEquals("[1,2]", Backup.merge("ink:你|large|s", "[3]", JSONArray("[1,2]")))
        assertEquals("\"cmn\"", Backup.merge("lang", "\"yue\"", "cmn"))
        assertEquals("true", Backup.merge("fingerDraw", null, true))
    }

    @Test
    fun backupContentsAreEmptyBeforeAnythingIsDone() {
        assertTrue(Backup.isEmpty(Backup.contents(folder.newFolder("files"))))
    }

    @Test
    fun backupContentsDontDependOnTheOrderThingsWereSaved() {
        val a = folder.newFolder("a")
        TrainingStore(java.io.File(a, TrainingStore.DIR)).apply { set("history", "{}"); set("bookmarks", """["你"]""") }
        val b = folder.newFolder("b")
        TrainingStore(java.io.File(b, TrainingStore.DIR)).apply { set("bookmarks", """["你"]"""); set("history", "{}") }
        java.io.File(b, "ink").mkdirs()
        java.io.File(b, "ink/0a.json").writeText("""{"0":[]}""")
        assertNotEquals(Backup.contents(a).toString(), Backup.contents(b).toString())
        java.io.File(a, "ink").mkdirs()
        java.io.File(a, "ink/0a.json").writeText("""{"0":[]}""")
        assertEquals(Backup.contents(a).toString(), Backup.contents(b).toString())
        assertFalse(Backup.isEmpty(Backup.contents(a)))
    }
}
