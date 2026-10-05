package com.studybook.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Writing practice data (history, bookmarks, writing on worksheets, settings): one JSON file per key in the app's
 * own storage, so it is in Hok6's backups and moves to a new device (unlike the web page's localStorage).
 */
class TrainingStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR))

    private fun file(key: String) = File(dir, URLEncoder.encode(key, "UTF-8") + ".json")

    fun get(key: String): String? = synchronized(lock) { file(key).takeIf { it.isFile }?.readText() }

    fun set(key: String, value: String) = synchronized(lock) {
        dir.mkdirs()
        val target = file(key)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(value)
        tmp.renameTo(target)
        Unit
    }

    fun delete(key: String) = synchronized(lock) { file(key).delete(); Unit }

    fun keys(): List<String> = synchronized(lock) {
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".json") }
            .map { URLDecoder.decode(it.removeSuffix(".json"), "UTF-8") }
    }

    companion object {
        const val DIR = "training"
        private val lock = Any()
    }
}

/** Characters and words set aside for the quiz in writing practice: a JSON list in [TrainingStore], oldest first. */
object QuizStash {
    const val KEY = "quiz"

    /** Adds the ones not there yet; returns how many were added and how many are in the quiz now. */
    fun add(context: Context, items: List<String>): Pair<Int, Int> {
        val store = TrainingStore(context)
        val list = runCatching { JSONArray(store.get(KEY) ?: "[]") }.getOrDefault(JSONArray())
        val have = (0 until list.length()).mapTo(HashSet()) { list.getString(it) }
        var added = 0
        for (item in items) if (have.add(item)) {
            list.put(item)
            added++
        }
        if (added > 0) store.set(KEY, list.toString())
        return added to list.length()
    }
}

/**
 * A backup of everything you've done in the app, as one JSON file you keep (e.g. in Download):
 * writing practice history, bookmarks and writing, and the tracing on chapter files.
 */
object Backup {
    private const val FORMAT = "study-book-backup"

    fun fileName() = "Hok6 backup ${LocalDate.now()}.json"

    fun export(context: Context): String = export(contents(context.filesDir))

    fun export(contents: JSONObject): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("created", System.currentTimeMillis())
        .put("training", contents.getJSONObject("training"))
        .put("ink", contents.getJSONObject("ink"))
        .toString()

    /** Everything a backup holds, in the same order each time so two copies of the same work compare equal. */
    fun contents(filesDir: File): JSONObject {
        val store = TrainingStore(File(filesDir, TrainingStore.DIR))
        val training = JSONObject()
        for (key in store.keys().sorted()) store.get(key)?.let { runCatching { training.put(key, JSONTokener(it).nextValue()) } }
        val ink = JSONObject()
        File(filesDir, "ink").listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }.forEach {
            runCatching { ink.put(it.name, JSONObject(it.readText())) }
        }
        return JSONObject().put("training", training).put("ink", ink)
    }

    fun isEmpty(contents: JSONObject) =
        contents.getJSONObject("training").length() == 0 && contents.getJSONObject("ink").length() == 0

    class Summary(val words: Int, val tracedFiles: Int)

    /**
     * Adds a backup to what's already here: history, bookmarks and the quiz are merged, writing and tracing from the backup
     * replace what's here for the same word or file. Throws if the file isn't a Hok6 (or Study Book) backup.
     */
    fun restore(context: Context, text: String): Summary {
        val json = JSONObject(text)
        require(json.optString("format") == FORMAT) { "not a Hok6 backup" }
        val store = TrainingStore(context)
        val training = json.optJSONObject("training") ?: JSONObject()
        for (key in training.keys()) {
            store.set(key, merge(key, store.get(key), training.get(key)))
        }
        val ink = json.optJSONObject("ink") ?: JSONObject()
        val inkDir = File(context.filesDir, "ink").apply { mkdirs() }
        var traced = 0
        for (name in ink.keys()) {
            // Only plain file names, as written by InkDocument.
            if (!name.matches(Regex("[0-9a-f]+\\.json"))) continue
            File(inkDir, name).writeText(ink.getJSONObject(name).toString())
            traced++
        }
        val words = JSONObject(store.get("history") ?: "{}").length()
        return Summary(words, traced)
    }

    /** One stored value after restoring: history, bookmarks and the quiz combine both copies; anything else is the backup's. */
    fun merge(key: String, local: String?, incoming: Any): String {
        val here = local?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
        return when {
            key == "history" && here is JSONObject && incoming is JSONObject -> {
                // The entry practised most recently wins.
                for (word in incoming.keys()) {
                    val theirs = incoming.getJSONObject(word)
                    val ours = here.optJSONObject(word)
                    if (ours == null || theirs.optLong("last") > ours.optLong("last")) here.put(word, theirs)
                }
                here.toString()
            }
            (key == "bookmarks" || key == QuizStash.KEY) && here is JSONArray && incoming is JSONArray -> {
                val seen = HashSet<String>()
                for (i in 0 until here.length()) seen += here.getString(i)
                for (i in 0 until incoming.length()) if (seen.add(incoming.getString(i))) here.put(incoming.getString(i))
                here.toString()
            }
            else -> if (incoming is String) JSONObject.quote(incoming) else incoming.toString()
        }
    }
}

