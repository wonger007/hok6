package com.studybook.reader

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Choosing characters on an open file to practise: a tap on a PDF page (its text, or for a scanned page the characters
 * recognised from its picture) or in a Word document opens [PracticePicker], then writing practice with a worksheet.
 * Background work runs in [activity]'s lifecycle, so it stops when the chapter screen goes.
 */
class PractiseFlow(
    private val activity: AppCompatActivity,
    private val pdf: PdfViewer,
    private val docxText: InkTextView,
    /** The worksheet's title: the open file's name, or the chapter's. */
    private val title: () -> String,
) {
    /** Characters and their positions on each page of the open PDF, read in the background. */
    private var pdfGlyphs: Deferred<List<List<PdfGlyph>>>? = null
    /** Characters recognised from the images of scanned pages (pages with no text), by page. */
    private val recognisedPages = HashMap<Int, List<PdfGlyph>>()

    /** Forgets the last file's characters; called whenever another file is opened. */
    fun reset() {
        pdfGlyphs?.cancel()
        pdfGlyphs = null
        recognisedPages.clear()
    }

    /** Starts reading the characters of a newly opened PDF, in the background. */
    fun openPdf(uri: Uri) {
        pdfGlyphs = activity.lifecycleScope.async(Dispatchers.IO) {
            runCatching { PdfText.load(activity, uri) }.getOrDefault(emptyList())
        }
    }

    /** Shows the Chinese characters of a PDF page; the one under a long-press (x, y in points) comes pre-selected. */
    fun pickFromPdf(page: Int, x: Float?, y: Float?) {
        val glyphsJob = pdfGlyphs ?: return
        activity.lifecycleScope.launch {
            var glyphs = glyphsJob.await().getOrNull(page).orEmpty()
            // A scanned page has no text layer: recognise the characters from the page image instead.
            val recognised = PdfText.chineseCharacters(glyphs).isEmpty()
            if (recognised) glyphs = recognisedPages[page] ?: recognisePage(page).also { recognisedPages[page] = it }
            val pressed = if (x != null && y != null) PdfText.glyphAt(glyphs, x, y, slop = 6f) else null
            if (x != null && pressed == null) {
                Toast.makeText(activity, R.string.practise_miss, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val preselected = pressed?.text?.let { chineseIn(it) }.orEmpty().toSet()
            PracticePicker.show(activity, PdfText.chineseCharacters(glyphs), preselected, recognised) { practise(it) }
        }
    }

    /** Runs on-device text recognition on a rendered PDF page. */
    private suspend fun recognisePage(page: Int): List<PdfGlyph> {
        val widthPoints = pdf.pageWidthPoints(page) ?: return emptyList()
        Toast.makeText(activity, R.string.recognising, Toast.LENGTH_SHORT).show()
        val pixels = 2000
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont -> pdf.renderPage(page, pixels) { cont.resume(it) } }
            ?: return emptyList()
        return try {
            PdfOcr.recognise(bitmap, widthPoints.toFloat() / pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /** Practise mode tap in a Word document: offers the characters of the tapped paragraph, the tapped one chosen. */
    fun pickFromDocx(offset: Int) {
        val text = docxText.text
        if (offset !in text.indices) return
        val cp = Character.codePointAt(text, offset)
        val tapped = String(Character.toChars(cp))
        if (!isChinese(tapped)) {
            Toast.makeText(activity, R.string.practise_miss, Toast.LENGTH_SHORT).show()
            return
        }
        val start = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        PracticePicker.show(activity, chineseIn(text.subSequence(start, end)), setOf(tapped)) { practise(it) }
    }

    /** Offers all the characters of the Word document. */
    fun pickAllFromDocx() = PracticePicker.show(activity, chineseIn(docxText.text), emptySet()) { practise(it) }

    private fun chineseIn(text: CharSequence): List<String> =
        text.codePoints().toArray().map { String(Character.toChars(it)) }.filter(::isChinese).distinct()

    /** Opens writing practice with a worksheet for these characters. */
    private fun practise(characters: List<String>) {
        if (characters.isEmpty()) return
        activity.startActivity(
            Intent(activity, TrainingActivity::class.java)
                .putExtra(TrainingActivity.EXTRA_TEXT, characters.joinToString(""))
                .putExtra(TrainingActivity.EXTRA_TITLE, title())
                .putExtra(TrainingActivity.EXTRA_AUTO_START, true)
        )
    }

    /** Adds "Practise writing" to the text selection menu of Word documents. */
    inner class Selection : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.practise_selection, 0, R.string.practise_title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.practise_selection) return false
            val start = minOf(docxText.selectionStart, docxText.selectionEnd).coerceAtLeast(0)
            val end = maxOf(docxText.selectionStart, docxText.selectionEnd).coerceAtLeast(0)
            val characters = chineseIn(docxText.text.subSequence(start, end))
            if (characters.isEmpty()) {
                Toast.makeText(activity, R.string.practise_no_chinese, Toast.LENGTH_SHORT).show()
            } else {
                practise(characters)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}
