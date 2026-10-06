package com.studybook.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PREFS = "studybook"
/** Set once Hok6 has asked for permission to keep its backup in a book folder picked by an earlier version. */
private const val KEY_ASKED_WRITE = "asked_backup_write"
/** The folder picker starts in Download, where book folders are usually copied. */
private val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

class MainActivity : AppCompatActivity() {
    companion object {
        /** The splash shows once when Hok6 starts, not each time the main screen is made again (e.g. turning). */
        private var splashShown = false
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var message: TextView
    private val adapter = ChapterAdapter { openChapter(it) }
    private var treeUri: Uri? = null
    private val backup = BackupActions(this)
    private val writeAccess = WriteAccess(this) { treeUri }
    /** Over the screen while the chapters load when Hok6 starts; null once gone. */
    private var splash: Splash? = null
    private var checkingBackup = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        BookFolder.keep(this, uri)
        treeUri = uri
        load()
        checkBackup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Started with the red launch theme (see Theme.StudyBook.Launch); the app's own from here on.
        setTheme(R.style.Theme_StudyBook)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null && !splashShown) {
            splashShown = true
            supportActionBar?.hide()
            splash = Splash(findViewById(R.id.splash), minMs = 800) {
                splash = null
                supportActionBar?.show()
            }
        }
        list = findViewById(R.id.chapters)
        empty = findViewById(R.id.empty)
        message = findViewById(R.id.message)
        list.layoutManager = GridLayoutManager(this, spanCount())
        list.adapter = adapter
        findViewById<View>(R.id.choose).setOnClickListener { pickFolder.launch(treeUri ?: DOWNLOADS) }

        treeUri = prefs.getString(BookFolder.KEY_ROOT, null)?.let(Uri::parse)?.takeIf { uri ->
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    }

    override fun onStart() {
        super.onStart()
        load()
        checkBackup()
    }

    override fun onStop() {
        super.onStop()
        AutoBackup.saveLater(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (list.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    private fun spanCount() = (resources.configuration.screenWidthDp / 280).coerceAtLeast(1)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.new_folder).isVisible = treeUri != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.change_folder -> pickFolder.launch(treeUri ?: DOWNLOADS)
            R.id.writing_practice -> startActivity(Intent(this, TrainingActivity::class.java))
            R.id.new_folder -> newFolder()
            R.id.back_up -> backup.backUp()
            R.id.restore -> backup.restore()
            R.id.appearance -> Appearance.choose(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun load() {
        val uri = treeUri ?: return showMessage(getString(R.string.no_folder)).also { splash?.done() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootId = DocumentsContract.getTreeDocumentId(uri)
                    val name = Docs.displayName(this@MainActivity, uri, rootId)
                    val children = Docs.listChildren(this@MainActivity, uri, rootId)
                    val chapters = children.filter { it.isDir }.toMutableList()
                    if (children.any { !it.isDir && it.kind != Kind.OTHER }) {
                        chapters.add(0, Entry(DocumentsContract.buildDocumentUriUsingTree(uri, rootId), rootId,
                            getString(R.string.loose_files), DocumentsContract.Document.MIME_TYPE_DIR, true))
                    }
                    name to chapters
                }
            }
            invalidateOptionsMenu()
            splash?.done()
            result.onSuccess { (name, chapters) ->
                title = name ?: getString(R.string.app_name)
                adapter.items = chapters
                if (chapters.isEmpty()) showMessage(getString(R.string.no_chapters)) else {
                    empty.isVisible = false
                    list.isVisible = true
                }
            }.onFailure {
                showMessage(getString(R.string.folder_unavailable))
            }
        }
    }

    private fun newFolder() = writeAccess.run {
        val uri = treeUri ?: return@run
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        BookFolder.askFolderName(this, adapter.items.filter { it.docId != rootId }.map { it.name }) { name ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { BookFolder.createFolder(this@MainActivity, uri, rootId, name) } }
                result.onSuccess {
                    Toast.makeText(this@MainActivity, getString(R.string.folder_created, it.name), Toast.LENGTH_SHORT).show()
                    load()
                }.onFailure {
                    Toast.makeText(this@MainActivity, getString(R.string.folder_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Offers to restore the backup in the book folder (e.g. after reinstalling); otherwise makes sure Hok6 may keep
     * its backup there, asking once if the folder was picked by an earlier version that only read it.
     */
    private fun checkBackup() {
        val uri = treeUri ?: return
        if (checkingBackup) return
        checkingBackup = true
        lifecycleScope.launch {
            val waiting = withContext(Dispatchers.IO) {
                runCatching { AutoBackup.waitingToRestore(this@MainActivity, uri) }.getOrNull()
            }
            when {
                waiting != null -> {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.restore_found_title)
                        .setMessage(getString(R.string.restore_found, AutoBackup.FILE_NAME))
                        .setCancelable(false)
                        .setPositiveButton(R.string.restore_found_yes) { _, _ ->
                            backup.restoreFrom(waiting) { AutoBackup.decided(this@MainActivity, uri) }
                        }
                        .setNegativeButton(R.string.restore_found_no) { _, _ -> AutoBackup.decided(this@MainActivity, uri) }
                        .setOnDismissListener { checkingBackup = false }
                        .show()
                    return@launch
                }
                !BookFolder.canWrite(this@MainActivity, uri) && !prefs.getBoolean(KEY_ASKED_WRITE, false) -> {
                    prefs.edit().putBoolean(KEY_ASKED_WRITE, true).apply()
                    writeAccess.run(R.string.need_write_backup) { AutoBackup.saveLater(this@MainActivity) }
                }
            }
            checkingBackup = false
        }
    }

    private fun showMessage(text: String) {
        title = getString(R.string.app_name)
        adapter.items = emptyList()
        list.isVisible = false
        empty.isVisible = true
        message.text = text
    }

    private fun openChapter(chapter: Entry) {
        startActivity(Intent(this, ChapterActivity::class.java)
            .putExtra(ChapterActivity.EXTRA_TREE, treeUri.toString())
            .putExtra(ChapterActivity.EXTRA_DOC_ID, chapter.docId)
            .putExtra(ChapterActivity.EXTRA_NAME, chapter.name))
    }
}

private class ChapterAdapter(private val onClick: (Entry) -> Unit) : RecyclerView.Adapter<ChapterAdapter.Holder>() {
    var items: List<Entry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
