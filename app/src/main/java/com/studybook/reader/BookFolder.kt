package com.studybook.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The book folder the user picked: keeping access to it, and changing it (moving files, making chapter folders). */
object BookFolder {
    const val KEY_ROOT = "root_uri"
    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    /** Characters Android storage doesn't allow in a file name. */
    private val BAD_NAME_CHARS = Regex("[/\\\\:*?\"<>|]")

    /** Keeps access to a newly picked folder across restarts, and gives up access to folders picked before. */
    fun keep(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(uri, READ_WRITE)
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(it.uri, flags) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROOT, uri.toString()).apply()
    }

    fun canWrite(context: Context, uri: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** Makes a sub-folder and returns it. */
    fun createFolder(context: Context, treeUri: Uri, parentDocId: String, name: String): Entry {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(context.contentResolver, parent, Document.MIME_TYPE_DIR, name)
            ?: error("the folder couldn't be made")
        val id = DocumentsContract.getDocumentId(created)
        return Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), id,
            Docs.displayName(context, treeUri, id) ?: name, Document.MIME_TYPE_DIR, true)
    }

    /** Moves a file into another folder of the book, with its tracing and last page read, and returns its new address. */
    fun move(context: Context, treeUri: Uri, file: Entry, fromDocId: String, toDocId: String): Uri {
        val resolver = context.contentResolver
        val from = DocumentsContract.buildDocumentUriUsingTree(treeUri, fromDocId)
        val to = DocumentsContract.buildDocumentUriUsingTree(treeUri, toDocId)
        val moved = runCatching { DocumentsContract.moveDocument(resolver, file.uri, from, to) }.getOrNull()
            ?: copyThenDelete(context, file, to)
        // Same form of address as Docs.listChildren gives, so saved work is found under it.
        val newUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(moved))
        InkDocument.rename(context, file.uri, newUri)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldPage = pageKey(file.uri)
        if (prefs.contains(oldPage)) prefs.edit().putInt(pageKey(newUri), prefs.getInt(oldPage, 0)).remove(oldPage).apply()
        return newUri
    }

    /** For storage that can't move files itself. */
    private fun copyThenDelete(context: Context, file: Entry, to: Uri): Uri {
        val resolver = context.contentResolver
        val copy = DocumentsContract.createDocument(resolver, to, file.mime.ifEmpty { "application/octet-stream" }, file.name)
            ?: error("couldn't copy ${file.name}")
        try {
            resolver.openInputStream(file.uri)!!.use { input -> resolver.openOutputStream(copy)!!.use { input.copyTo(it) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            throw e
        }
        DocumentsContract.deleteDocument(resolver, file.uri)
        return copy
    }

    /** Asks for a new folder's name; [existing] names are refused. */
    fun askFolderName(activity: AppCompatActivity, existing: Collection<String>, onName: (String) -> Unit) {
        val taken = existing.map { it.lowercase() }.toSet()
        askName(activity, activity.getString(R.string.new_folder_title), "", activity.getString(R.string.folder_name),
            R.string.create, refuse = { if (it.lowercase() in taken) activity.getString(R.string.folder_exists, it) else null },
            onName = onName)
    }

    /**
     * A dialog asking for a file or folder name. [extension] (e.g. ".pdf") is shown after the box and not typed;
     * [quickNames] are buttons that fill in the box; [refuse] gives a reason a name can't be used, or null.
     */
    fun askName(
        activity: AppCompatActivity,
        title: String,
        initial: String,
        hint: String,
        action: Int,
        message: String? = null,
        extension: String? = null,
        quickNames: List<String> = emptyList(),
        refuse: (String) -> String? = { null },
        onName: (String) -> Unit,
    ) {
        val dp = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            setSingleLine()
            this.hint = hint
            setText(initial)
            // Keeps the dialog in view (not a full-screen keyboard in landscape), so errors under the name show.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val pad = (24 * dp).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        // Quick names first: on a phone in landscape the keyboard can hide what's below the name.
        if (quickNames.isNotEmpty()) box.addView(ChipGroup(activity).apply {
            for (name in quickNames) addView(Chip(activity).apply {
                text = name
                setOnClickListener {
                    input.setText(name)
                    input.setSelection(name.length)
                }
            })
        })
        box.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (extension != null) addView(TextView(activity).apply {
                text = extension
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
        })
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(action, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            // The keyboard's Done key does the same as the button.
            input.setOnEditorActionListener { _, _, _ -> ok.performClick() }
            ok.setOnClickListener {
                val name = input.text.toString().trim()
                input.error = when {
                    name.isEmpty() -> activity.getString(R.string.folder_name_empty)
                    name.startsWith(".") || BAD_NAME_CHARS.containsMatchIn(name) -> activity.getString(R.string.folder_name_bad)
                    else -> refuse(name)
                }
                if (input.error == null) {
                    dialog.dismiss()
                    onName(name)
                }
            }
        }
        // With a name already filled in, the keyboard waits until the box is tapped, so the buttons stay in view.
        if (initial.isEmpty()) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        if (initial.isEmpty()) input.requestFocus() else input.setSelection(input.text.length)
    }
}

/**
 * Makes sure the app may change the book folder before running an action. Earlier versions kept only permission to
 * read it; then the user is asked to choose the same folder again so Android asks them to allow changes.
 */
class WriteAccess(private val activity: AppCompatActivity, private val treeUri: () -> Uri?) {
    private var pending: (() -> Unit)? = null
    private val pick = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val action = pending
        pending = null
        if (uri == null) return@registerForActivityResult
        if (uri != treeUri()) {
            Toast.makeText(activity, R.string.wrong_folder, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        BookFolder.keep(activity, uri)
        action?.invoke()
    }

    fun run(action: () -> Unit) {
        val uri = treeUri() ?: return
        if (BookFolder.canWrite(activity, uri)) return action()
        pending = action
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.need_write)
            .setPositiveButton(R.string.choose_again) { _, _ ->
                pick.launch(DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
