package com.studybook.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** About Hok6: its name and version, Check for updates, links (guide, privacy, source), device tips and credits. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        setTitle(R.string.about)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, Updates.version(this))
        val check = findViewById<MaterialButton>(R.id.about_check)
        val busy = findViewById<View>(R.id.about_checking)
        check.setOnClickListener {
            check.isEnabled = false
            busy.isVisible = true
            Updates.check(this) {
                check.isEnabled = true
                busy.isVisible = false
            }
        }
        findViewById<View>(R.id.about_guide).setOnClickListener { open(this, Updates.REPO + "/blob/main/USER_GUIDE.md") }
        findViewById<View>(R.id.about_privacy).setOnClickListener { open(this, Updates.REPO + "/blob/main/PRIVACY.md") }
        findViewById<View>(R.id.about_source).setOnClickListener { open(this, Updates.REPO) }

        val html = assets.open("about.html").bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.about_text).apply {
            text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** Opens a web page in the browser. */
        fun open(context: Context, url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, url, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Check for updates, only when asked: through Google Play when Hok6 came from there (its in-app update), else from
 * the latest release on GitHub (for an APK installed from there).
 */
object Updates {
    const val REPO = "https://github.com/wonger007/hok6"
    private const val LATEST = "https://api.github.com/repos/wonger007/hok6/releases/latest"
    private const val PLAY = "com.android.vending"

    fun version(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.versionName} ($code)"
    }

    private fun versionName(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    private fun fromPlay(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == PLAY
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName) == PLAY
    }.getOrDefault(false)

    /** Checks, then says what it found (or starts Google Play's update); [done] runs when the check is over. */
    fun check(activity: AppCompatActivity, done: () -> Unit) {
        if (fromPlay(activity)) checkPlay(activity, done) else checkGitHub(activity, done)
    }

    private fun checkPlay(activity: AppCompatActivity, done: () -> Unit) {
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                done()
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    manager.startUpdateFlow(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
                } else if (available) {
                    AboutActivity.open(activity, "market://details?id=${activity.packageName}")
                } else {
                    upToDate(activity)
                }
            }
            .addOnFailureListener {
                done()
                // Play couldn't say (e.g. offline): its page for Hok6 shows Update when there is one.
                AboutActivity.open(activity, "https://play.google.com/store/apps/details?id=${activity.packageName}")
            }
    }

    private fun checkGitHub(activity: AppCompatActivity, done: () -> Unit) {
        activity.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(LATEST).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    try {
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            done()
            if (activity.isFinishing) return@launch
            if (latest == null) {
                Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val tag = latest.optString("tag_name").removePrefix("v")
            if (newer(tag, versionName(activity))) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.update_available, tag))
                    .setMessage(R.string.update_github)
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        AboutActivity.open(activity, latest.optString("html_url", "$REPO/releases/latest"))
                    }
                    .setNegativeButton(R.string.ask_not_now, null)
                    .show()
            } else {
                upToDate(activity)
            }
        }
    }

    private fun upToDate(activity: AppCompatActivity) =
        Toast.makeText(activity, activity.getString(R.string.update_none, versionName(activity)), Toast.LENGTH_LONG).show()

    /** Whether version [a] (e.g. "1.15") is newer than [b] ("1.14"), comparing each number in turn. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
