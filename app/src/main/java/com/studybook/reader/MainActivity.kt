package com.studybook.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PREFS = "studybook"
/** Set once Hok6 has asked for permission to keep its backup in a book folder picked by an earlier version. */
private const val KEY_ASKED_WRITE = "asked_backup_write"
/** The folder picker starts in Download, where book folders are usually copied. */
private val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

/**
 * The book folder's folders as cards (books, chapters or any folders), favourites first. A folder that holds more
 * folders opens as cards again, one level down (the same screen, started with [EXTRA_DOC_ID]); one with only files
 * opens its file list ([ChapterActivity]).
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** The splash shows once when Hok6 starts, not each time the main screen is made again (e.g. turning). */
        private var splashShown = false
        /** The folder shown, below the book folder; none for the book folder itself. */
        const val EXTRA_DOC_ID = "doc_id"
        /** The names of the folders above it, from the book folder down, for the title bar. */
        const val EXTRA_PATH = "path"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var message: TextView
    private lateinit var choose: View
    private val adapter = ChapterAdapter({ openFolder(it) }, { toggleFavorite(it) })
    private var treeUri: Uri? = null
    /** The folder shown when it isn't the book folder itself. */
    private val folderId by lazy { intent.getStringExtra(EXTRA_DOC_ID) }
    private val path by lazy { intent.getStringArrayListExtra(EXTRA_PATH).orEmpty() }
    private val isTop get() = folderId == null
    /** The folder whose files make the "Files in …" card, if it has study files. */
    private var filesHere: Entry? = null
    private val backup = BackupActions(this)
    private val writeAccess = WriteAccess(this) { treeUri }
    /** Over the screen while the chapters load when Hok6 starts; null once gone. */
    private var splash: Splash? = null
    private var checkingBackup = false
    /**
     * Whether each folder card's folder has folders in it, found in the background after the cards are shown, so a tap
     * opens the right screen at once instead of looking first.
     */
    private val hasFolders = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var lookAhead: Job? = null
    private lateinit var opening: View

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
        if (!isTop) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.subtitle = path.joinToString(getString(R.string.path_separator))
        }
        // First start: the welcome screen, with what to download (it shows Hok6's name, so no splash as well).
        if (isTop && savedInstanceState == null && !Downloads.setupDone(this)) {
            splashShown = true
            startActivity(Intent(this, SetupActivity::class.java))
        }
        if (isTop && savedInstanceState == null && !splashShown) {
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
        choose = findViewById(R.id.choose)
        opening = findViewById(R.id.opening)
        choose.setOnClickListener { pickFolder.launch(treeUri ?: DOWNLOADS) }

        treeUri = prefs.getString(BookFolder.KEY_ROOT, null)?.let(Uri::parse)?.takeIf { uri ->
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    }

    override fun onStart() {
        super.onStart()
        load()
        if (isTop) checkBackup()
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
        // Changing the book folder is done from the top; one level down, Back or ← goes there.
        menu.findItem(R.id.change_folder).isVisible = isTop
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.change_folder -> pickFolder.launch(treeUri ?: DOWNLOADS)
            R.id.writing_practice -> startActivity(Intent(this, TrainingActivity::class.java))
            R.id.new_folder -> newFolder()
            R.id.back_up -> backup.backUp()
            R.id.restore -> backup.restore()
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
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
                    val shownId = folderId ?: rootId
                    val name = Docs.displayName(this@MainActivity, uri, shownId)
                    val children = Docs.listChildren(this@MainActivity, uri, shownId)
                    val favorites = Favorites.all(this@MainActivity)
                    val folders = children.filter { it.isDir }
                    val files = if (children.any { !it.isDir && it.kind != Kind.OTHER }) {
                        Entry(DocumentsContract.buildDocumentUriUsingTree(uri, shownId), shownId,
                            getString(if (isTop) R.string.loose_files else R.string.files_here),
                            DocumentsContract.Document.MIME_TYPE_DIR, true)
                    } else null
                    Listing(name, rootId, files, folders, favorites)
                }
            }
            invalidateOptionsMenu()
            splash?.done()
            result.onSuccess { listing ->
                title = listing.name ?: getString(R.string.app_name)
                filesHere = listing.files
                rootId = listing.rootId
                // Favourites first, then the files here, then the other folders.
                val (favorites, others) = listing.folders.partition { Favorites.key(listing.rootId, it.docId) in listing.favorites }
                adapter.favorites = favorites.mapTo(HashSet()) { it.docId }
                adapter.filesCard = listing.files?.docId
                adapter.items = favorites + listOfNotNull(listing.files) + others
                lookInto(uri, listing.folders)
                if (adapter.items.isEmpty()) {
                    showMessage(getString(if (isTop) R.string.no_chapters else R.string.empty_folder))
                } else {
                    empty.isVisible = false
                    list.isVisible = true
                }
            }.onFailure {
                showMessage(getString(R.string.folder_unavailable))
            }
        }
    }

    /** What [load] found: the folder's name, its "Files in …" card if it has files, and its folders. */
    private class Listing(val name: String?, val rootId: String, val files: Entry?, val folders: List<Entry>, val favorites: Set<String>)

    /** The book folder's own id, once loaded. */
    private var rootId: String? = null

    /** Makes a folder in the one shown. */
    private fun newFolder() = writeAccess.run {
        val uri = treeUri ?: return@run
        val parentId = folderId ?: DocumentsContract.getTreeDocumentId(uri)
        BookFolder.askFolderName(this, adapter.items.filter { it.docId != filesHere?.docId }.map { it.name }) { name ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { BookFolder.createFolder(this@MainActivity, uri, parentId, name) } }
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
        if (isTop) title = getString(R.string.app_name)
        adapter.items = emptyList()
        list.isVisible = false
        empty.isVisible = true
        message.text = text
        choose.isVisible = isTop
    }

    /** Finds out, in the background, which of these folders have folders in them (see [hasFolders]). */
    private fun lookInto(uri: Uri, folders: List<Entry>) {
        lookAhead?.cancel()
        lookAhead = lifecycleScope.launch(Dispatchers.IO) {
            for (folder in folders) {
                if (!isActive) break
                runCatching { Docs.listChildren(this@MainActivity, uri, folder.docId).any { it.isDir } }
                    .onSuccess { hasFolders[folder.docId] = it }
            }
        }
    }

    /** A folder with folders in it opens as cards one level down; otherwise (or the "Files in …" card) its files. */
    private fun openFolder(folder: Entry) {
        val uri = treeUri ?: return
        if (folder.docId == filesHere?.docId) return openFiles(folder)
        // Already looking into a folder after a tap: one at a time.
        if (opening.isVisible) return
        lifecycleScope.launch {
            val deeper = hasFolders[folder.docId] ?: run {
                // Not known yet: show that something is happening while looking.
                opening.isVisible = true
                try {
                    withContext(Dispatchers.IO) {
                        runCatching { Docs.listChildren(this@MainActivity, uri, folder.docId).any { it.isDir } }.getOrDefault(false)
                    }.also { hasFolders[folder.docId] = it }
                } finally {
                    opening.isVisible = false
                }
            }
            if (deeper) {
                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                    .putExtra(EXTRA_DOC_ID, folder.docId)
                    .putStringArrayListExtra(EXTRA_PATH, ArrayList(path + title.toString())))
            } else {
                openFiles(folder)
            }
        }
    }

    private fun openFiles(folder: Entry) {
        startActivity(Intent(this, ChapterActivity::class.java)
            .putExtra(ChapterActivity.EXTRA_TREE, treeUri.toString())
            .putExtra(ChapterActivity.EXTRA_DOC_ID, folder.docId)
            .putExtra(ChapterActivity.EXTRA_PARENT_ID, folderId ?: rootId)
            .putExtra(ChapterActivity.EXTRA_NAME, if (folder.docId == filesHere?.docId) title.toString() else folder.name))
    }

    private fun toggleFavorite(folder: Entry) {
        val root = rootId ?: return
        val key = Favorites.key(root, folder.docId)
        Favorites.set(this, key, folder.docId !in adapter.favorites)
        AutoBackup.saveLater(this)
        load()
    }
}

private class ChapterAdapter(
    private val onClick: (Entry) -> Unit,
    private val onFavorite: (Entry) -> Unit,
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {
    var items: List<Entry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    /** The favourite folders' ids. */
    var favorites: Set<String> = emptySet()
    /** The "Files in …" card's id, which has no star. */
    var filesCard: String? = null

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val icon: ImageView = view.findViewById(R.id.icon)
        val favorite: ImageButton = view.findViewById(R.id.favorite)
        val starTint = ImageViewCompat.getImageTintList(favorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.name.text = item.name
        holder.itemView.setOnClickListener { onClick(item) }
        val isFiles = item.docId == filesCard
        holder.icon.setImageResource(if (isFiles) R.drawable.ic_doc else R.drawable.ic_folder)
        holder.favorite.isVisible = !isFiles
        val isFavorite = item.docId in favorites
        holder.favorite.setImageResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
        ImageViewCompat.setImageTintList(holder.favorite,
            if (isFavorite) ColorStateList.valueOf(context.getColor(R.color.star)) else holder.starTint)
        holder.favorite.contentDescription =
            context.getString(if (isFavorite) R.string.favorite_remove else R.string.favorite_add, item.name)
        holder.favorite.setOnClickListener { onFavorite(item) }
    }
}
