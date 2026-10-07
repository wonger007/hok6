package com.studybook.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tracing on chapter files: what's saved loads back the same, so work isn't lost or moved. */
class InkDocumentTest {
    @get:Rule val folder = TemporaryFolder()

    private val red = 0xE6E53935.toInt()
    private val blue = 0xE61E88E5.toInt()

    /** Waits until everything saved so far has been written (saves happen on a background thread). */
    private fun waitForSaves() {
        val done = CountDownLatch(1)
        InkDocument.afterSaves { done.countDown() }
        assertTrue("saving took too long", done.await(5, TimeUnit.SECONDS))
    }

    private fun saved(doc: InkDocument) {
        doc.save()
        waitForSaves()
    }

    private fun assertSameStrokes(expected: List<Stroke>, actual: List<Stroke>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.color, a.color)
            // Saved to 5 decimal places of the page's width (a fiftieth of a pixel on the widest page drawn).
            assertEquals(InkDocument.round(e.width).toFloat(), a.width)
            assertEquals(e.points.size, a.points.size)
            for (i in e.points.indices) assertEquals(InkDocument.round(e.points[i]).toFloat(), a.points[i])
            for (i in e.points.indices) assertEquals(e.points[i], a.points[i], 0.00001f)
        }
    }

    @Test
    fun tracingLoadsBackAsItWasSaved() {
        val file = File(folder.root, "ink/doc.json")
        val doc = InkDocument.load(file)
        val first = Stroke(red, 0.0041234567f, floatArrayOf(0.1f, 0.2f, 0.123456789f, 0.987654321f, 0.5f, 1.25f))
        val dot = Stroke(blue, 0.0078f, floatArrayOf(0.33333334f, 0.6666667f))
        val onPage3 = Stroke(blue, 0.0078f, floatArrayOf(0.9f, 0.05f, 0.91f, 0.06f))
        doc.add(0, first)
        doc.add(0, dot)
        doc.add(2, onPage3)
        saved(doc)

        val loaded = InkDocument.load(file)
        assertSameStrokes(listOf(first, dot), loaded.strokes(0))
        assertTrue(loaded.strokes(1).isEmpty())
        assertSameStrokes(listOf(onPage3), loaded.strokes(2))
    }

    @Test
    fun savingWhatWasLoadedChangesNothing() {
        val file = File(folder.root, "ink/doc.json")
        val doc = InkDocument.load(file)
        doc.add(0, Stroke(red, 0.004f, floatArrayOf(0.123456789f, 0.2f, 0.3f, 0.456789123f)))
        saved(doc)
        val text = file.readText()

        // Loading, changing and changing back, then saving again writes the same file: positions don't drift.
        val loaded = InkDocument.load(file)
        loaded.add(0, Stroke(blue, 0.01f, floatArrayOf(0.5f, 0.5f)))
        loaded.undo()
        saved(loaded)
        assertEquals(text, file.readText())
    }

    @Test
    fun clearingEverythingRemovesTheFileAndUndoBringsItBack() {
        val file = File(folder.root, "ink/doc.json")
        val doc = InkDocument.load(file)
        val stroke = Stroke(red, 0.004f, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f))
        doc.add(1, stroke)
        saved(doc)
        assertTrue(file.exists())

        assertTrue(doc.clear(listOf(1)))
        saved(doc)
        assertFalse(file.exists())
        assertFalse("nothing left to clear", doc.clear(listOf(1)))

        assertTrue(doc.undo())
        saved(doc)
        assertSameStrokes(listOf(stroke), InkDocument.load(file).strokes(1))
    }

    @Test
    fun clearingOnePageKeepsTheOthers() {
        val file = File(folder.root, "ink/doc.json")
        val doc = InkDocument.load(file)
        val keep = Stroke(blue, 0.004f, floatArrayOf(0.7f, 0.7f, 0.8f, 0.8f))
        doc.add(0, Stroke(red, 0.004f, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)))
        doc.add(1, keep)
        doc.clear(listOf(0))
        saved(doc)

        val loaded = InkDocument.load(file)
        assertTrue(loaded.strokes(0).isEmpty())
        assertSameStrokes(listOf(keep), loaded.strokes(1))
    }

    @Test
    fun aDamagedFileLoadsAsNoTracing() {
        val file = File(folder.root, "ink/doc.json").apply {
            parentFile.mkdirs()
            writeText("{\"0\": [{\"c\": 1, \"w\": ")
        }
        assertTrue(InkDocument.load(file).snapshot().isEmpty())
    }

    @Test
    fun everyChangeIsCountedSoDrawingsAreMadeAgain() {
        val doc = InkDocument.load(File(folder.root, "ink/doc.json"))
        val start = doc.version
        doc.add(0, Stroke(red, 0.004f, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)))
        assertTrue(doc.version > start)
        val afterAdd = doc.version
        assertTrue(doc.eraseAt(0, 0.15f, 0.15f, 0.01f))
        assertTrue(doc.version > afterAdd)
        val afterErase = doc.version
        doc.undo()
        assertTrue(doc.version > afterErase)
    }
}
