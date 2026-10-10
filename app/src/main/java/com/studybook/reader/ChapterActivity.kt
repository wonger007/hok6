package com.studybook.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the last page read of a PDF is kept. */
fun pageKey(uri: Uri) = "page:$uri"

class ChapterActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_NAME = "name"
        /** The folder this one is in, where a new folder for moving files is made (the book folder if missing). */
        const val EXTRA_PARENT_ID = "parent_id"
        /** How deep, and how many folders, are offered as places to move files to. */
        private const val MOVE_DEPTH = 5
        private const val MOVE_MAX = 300
        /** How long the message offering Undo after Clear stays, in milliseconds. */
        private const val UNDO_MS = 5000
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var treeUri: Uri
    private lateinit var chapterName: String
    private lateinit var docId: String
    private lateinit var listPane: View
    private lateinit var contentPane: View
    /** Back from an open file closes it and returns to the file list. */
    private val closeFileOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeFile()
    }
    private lateinit var filesEmpty: View
    private lateinit var placeholder: View
    private lateinit var pdfFrame: View
    private lateinit var docxFrame: ZoomPanView
    private lateinit var docxScroll: ScrollView
    private lateinit var docxText: InkTextView
    private lateinit var toolbarTitle: TextView
    /** The tracing and zoom tools in the title bar, shown while a file is open. */
    private lateinit var fileTools: View
    private lateinit var practiseButton: TextView
    private lateinit var modeButton: TextView
    private lateinit var penButton: ImageButton
    private lateinit var sizeButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var ink: Ink
    private lateinit var pdf: PdfViewer
    private lateinit var docx: DocxViewer
    private lateinit var audio: AudioBar
    private val adapter = FileAdapter({ onFileClicked(it) }, { startSelection(it) })
    private val writeAccess = WriteAccess(this) { treeUri }
    /** Selecting files to move them; null when not selecting. */
    private var selection: SelectionMode? = null
    private var files: List<Entry> = emptyList()
    private var current: Entry? = null
    private var docxJob: Job? = null
    /** Choosing characters on the open file to practise. */
    private lateinit var practice: PractiseFlow
    private lateinit var pageIndicator: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter)
        toolbarTitle = findViewById(R.id.toolbar_title)
        setSupportActionBar(findViewById(R.id.toolbar))
        // The title is a view of its own in the bar, so the tools get the room they need and a long name is shortened.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        treeUri = Uri.parse(intent.getStringExtra(EXTRA_TREE))
        chapterName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        title = chapterName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listPane = findViewById(R.id.list_pane)
        contentPane = findViewById(R.id.content_pane)
        filesEmpty = findViewById(R.id.files_empty)
        placeholder = findViewById(R.id.placeholder)
        pdfFrame = findViewById(R.id.pdf_frame)
        docxFrame = findViewById(R.id.docx_frame)
        docxScroll = findViewById(R.id.docx_scroll)
        docxText = findViewById(R.id.docx_text)
        fileTools = findViewById(R.id.file_tools)
        practiseButton = findViewById(R.id.practise_mode)
        modeButton = findViewById(R.id.ink_mode)
        eraserButton = findViewById(R.id.ink_eraser)
        penButton = findViewById(R.id.ink_pen)
        sizeButton = findViewById(R.id.ink_size)
        // Files open ready to write on; Practice mode switches tapping to choosing characters instead.
        ink = Ink(this).apply {
            active = true
            // The title bar is red (a deeper red in dark mode); a blue pen stands out against it.
            color = Ink.PEN_COLORS[1]
        }
        docxText.surface = InkSurface(docxText, ink)
        docxText.ink = ink

        findViewById<RecyclerView>(R.id.files).apply {
            layoutManager = LinearLayoutManager(this@ChapterActivity)
            adapter = this@ChapterActivity.adapter
        }
        pageIndicator = findViewById(R.id.page_indicator)
        pdf = PdfViewer(findViewById(R.id.pdf_frame), findViewById(R.id.pdf_pages), pageIndicator as TextView, ink)
        pageIndicator.setOnClickListener { askPage() }
        audio = AudioBar(this) { adapter.playing = it }
        practice = PractiseFlow(this, pdf, docxText) { current?.name?.substringBeforeLast('.') ?: chapterName }
        pdf.onPageTap = { page, x, y -> practice.pickFromPdf(page, x, y) }
        docxText.customSelectionActionModeCallback = practice.Selection()
        docxText.onTapAt = { offset -> practice.pickFromDocx(offset) }

        docx = DocxViewer(docxFrame, docxScroll, docxText, prefs)
        findViewById<View>(R.id.zoom_in).setOnClickListener { zoom(1) }
        findViewById<View>(R.id.zoom_out).setOnClickListener { zoom(-1) }
        setupInkTools()
        showList(true)
        onBackPressedDispatcher.addCallback(this, closeFileOnBack)

        docId = intent.getStringExtra(EXTRA_DOC_ID)!!
        loadFiles()
    }

    private fun loadFiles() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Docs.listChildren(this@ChapterActivity, treeUri, docId).filter { !it.isDir && it.name != AutoBackup.FILE_NAME } }
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

    override fun onStop() {
        super.onStop()
        AutoBackup.saveLater(this)
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
        // With a file open, the Practice Selector does this.
        menu.findItem(R.id.writing_practice).isVisible = current == null
        menu.findItem(R.id.move_files).isVisible = files.isNotEmpty()
        menu.findItem(R.id.save_traced).isVisible = current?.kind == Kind.PDF
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            R.id.open_external -> current?.let { openExternal(it) }
            R.id.move_files -> startSelection(null)
            R.id.save_traced -> saveTraced()
            R.id.writing_practice -> when {
                current?.kind == Kind.PDF && pdfFrame.isVisible -> practice.pickFromPdf(pdf.middlePage, null, null)
                current?.kind == Kind.DOCX && docxFrame.isVisible -> practice.pickAllFromDocx()
                else -> startActivity(Intent(this, TrainingActivity::class.java).putExtra(TrainingActivity.EXTRA_TITLE, chapterName))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Either the file list or the open file fills the screen. */
    private fun showList(visible: Boolean) {
        listPane.isVisible = visible
        contentPane.isVisible = !visible
        closeFileOnBack.isEnabled = !visible
        title = if (visible) chapterName else current?.name ?: chapterName
    }

    /** Back from an open file: save, close it, and return to the list. */
    private fun closeFile() {
        clearOpenFile()
        showList(true)
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
        for (v in listOf(placeholder, pdfFrame, docxFrame)) v.isVisible = v === placeholder
        fileTools.isVisible = false
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

    /** A folder of the book and its path inside it, e.g. "Book A › Chapter 1". */
    private class Place(val folder: Entry, val label: String)

    /** The book's folders, each followed by the folders inside it, a few levels deep. */
    private fun bookFolders(rootId: String): List<Place> {
        val out = ArrayList<Place>()
        val separator = getString(R.string.path_separator)
        fun walk(parentId: String, prefix: String, depth: Int) {
            if (depth > MOVE_DEPTH) return
            for (folder in Docs.listChildren(this, treeUri, parentId).filter { it.isDir }) {
                if (out.size >= MOVE_MAX) return
                val label = prefix + folder.name
                out += Place(folder, label)
                walk(folder.docId, label + separator, depth + 1)
            }
        }
        walk(rootId, "", 1)
        return out
    }

    /** Offers the book's other folders (favourites first), the book folder itself, and a new folder, to move the selected files to. */
    private fun chooseMoveTarget() {
        val chosen = files.filter { it.uri in adapter.checked }
        if (chosen.isEmpty()) return
        writeAccess.run {
            lifecycleScope.launch {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                // A new folder goes next to this one.
                val parentId = intent.getStringExtra(EXTRA_PARENT_ID) ?: rootId
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        val favorites = Favorites.all(this@ChapterActivity)
                        val (fav, rest) = bookFolders(rootId).partition { Favorites.key(rootId, it.folder.docId) in favorites }
                        val siblings = Docs.listChildren(this@ChapterActivity, treeUri, parentId).filter { it.isDir }.map { it.name }
                        (fav + rest) to siblings
                    }
                }.getOrElse {
                    Toast.makeText(this@ChapterActivity, getString(R.string.cannot_open, it.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val (places, siblings) = found
                val targets = buildList {
                    if (docId != rootId) add(Place(Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), rootId,
                        getString(R.string.book_folder_target), DocumentsContract.Document.MIME_TYPE_DIR, true),
                        getString(R.string.book_folder_target)))
                    addAll(places.filter { it.folder.docId != docId })
                }
                val labels = listOf(getString(R.string.new_folder_item)) + targets.map { it.label }
                MaterialAlertDialogBuilder(this@ChapterActivity)
                    .setTitle(getString(R.string.move_title, chosen.size))
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i == 0) {
                            BookFolder.askFolderName(this@ChapterActivity, siblings) { name ->
                                moveFiles(chosen) { BookFolder.createFolder(this@ChapterActivity, treeUri, parentId, name) }
                            }
                        } else {
                            moveFiles(chosen) { targets[i - 1].folder }
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
            showList(true)
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
                    message(lines.joinToString()).show()
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

    /** Saves a copy of the open PDF with the tracing drawn in, in this folder, under a name the user picks. */
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
            showList(true)
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
                message(getString(R.string.saved_as, fileName)).show()
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
        practice.reset()
        ink.document?.save()
        ink.document = InkDocument.load(this, entry.uri)
        current = entry
        adapter.selected = entry.uri
        for (v in listOf(placeholder, pdfFrame, docxFrame)) v.isVisible = v === view
        fileTools.isVisible = view !== placeholder
        showList(false)
        if (!writeHintShown) {
            writeHintShown = true
            Toast.makeText(this, R.string.write_hint, Toast.LENGTH_SHORT).show()
        }
        invalidateOptionsMenu()
    }

    private fun showPdf(entry: Entry) {
        select(entry, pdfFrame)
        practice.openPdf(entry.uri)
        pdf.open(entry.uri, prefs.getInt(pageKey(entry.uri), 0)) { showError(it) }
    }

    private fun showDocx(entry: Entry) {
        select(entry, docxFrame)
        docxText.text = ""
        docxJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DocxReader.read(this@ChapterActivity, entry.uri) } }
            result.onSuccess {
                docxText.text = it
                docxScroll.scrollTo(0, 0)
                docxFrame.scrollTo(0, 0)
            }.onFailure { showError(it) }
        }
    }

    private fun showError(t: Throwable) {
        Toast.makeText(this, getString(R.string.cannot_open, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        current = null
        ink.document = null
        adapter.selected = null
        for (v in listOf(placeholder, pdfFrame, docxFrame)) v.isVisible = v === placeholder
        fileTools.isVisible = false
        showList(true)
        invalidateOptionsMenu()
    }

    /** Fingers draw too, or only the stylus does and fingers scroll; remembered (see [Stylus]). */
    private fun setFingersDraw(on: Boolean) {
        ink.fingersDraw = on
        Stylus.setFingersDraw(this, on)
        updateModeButton()
        Toast.makeText(this, if (on) R.string.fingers_draw_on else R.string.fingers_draw_off, Toast.LENGTH_SHORT).show()
    }

    /** The stylus / finger toggle: shown when the device takes a stylus, saying who writes now. */
    private fun updateModeButton() {
        modeButton.isVisible = Stylus.supported(this)
        modeButton.setText(if (ink.fingersDraw) R.string.mode_finger else R.string.mode_stylus)
        modeButton.contentDescription = getString(if (ink.fingersDraw) R.string.mode_finger_desc else R.string.mode_stylus_desc)
    }

    private fun setupInkTools() {
        // Once a stylus has been used, a finger only scrolls: the first time one touches the page, say how to draw with
        // fingers again (the stylus may be lost or flat).
        var fingerHinted = false
        ink.onStylus = {
            if (Stylus.used(this)) {
                ink.fingersDraw = false
                updateModeButton()
                Toast.makeText(this, R.string.fingers_draw_off, Toast.LENGTH_SHORT).show()
            } else if (!modeButton.isVisible) {
                updateModeButton()
            }
        }
        modeButton.setOnClickListener { setFingersDraw(!ink.fingersDraw) }
        updateModeButton()
        ink.onFingerIgnored = {
            if (!fingerHinted) {
                fingerHinted = true
                message(getString(R.string.fingers_hint)).setAction(R.string.fingers_let) { setFingersDraw(true) }
                    .setDuration(8000).show()
            }
        }
        // Writing is always on, except in Practice mode, where a tap chooses a character to practice.
        practiseButton.setOnClickListener {
            ink.practising = !ink.practising
            ink.active = !ink.practising
            if (ink.practising) Toast.makeText(this, R.string.practise_on_hint, Toast.LENGTH_SHORT).show()
            updateInkTools()
        }
        penButton.setOnClickListener { showColours() }
        eraserButton.setOnClickListener {
            ink.tool = if (ink.tool == InkTool.ERASER) InkTool.PEN else InkTool.ERASER
            leavePractice()
            updateInkTools()
        }
        sizeButton.setOnClickListener { showSizes() }
        findViewById<View>(R.id.ink_clear).setOnClickListener { askClear() }
        updateInkTools()
    }

    /**
     * Clears one page's tracing after asking: the page on screen, or a choice when more than one page on screen has
     * tracing (a Word document is one long page). Undo brings it back.
     */
    private fun askClear() {
        val doc = ink.document ?: return
        val isPdf = pdfFrame.isVisible
        val traced = (if (isPdf) pdf.pagesOnScreen else listOf(0)).filter { doc.strokes(it).isNotEmpty() }
        if (traced.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_clear, Toast.LENGTH_SHORT).show()
            return
        }
        val clear = { page: Int ->
            if (doc.clear(listOf(page))) {
                invalidateInk()
                message(if (isPdf) getString(R.string.cleared_page, page + 1) else getString(R.string.cleared))
                    // A little longer than usual, to get to Undo after clearing.
                    .setDuration(UNDO_MS)
                    .setAction(R.string.undo) { if (doc.undo()) invalidateInk() }
                    .show()
            }
        }
        val dialog = MaterialAlertDialogBuilder(this).setNegativeButton(android.R.string.cancel, null)
        when {
            !isPdf -> dialog.setTitle(R.string.clear_ask_title).setMessage(R.string.clear_ask_document)
                .setPositiveButton(R.string.clear) { _, _ -> clear(0) }
            traced.size == 1 -> dialog.setTitle(R.string.clear_ask_title)
                .setMessage(getString(R.string.clear_ask_page, traced[0] + 1))
                .setPositiveButton(R.string.clear) { _, _ -> clear(traced[0]) }
            else -> dialog.setTitle(R.string.clear_which_page)
                .setItems(traced.map { getString(R.string.page_number, it + 1) }.toTypedArray()) { _, i -> clear(traced[i]) }
        }
        dialog.show()
    }

    /** A message at the bottom of the screen, above the page number when a PDF is open so it doesn't cover it. */
    private fun message(text: String): Snackbar =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).apply {
            if (pageIndicator.isVisible && pdfFrame.isVisible) anchorView = pageIndicator
        }

    /** Asks for a page number and scrolls to it. */
    private fun askPage() {
        val count = pdf.pageCount
        if (count < 2) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.go_to_page_hint, count)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val pad = (24 * ink.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(box)
            .setPositiveButton(R.string.go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val go = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            input.setOnEditorActionListener { _, _, _ -> go.performClick() }
            go.setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..count) {
                    input.error = getString(R.string.go_to_page_hint, count)
                } else {
                    dialog.dismiss()
                    pdf.goToPage(page - 1)
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /** Picking a pen, size or the eraser means writing again. */
    private fun leavePractice() {
        ink.practising = false
        ink.active = true
    }

    private fun updateInkTools() {
        // The mode that's on is shown solid; pens and the eraser are ringed when chosen (and not in Practice mode).
        practiseButton.setBackgroundResource(if (ink.practising) R.drawable.bar_active else R.drawable.bar_button)
        if (ink.practising) practiseButton.setTextColor(ContextCompat.getColor(this, R.color.brand))
        else practiseButton.setTextColor(barTextColors)
        val writing = !ink.practising
        penButton.setBackgroundResource(
            if (writing && ink.tool == InkTool.PEN) R.drawable.bar_selected else R.drawable.bar_button)
        // The dot shows the pen's colour; the thickness button, a line as thick as the pen.
        penButton.imageTintList = null // the bar's icon colour would turn the dot white
        penButton.setImageDrawable(colourDot(ink.color))
        val pad = (14 * ink.density).toInt()
        penButton.scaleType = ImageView.ScaleType.FIT_CENTER
        penButton.setPadding(pad, pad, pad, pad)
        sizeButton.imageTintList = null
        sizeButton.scaleType = ImageView.ScaleType.CENTER
        sizeButton.setImageDrawable(LineIcon(ink.widthDp * ink.density, 26 * ink.density))
        eraserButton.setBackgroundResource(
            if (writing && ink.tool == InkTool.ERASER) R.drawable.bar_selected else R.drawable.bar_button)
    }

    /** A pen colour as a dot, ringed in white so the red one shows on the red bar. */
    private fun colourDot(colour: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(colour or 0xFF000000.toInt())
        setStroke((2 * ink.density).toInt(), Color.WHITE)
    }

    /** The pen colours, opening under the pen button; choosing one goes back to writing with it. */
    private fun showColours() {
        val dp = ink.density
        val tray = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@ChapterActivity, R.drawable.color_tray_bg)
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val popup = PopupWindow(tray, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8 * dp
        val names = listOf(R.string.pen_red, R.string.pen_blue, R.string.pen_black)
        Ink.PEN_COLORS.forEachIndexed { i, colour ->
            tray.addView(ImageButton(this).apply {
                contentDescription = getString(names[i])
                setImageDrawable(colourDot(colour))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (10 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(
                    if (ink.tool == InkTool.PEN && ink.color == colour) R.drawable.bar_selected else R.drawable.bar_button)
                setOnClickListener {
                    ink.color = colour
                    ink.tool = InkTool.PEN
                    leavePractice()
                    updateInkTools()
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply {
                val m = (4 * dp).toInt()
                setMargins(m, m, m, m)
            })
        }
        popup.showAsDropDown(penButton, 0, (6 * dp).toInt())
    }

    /** The pen thicknesses, opening under the thickness button like the colours; choosing one goes back to writing. */
    private fun showSizes() {
        val dp = ink.density
        val tray = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@ChapterActivity, R.drawable.color_tray_bg)
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val popup = PopupWindow(tray, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8 * dp
        val names = listOf(R.string.pen_thin, R.string.pen_medium, R.string.pen_thick)
        Ink.PEN_SIZES.forEachIndexed { i, width ->
            tray.addView(ImageButton(this).apply {
                contentDescription = getString(names[i])
                setImageDrawable(LineIcon(width * dp, 30 * dp))
                scaleType = ImageView.ScaleType.CENTER
                setBackgroundResource(
                    if (ink.tool == InkTool.PEN && ink.widthDp == width) R.drawable.bar_selected else R.drawable.bar_button)
                setOnClickListener {
                    ink.widthDp = width
                    ink.tool = InkTool.PEN
                    leavePractice()
                    updateInkTools()
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply {
                val m = (4 * dp).toInt()
                setMargins(m, m, m, m)
            })
        }
        popup.showAsDropDown(sizeButton, 0, (6 * dp).toInt())
    }

    /** A white line [thickness] px thick and [length] px long, with round ends: how thick the pen writes. */
    private class LineIcon(private val thickness: Float, private val length: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = thickness
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun getIntrinsicWidth() = length.toInt()
        override fun getIntrinsicHeight() = length.toInt()
        override fun draw(canvas: android.graphics.Canvas) {
            val y = bounds.exactCenterY()
            val inset = thickness / 2
            canvas.drawLine(bounds.left + inset, y, bounds.right - inset, y, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The title bar's text colour (from the layout), for the Practice button when it's off. */
    private val barTextColors by lazy { practiseButton.textColors }
    /** The tip about writing and scrolling is shown on the first file opened. */
    private var writeHintShown = false

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        if (::toolbarTitle.isInitialized) toolbarTitle.text = title
    }

    private fun invalidateInk() {
        pdf.invalidateInk()
        docxText.invalidate()
    }

    private fun zoom(direction: Int) {
        if (pdfFrame.isVisible) {
            pdf.zoomBy(if (direction > 0) 1.25f else 0.8f)
        } else if (docxFrame.isVisible) {
            docx.zoomBy(if (direction > 0) 1.25f else 0.8f)
        }
    }

    private fun savePdfPage() {
        val entry = current ?: return
        if (entry.kind == Kind.PDF && pdfFrame.isVisible) prefs.edit().putInt(pageKey(entry.uri), pdf.currentPage).apply()
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
        holder.icon.setColorFilter(ContextCompat.getColor(context, if (isChecked) R.color.brand_text else color))
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