/**
 * A backup kept in the book folder, [FILE_NAME], brought up to date whenever a screen is left after something
 * changed. It stays when Hok6 is uninstalled, and choosing the book folder after reinstalling offers to restore it.
 */
object AutoBackup {
    const val FILE_NAME = "Hok6 backup.json"
    /** What was last written, so an unchanged backup isn't written again. */
    private const val KEY_HASH = "auto_backup_hash"
    /** The book folder whose backup file Hok6 may replace: one it wrote, or one the user decided about. */
    private const val KEY_FOLDER = "auto_backup_folder"

    /** Brings the backup file up to date in the background, after any tracing still being saved. */
    fun saveLater(context: Context) {
        val app = context.applicationContext
        InkDocument.afterSaves { runCatching { save(app) } }
    }

    private fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tree = prefs.getString(BookFolder.KEY_ROOT, null)?.let(Uri::parse) ?: return
        if (!BookFolder.canWrite(context, tree)) return
        val contents = Backup.contents(context.filesDir)
        // Nothing done yet (e.g. just reinstalled): keep the backup that's there.
        if (Backup.isEmpty(contents)) return
        val hash = MessageDigest.getInstance("SHA-1").digest((tree.toString() + contents).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (prefs.getString(KEY_HASH, null) == hash) return
        val existing = find(context, tree)
        // A backup this install hasn't written or been asked about waits until the user decides whether to restore it.
        if (existing != null && prefs.getString(KEY_FOLDER, null) != tree.toString()) return
        val target = existing ?: DocumentsContract.createDocument(context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            "application/json", FILE_NAME) ?: return
        context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(Backup.export(contents).toByteArray()) }
        prefs.edit().putString(KEY_HASH, hash).putString(KEY_FOLDER, tree.toString()).apply()
    }

    /** The backup file in the book folder, if there is one. */
    fun find(context: Context, tree: Uri): Uri? =
        Docs.listChildren(context, tree, DocumentsContract.getTreeDocumentId(tree))
            .firstOrNull { !it.isDir && it.name == FILE_NAME }?.uri

    /** A backup in the book folder that this install hasn't written or asked about: the user may want it restored. */
    fun waitingToRestore(context: Context, tree: Uri): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FOLDER, null) == tree.toString()) return null
        return find(context, tree)
    }

    /** The user restored the folder's backup or chose not to; from now on it's replaced by this install's work. */
    fun decided(context: Context, tree: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FOLDER, tree.toString()).apply()
        saveLater(context)
    }
}

/** "Back up my work" / "Restore my work": file pickers for [Backup], for any screen that offers them. */
class BackupActions(private val activity: AppCompatActivity, private val onRestored: () -> Unit = {}) {
    private val save = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) write(uri)
    }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom(uri)
    }

    fun backUp() = save.launch(Backup.fileName())

    fun restore() = open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    private fun write(uri: Uri) = activity.lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(activity).toByteArray()) }
            }.isSuccess
        }
        toast(if (ok) activity.getString(R.string.backup_saved) else activity.getString(R.string.backup_failed))
    }

    /** Restores the backup at [uri]; [onSuccess] runs before the book folder's backup file is brought up to date. */
    fun restoreFrom(uri: Uri, onSuccess: () -> Unit = {}) = activity.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                Backup.restore(activity, text)
            }
        }
        result.onSuccess {
            toast(activity.getString(R.string.backup_restored, it.words, it.tracedFiles))
            onSuccess()
            AutoBackup.saveLater(activity)
            onRestored()
        }.onFailure {
            toast(activity.getString(R.string.backup_not_valid))
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
