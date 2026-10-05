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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PREFS = "studybook"
/** The folder picker starts in Download, where book folders are usually copied. */
private val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
private const val KEY_ROOT = "root_uri"

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var message: TextView
    private val adapter = ChapterAdapter { openChapter(it) }
    private var treeUri: Uri? = null
    private val backup = BackupActions(this)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contentResolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach { runCatching { contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
        treeUri = uri
        load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.chapters)
        empty = findViewById(R.id.empty)
        message = findViewById(R.id.message)
        list.layoutManager = GridLayoutManager(this, spanCount())
        list.adapter = adapter
        findViewById<View>(R.id.choose).setOnClickListener { pickFolder.launch(treeUri ?: DOWNLOADS) }

        treeUri = prefs.getString(KEY_ROOT, null)?.let(Uri::parse)?.takeIf { uri ->
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    }

    override fun onStart() {
        super.onStart()
        load()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.change_folder -> pickFolder.launch(treeUri ?: DOWNLOADS)
            R.id.writing_practice -> startActivity(Intent(this, TrainingActivity::class.java))
            R.id.back_up -> backup.backUp()
            R.id.restore -> backup.restore()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun load() {
        val uri = treeUri ?: return showMessage(getString(R.string.no_folder))
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
