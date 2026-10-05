package com.studybook.reader

import android.content.Context
import android.net.Uri
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
import java.time.LocalDate

/**
 * Writing practice data (history, bookmarks, writing on worksheets, settings): one JSON file per key in the app's
 * own storage, so it is included in Android's backup (unlike the web page's localStorage).
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

/**
 * A backup of everything you've done in the app, as one JSON file you keep (e.g. in Download):
 * writing practice history, bookmarks and writing, and the tracing on chapter files.
 */
object Backup {
    private const val FORMAT = "study-book-backup"

    fun fileName() = "Study Book backup ${LocalDate.now()}.json"

    fun export(context: Context): String {
        val store = TrainingStore(context)
        val training = JSONObject()
        for (key in store.keys()) store.get(key)?.let { runCatching { training.put(key, JSONTokener(it).nextValue()) } }
        val ink = JSONObject()
        File(context.filesDir, "ink").listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach {
            runCatching { ink.put(it.name, JSONObject(it.readText())) }
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("training", training)
            .put("ink", ink)
            .toString()
    }

    class Summary(val words: Int, val tracedFiles: Int)

    /**
     * Adds a backup to what's already here: history and bookmarks are merged, writing and tracing from the backup
     * replace what's here for the same word or file. Throws if the file isn't a Study Book backup.
     */
    fun restore(context: Context, text: String): Summary {
        val json = JSONObject(text)
        require(json.optString("format") == FORMAT) { "not a Study Book backup" }
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

    /** One stored value after restoring: history and bookmarks combine both copies; anything else is the backup's. */
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
            key == "bookmarks" && here is JSONArray && incoming is JSONArray -> {
                val seen = HashSet<String>()
                for (i in 0 until here.length()) seen += here.getString(i)
                for (i in 0 until incoming.length()) if (seen.add(incoming.getString(i))) here.put(incoming.getString(i))
                here.toString()
            }
            else -> if (incoming is String) JSONObject.quote(incoming) else incoming.toString()
        }
    }
}

/** "Back up my work" / "Restore my work": file pickers for [Backup], for any screen that offers them. */
class BackupActions(private val activity: AppCompatActivity, private val onRestored: () -> Unit = {}) {
    private val save = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) write(uri)
    }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri)
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

    private fun read(uri: Uri) = activity.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                Backup.restore(activity, text)
            }
        }
        result.onSuccess {
            toast(activity.getString(R.string.backup_restored, it.words, it.tracedFiles))
            onRestored()
        }.onFailure {
            toast(activity.getString(R.string.backup_not_valid))
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
