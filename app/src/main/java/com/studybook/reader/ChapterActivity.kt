package com.studybook.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode as SelectionMode
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY_DOCX_SIZE = "docx_text_size"
private const val KEY_LIST_HIDDEN = "file_list_hidden"
private const val TOOL_ICON = 0xFF424242.toInt()

/** Where the last page read of a PDF is kept. */
fun pageKey(uri: Uri) = "page:$uri"

class ChapterActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_NAME = "name"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var treeUri: Uri
    private lateinit var chapterName: String
    private lateinit var docId: String
    private lateinit var listPane: View
    private lateinit var contentPane: View
    /** Phones (smallest side under 600 dp) show the file list and the open file one at a time, full screen. */
    private val isPhone by lazy { resources.configuration.smallestScreenWidthDp < 600 }
    /** On phones, Back closes the open file and returns to the file list. */
    private val closeFileOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeFile()
    }
    private lateinit var listDivider: View
    private lateinit var filesEmpty: View
    private lateinit var placeholder: View
    private lateinit var pdfFrame: View
    private lateinit var docxScroll: ScrollView
    private lateinit var docxText: InkTextView
    private lateinit var zoomBar: View
    private lateinit var inkTools: View
    private lateinit var traceButton: ImageButton
    private lateinit var practiseButton: TextView
    private lateinit var colorButtons: List<ImageButton>
    private lateinit var eraserButton: ImageButton
    private lateinit var ink: Ink
    private lateinit var pdf: PdfViewer
    private lateinit var audio: AudioBar
    private val adapter = FileAdapter({ onFileClicked(it) }, { startSelection(it) })
    private val writeAccess = WriteAccess(this) { treeUri }
    /** Selecting files to move them; null when not selecting. */
    private var selection: SelectionMode? = null
    private var files: List<Entry> = emptyList()
    private var current: Entry? = null
    private var docxJob: Job? = null
    /** Characters and their positions on each page of the open PDF, read in the background. */
    private var pdfGlyphs: Deferred<List<List<PdfGlyph>>>? = null
    /** Characters recognised from the images of scanned pages (pages with no text), by page. */
    private val recognisedPages = HashMap<Int, List<PdfGlyph>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter)
        treeUri = Uri.parse(intent.getStringExtra(EXTRA_TREE))
        chapterName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        title = chapterName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listPane = findViewById(R.id.list_pane)
        contentPane = findViewById(R.id.content_pane)
        listDivider = findViewById(R.id.list_divider)
        filesEmpty = findViewById(R.id.files_empty)
        placeholder = findViewById(R.id.placeholder)
        pdfFrame = findViewById(R.id.pdf_frame)
        docxScroll = findViewById(R.id.docx_scroll)
        docxText = findViewById(R.id.docx_text)
        zoomBar = findViewById(R.id.zoom_bar)
        inkTools = findViewById(R.id.ink_tools)
        traceButton = findViewById(R.id.trace)
        practiseButton = findViewById(R.id.practise_mode)
        eraserButton = findViewById(R.id.ink_eraser)
        colorButtons = listOf(R.id.ink_red, R.id.ink_blue, R.id.ink_black).map { findViewById(it) }
        ink = Ink(this)
        docxText.surface = InkSurface(docxText, ink) { dy -> docxScroll.scrollBy(0, dy.toInt()) }
        docxText.customSelectionActionModeCallback = PractiseSelection()
        docxText.ink = ink
        docxText.onTapAt = { offset -> pickFromDocx(offset) }

        findViewById<RecyclerView>(R.id.files).apply {
            layoutManager = LinearLayoutManager(this@ChapterActivity)
            adapter = this@ChapterActivity.adapter
        }
        pdf = PdfViewer(findViewById(R.id.pdf_frame), findViewById(R.id.pdf_pages), findViewById(R.id.page_indicator), ink)
        audio = AudioBar(this) { adapter.playing = it }
        pdf.onPageTap = { page, x, y -> pickFromPdf(page, x, y) }

        docxText.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getFloat(KEY_DOCX_SIZE, 20f))
        findViewById<View>(R.id.zoom_in).setOnClickListener { zoom(1) }
        findViewById<View>(R.id.zoom_out).setOnClickListener { zoom(-1) }
        setupInkTools()
        if (isPhone) {
            listPane.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            showPhoneList(true)
            onBackPressedDispatcher.addCallback(this, closeFileOnBack)
        } else {
            setListVisible(!prefs.getBoolean(KEY_LIST_HIDDEN, false))
        }

        docId = intent.getStringExtra(EXTRA_DOC_ID)!!
        loadFiles()
    }

    private fun loadFiles() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Docs.listChildren(this@ChapterActivity, treeUri, docId).filter { !it.isDir } }
            }
            result.onSuccess {
                files = it
                adapter.items = it
                adapter.checked = adapter.checked.filterTo(HashSet()) { uri -> it.any { f -> f.uri == uri } }
                filesEmpty.isVisible = it.isEmpty()
            }.onFailure {
                Toast.makeText(this@ChapterActivity, getString(R.string.cannot_open, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        savePdfPage()
        ink.document?.save()
    }

    override fun onDestroy() {
        pdf.shutdown()
        audio.release()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chapter, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.open_external).isVisible = current != null
        menu.findItem(R.id.toggle_list).isVisible = !isPhone
        menu.findItem(R.id.move_files).isVisible = files.isNotEmpty()
        menu.findItem(R.id.save_traced).isVisible = current?.kind == Kind.PDF
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            R.id.toggle_list -> {
                val visible = !listPane.isVisible
                setListVisible(visible)
                prefs.edit().putBoolean(KEY_LIST_HIDDEN, !visible).apply()
            }
            R.id.open_external -> current?.let { openExternal(it) }
            R.id.move_files -> startSelection(null)
            R.id.save_traced -> saveTraced()
            R.id.writing_practice -> when {
                current?.kind == Kind.PDF && pdfFrame.isVisible -> pickFromPdf(pdf.middlePage, null, null)
                current?.kind == Kind.DOCX && docxScroll.isVisible ->
                    PracticePicker.show(this, chineseIn(docxText.text), emptySet()) { practise(it) }
                else -> startActivity(Intent(this, TrainingActivity::class.java).putExtra(TrainingActivity.EXTRA_TITLE, chapterName))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setListVisible(visible: Boolean) {
        listPane.isVisible = visible
        listDivider.isVisible = visible
    }

    /** Phone layout: either the file list or the open file fills the screen. */
    private fun showPhoneList(showList: Boolean) {
        listPane.isVisible = showList
        listDivider.isVisible = false
        contentPane.isVisible = !showList
        closeFileOnBack.isEnabled = !showList
        title = if (showList) chapterName else current?.name ?: chapterName
    }

    /** Phone Back from an open file: save, close it, and return to the list. */
    private fun closeFile() {
        clearOpenFile()
        showPhoneList(true)
    }

    /** Saves and closes the open file, leaving the "choose a file" message. */
    private fun clearOpenFile() {
        savePdfPage()
        ink.document?.save()
        docxJob?.cancel()
        pdf.close()
        current = null
        ink.document = null
        adapter.selected = null
        for (v in listOf(placeholder, pdfFrame, docxScroll)) v.isVisible = v === placeholder
        zoomBar.isVisible = false
        invalidateOptionsMenu()
    }

    private fun onFileClicked(entry: Entry) {
        if (selection != null) return toggleChecked(entry)
        when (entry.kind) {
            Kind.PDF -> showPdf(entry)
            Kind.DOCX -> showDocx(entry)
            Kind.AUDIO -> audio.play(files.filter { it.kind == Kind.AUDIO }, entry, chapterName)
            Kind.OTHER -> openExternal(entry)
        }
    }

    /** Starts selecting files to move, with [first] (from a long-press) already selected. */
    private fun startSelection(first: Entry?) {
        if (selection == null) selection = startSupportActionMode(SelectionCallback())
        if (first != null && first.uri !in adapter.checked) toggleChecked(first) else updateSelection()
    }

    private fun toggleChecked(entry: Entry) {
        val checked = HashSet(adapter.checked)
        if (!checked.remove(entry.uri)) checked += entry.uri
        adapter.checked = checked
        updateSelection()
    }

    private fun updateSelection() {
        val mode = selection ?: return
        val count = adapter.checked.size
        mode.title = if (count == 0) getString(R.string.select_files) else getString(R.string.selected_count, count)
        mode.menu.findItem(R.id.move)?.isEnabled = count > 0
    }

    private inner class SelectionCallback : SelectionMode.Callback {
        override fun onCreateActionMode(mode: SelectionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.file_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: SelectionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: SelectionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.move -> chooseMoveTarget()
                R.id.select_all -> {
                    adapter.checked = files.mapTo(HashSet()) { it.uri }
                    updateSelection()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: SelectionMode) {
            selection = null
            adapter.checked = emptySet()
        }
    }

    /** Offers the book's other chapter folders, the book folder itself, and a new folder, to move the selected files to. */
    private fun chooseMoveTarget() {
        val chosen = files.filter { it.uri in adapter.checked }
        if (chosen.isEmpty()) return
        writeAccess.run {
            lifecycleScope.launch {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val folders = withContext(Dispatchers.IO) {
                    runCatching { Docs.listChildren(this@ChapterActivity, treeUri, rootId).filter { it.isDir } }
                }.getOrElse {
                    Toast.makeText(this@ChapterActivity, getString(R.string.cannot_open, it.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val targets = buildList {
                    if (docId != rootId) add(Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), rootId,
                        getString(R.string.book_folder_target), DocumentsContract.Document.MIME_TYPE_DIR, true))
                    addAll(folders.filter { it.docId != docId })
                }
                val labels = listOf(getString(R.string.new_folder_item)) + targets.map { it.name }
                MaterialAlertDialogBuilder(this@ChapterActivity)
                    .setTitle(getString(R.string.move_title, chosen.size))
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i == 0) {
                            BookFolder.askFolderName(this@ChapterActivity, folders.map { it.name }) { name ->
                                moveFiles(chosen) { BookFolder.createFolder(this@ChapterActivity, treeUri, rootId, name) }
                            }
                        } else {
                            moveFiles(chosen) { targets[i - 1] }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private class MoveResult(val folder: String, val moved: Int, val skipped: List<String>, val failed: List<String>, val error: Throwable?)

    /** Moves files to the folder [destination] gives (run in the background, so it may make a new folder). */
    private fun moveFiles(chosen: List<Entry>, destination: () -> Entry) {
        selection?.finish()
        // Save the open file's tracing and page first, so they move with it.
        if (current != null && chosen.any { it.uri == current?.uri }) {
            clearOpenFile()
            if (isPhone) showPhoneList(true)
        }
        Toast.makeText(this, R.string.moving, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = destination()
                    // Android storage ignores upper/lower case in names.
                    val there = Docs.listChildren(this@ChapterActivity, treeUri, folder.docId).mapTo(HashSet()) { it.name.lowercase() }
                    var moved = 0
                    val skipped = ArrayList<String>()
                    val failed = ArrayList<String>()
                    var error: Throwable? = null
                    for (file in chosen) {
                        if (file.name.lowercase() in there) {
                            skipped += file.name
                            continue
                        }
                        try {
                            BookFolder.move(this@ChapterActivity, treeUri, file, docId, folder.docId)
                            moved++
                        } catch (e: Exception) {
                            failed += file.name
                            error = e
                        }
                    }
                    MoveResult(folder.name, moved, skipped, failed, error)
                }
            }
            loadFiles()
            result.onSuccess { r ->
                val lines = ArrayList<String>()
                if (r.moved > 0) lines += resources.getQuantityString(R.plurals.moved, r.moved, r.moved, r.folder)
                if (r.skipped.isNotEmpty()) lines += getString(R.string.move_skipped, r.folder, r.skipped.joinToString("\n"))
                if (r.failed.isNotEmpty()) lines += getString(R.string.move_failed, r.failed.joinToString("\n"),
                    r.error?.message ?: r.error?.javaClass?.simpleName)
                if (r.skipped.isEmpty() && r.failed.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), lines.joinToString(), Snackbar.LENGTH_LONG).show()
                } else {
                    MaterialAlertDialogBuilder(this@ChapterActivity)
                        .setMessage(lines.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.onFailure {
                Toast.makeText(this@ChapterActivity, getString(R.string.folder_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Saves a copy of the open PDF with the tracing drawn in, in this chapter folder, under a name the user picks. */
    private fun saveTraced(name: String? = null) {
        val entry = current?.takeIf { it.kind == Kind.PDF } ?: return
        val strokes = ink.document?.snapshot().orEmpty()
        if (strokes.isEmpty()) {
            Toast.makeText(this, R.string.nothing_traced, Toast.LENGTH_SHORT).show()
            return
        }
        val base = entry.name.substringBeforeLast('.')
        writeAccess.run {
            BookFolder.askName(this, getString(R.string.save_traced_title), name ?: base, getString(R.string.file_name),
                R.string.save, message = getString(R.string.save_traced_hint), extension = ".pdf", quickNames = listOf("completed_$base", "${base}_completed")) { chosen ->
                val fileName = "$chosen.pdf"
                // Android storage ignores upper/lower case in names.
                val existing = files.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
                if (existing == null) return@askName writeTraced(entry, strokes, fileName, null)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.replace_title)
                    .setMessage(getString(if (existing.uri == entry.uri) R.string.replace_original else R.string.replace_other, existing.name))
                    .setPositiveButton(R.string.replace) { _, _ -> writeTraced(entry, strokes, fileName, existing) }
                    .setNegativeButton(R.string.other_name) { _, _ -> saveTraced(chosen) }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun writeTraced(entry: Entry, strokes: Map<Int, List<Stroke>>, fileName: String, replace: Entry?) {
        val replacingOpenFile = replace?.uri == entry.uri
        if (replacingOpenFile) {
            clearOpenFile()
            if (isPhone) showPhoneList(true)
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = TracedPdf.render(this@ChapterActivity, entry.uri, strokes)
                    val target = replace?.uri ?: DocumentsContract.createDocument(contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), "application/pdf", fileName)
                        ?: error("the file couldn't be made")
                    contentResolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
                    if (replace != null) {
                        // The replaced file's old tracing no longer fits it (or is now part of the page).
                        InkDocument.delete(this@ChapterActivity, replace.uri)
                        if (!replacingOpenFile) prefs.edit().remove(pageKey(replace.uri)).apply()
                    }
                }
            }
            loadFiles()
            result.onSuccess {
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.saved_as, fileName), Snackbar.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this@ChapterActivity, getString(R.string.save_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun select(entry: Entry, view: View) {
        savePdfPage()
        docxJob?.cancel()
        if (view !== pdfFrame) pdf.close()
        pdfGlyphs?.cancel()
        pdfGlyphs = null
        recognisedPages.clear()
        ink.document?.save()
        ink.document = InkDocument.load(this, entry.uri)
        current = entry
        adapter.selected = entry.uri
        for (v in listOf(placeholder, pdfFrame, docxScroll)) v.isVisible = v === view
        zoomBar.isVisible = view !== placeholder
        if (isPhone) showPhoneList(false)
        invalidateOptionsMenu()
    }

    private fun showPdf(entry: Entry) {
        select(entry, pdfFrame)
        pdfGlyphs = lifecycleScope.async(Dispatchers.IO) {
            runCatching { PdfText.load(this@ChapterActivity, entry.uri) }.getOrDefault(emptyList())
        }
        pdf.open(entry.uri, prefs.getInt(pageKey(entry.uri), 0)) { showError(it) }
    }

    private fun showDocx(entry: Entry) {
        select(entry, docxScroll)
        docxText.text = ""
        docxJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DocxReader.read(this@ChapterActivity, entry.uri) } }
            result.onSuccess {
                docxText.text = it
                docxScroll.scrollTo(0, 0)
            }.onFailure { showError(it) }
        }
    }

    private fun showError(t: Throwable) {
        Toast.makeText(this, getString(R.string.cannot_open, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        current = null
        ink.document = null
        adapter.selected = null
        for (v in listOf(placeholder, pdfFrame, docxScroll)) v.isVisible = v === placeholder
        zoomBar.isVisible = false
        if (isPhone) showPhoneList(true)
        invalidateOptionsMenu()
    }

    private fun setupInkTools() {
        // Trace and Practise are separate modes; at most one is on, and the active one is drawn solid.
        traceButton.setOnClickListener {
            ink.active = !ink.active
            if (ink.active) {
                ink.practising = false
                Toast.makeText(this, R.string.trace_on_hint, Toast.LENGTH_SHORT).show()
            }
            updateInkTools()
        }
        practiseButton.setOnClickListener {
            ink.practising = !ink.practising
            if (ink.practising) {
                ink.active = false
                Toast.makeText(this, R.string.practise_on_hint, Toast.LENGTH_SHORT).show()
            }
            updateInkTools()
        }
        colorButtons.forEachIndexed { i, button ->
            button.setOnClickListener {
                ink.color = Ink.PEN_COLORS[i]
                ink.tool = InkTool.PEN
                updateInkTools()
            }
        }
        eraserButton.setOnClickListener {
            ink.tool = if (ink.tool == InkTool.ERASER) InkTool.PEN else InkTool.ERASER
            updateInkTools()
        }
        findViewById<View>(R.id.ink_size).setOnClickListener {
            val sizes = Ink.PEN_SIZES
            ink.widthDp = sizes[(sizes.indexOfFirst { it == ink.widthDp } + 1) % sizes.size]
            ink.tool = InkTool.PEN
            updateInkTools()
        }
        findViewById<View>(R.id.ink_undo).setOnClickListener {
            if (ink.document?.undo() == true) invalidateInk()
        }
        // Clears everything on screen at once (a Word document is one long page); Undo brings it back.
        findViewById<View>(R.id.ink_clear).setOnClickListener {
            val doc = ink.document ?: return@setOnClickListener
            if (!doc.clear(if (pdfFrame.isVisible) pdf.pagesOnScreen else listOf(0))) {
                Toast.makeText(this, R.string.nothing_to_clear, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            invalidateInk()
            Snackbar.make(findViewById(R.id.content_pane), R.string.cleared, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { if (doc.undo()) invalidateInk() }
                .show()
        }
        updateInkTools()
    }

    private fun updateInkTools() {
        inkTools.isVisible = ink.active
        traceButton.setBackgroundResource(if (ink.active) R.drawable.tool_active else 0)
        traceButton.imageTintList = android.content.res.ColorStateList.valueOf(if (ink.active) Color.WHITE else TOOL_ICON)
        practiseButton.setBackgroundResource(if (ink.practising) R.drawable.tool_active else 0)
        practiseButton.setTextColor(if (ink.practising) Color.WHITE else TOOL_ICON)
        colorButtons.forEachIndexed { i, button ->
            val selected = ink.tool == InkTool.PEN && ink.color == Ink.PEN_COLORS[i]
            button.setBackgroundResource(if (selected) R.drawable.tool_selected else 0)
            // Dot size shows the pen size.
            val pad = ((17 - 4 * Ink.PEN_SIZES.indexOfFirst { it == ink.widthDp }) * ink.density).toInt()
            button.scaleType = ImageView.ScaleType.FIT_CENTER
            button.setPadding(pad, pad, pad, pad)
        }
        eraserButton.setBackgroundResource(if (ink.tool == InkTool.ERASER) R.drawable.tool_selected else 0)
    }

    private fun invalidateInk() {
        pdf.invalidateInk()
        docxText.invalidate()
    }

    private fun zoom(direction: Int) {
        if (pdfFrame.isVisible) {
            pdf.zoomBy(if (direction > 0) 1.25f else 0.8f)
        } else if (docxScroll.isVisible) {
            val sp = (prefs.getFloat(KEY_DOCX_SIZE, 20f) + 2 * direction).coerceIn(12f, 48f)
            prefs.edit().putFloat(KEY_DOCX_SIZE, sp).apply()
            docxText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
    }

    private fun savePdfPage() {
        val entry = current ?: return
        if (entry.kind == Kind.PDF && pdfFrame.isVisible) prefs.edit().putInt(pageKey(entry.uri), pdf.currentPage).apply()
    }

    /** Shows the Chinese characters of a PDF page; the one under a long-press (x, y in points) comes pre-selected. */
    private fun pickFromPdf(page: Int, x: Float?, y: Float?) {
        val glyphsJob = pdfGlyphs ?: return
        lifecycleScope.launch {
            var glyphs = glyphsJob.await().getOrNull(page).orEmpty()
            // A scanned page has no text layer: recognise the characters from the page image instead.
            val recognised = PdfText.chineseCharacters(glyphs).isEmpty()
            if (recognised) glyphs = recognisedPages[page] ?: recognisePage(page).also { recognisedPages[page] = it }
            val pressed = if (x != null && y != null) PdfText.glyphAt(glyphs, x, y, slop = 6f) else null
            if (x != null && pressed == null) {
                Toast.makeText(this@ChapterActivity, R.string.practise_miss, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val preselected = pressed?.text?.let { chineseIn(it) }.orEmpty().toSet()
            PracticePicker.show(this@ChapterActivity, PdfText.chineseCharacters(glyphs), preselected, recognised) { practise(it) }
        }
    }

    /** Runs on-device text recognition on a rendered PDF page. */
    private suspend fun recognisePage(page: Int): List<PdfGlyph> {
        val widthPoints = pdf.pageWidthPoints(page) ?: return emptyList()
        Toast.makeText(this, R.string.recognising, Toast.LENGTH_SHORT).show()
        val pixels = 2000
        val bitmap = suspendCancellableCoroutine<android.graphics.Bitmap?> { cont -> pdf.renderPage(page, pixels) { cont.resume(it) } }
            ?: return emptyList()
        return try {
            PdfOcr.recognise(bitmap, widthPoints.toFloat() / pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /** Practise mode tap in a Word document: offers the characters of the tapped paragraph, the tapped one chosen. */
    private fun pickFromDocx(offset: Int) {
        val text = docxText.text
        if (offset !in text.indices) return
        val cp = Character.codePointAt(text, offset)
        val tapped = String(Character.toChars(cp))
        if (!isChinese(tapped)) {
            Toast.makeText(this, R.string.practise_miss, Toast.LENGTH_SHORT).show()
            return
        }
        val start = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        PracticePicker.show(this, chineseIn(text.subSequence(start, end)), setOf(tapped)) { practise(it) }
    }

    private fun chineseIn(text: CharSequence): List<String> =
        text.codePoints().toArray().map { String(Character.toChars(it)) }.filter(::isChinese).distinct()

    /** Opens writing practice with a worksheet for these characters. */
    private fun practise(characters: List<String>) {
        if (characters.isEmpty()) return
        val title = current?.name?.substringBeforeLast('.') ?: chapterName
        startActivity(
            Intent(this, TrainingActivity::class.java)
                .putExtra(TrainingActivity.EXTRA_TEXT, characters.joinToString(""))
                .putExtra(TrainingActivity.EXTRA_TITLE, title)
                .putExtra(TrainingActivity.EXTRA_AUTO_START, true)
        )
    }

    /** Adds "Practise writing" to the text selection menu of Word documents. */
    private inner class PractiseSelection : ActionMode.Callback {
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
                Toast.makeText(this@ChapterActivity, R.string.practise_no_chinese, Toast.LENGTH_SHORT).show()
            } else {
                practise(characters)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private fun openExternal(entry: Entry) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, getString(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

private class FileAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLongClick: (Entry) -> Unit,
) : RecyclerView.Adapter<FileAdapter.Holder>() {
    var items: List<Entry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var selected: Uri? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    /** Files selected to move. */
    var checked: Set<Uri> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var playing: Uri? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.row)
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val defaultColors = name.textColors
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val (icon, color) = when (item.kind) {
            Kind.PDF -> R.drawable.ic_doc to R.color.kind_pdf
            Kind.DOCX -> R.drawable.ic_doc to R.color.kind_docx
            Kind.AUDIO -> R.drawable.ic_audio to R.color.kind_audio
            Kind.OTHER -> R.drawable.ic_file to R.color.kind_other
        }
        val isChecked = item.uri in checked
        holder.icon.setImageResource(if (isChecked) R.drawable.ic_check else icon)
        holder.icon.setColorFilter(ContextCompat.getColor(context, if (isChecked) R.color.brand else color))
        holder.name.text = item.name
        val isPlaying = item.uri == playing
        holder.name.setTypeface(null, if (isPlaying || item.uri == selected) Typeface.BOLD else Typeface.NORMAL)
        if (isPlaying) holder.name.setTextColor(ContextCompat.getColor(context, R.color.kind_audio))
        else holder.name.setTextColor(holder.defaultColors)
        holder.row.setBackgroundColor(
            if (isChecked || item.uri == selected) ContextCompat.getColor(context, R.color.selected_bg) else 0)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
