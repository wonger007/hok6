package com.studybook.reader

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * The welcome screen on first start: Hok6's name and tag line, what it needs from the internet, a button to get all
 * of it, and Skip. Either way it isn't shown again (Settings can show it again); what's skipped is offered later,
 * when a feature needs it (see [Downloads.ask]).
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var list: DownloadList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        SystemBars.setColor(this, getColor(R.color.brand))
        // A phone on its side: a slim header, so the list has room.
        if (resources.configuration.screenHeightDp < 500) {
            findViewById<View>(R.id.setup_logo).isVisible = false
            findViewById<TextView>(R.id.setup_name).textSize = 28f
            findViewById<View>(R.id.setup_header).setPadding(0, dp(6), 0, dp(10))
        }
        val download = findViewById<MaterialButton>(R.id.setup_download)
        val skip = findViewById<MaterialButton>(R.id.setup_skip)
        val hint = findViewById<View>(R.id.setup_hint)
        val listView = findViewById<LinearLayout>(R.id.setup_list)
        // In the middle of the screen while Hok6 checks or downloads, so it's clear something is happening.
        val busy = findViewById<View>(R.id.setup_busy)
        val busyText = findViewById<TextView>(R.id.setup_busy_text)
        list = DownloadList(this, listView) { ready, checking, downloading ->
            busy.isVisible = checking || downloading
            busyText.setText(if (checking) R.string.setup_checking else R.string.setup_downloading)
            // The list waits until the check is done, so the bar is alone in the middle.
            listView.isVisible = !checking || downloading
            skip.isVisible = !ready
            hint.isVisible = !ready && !checking
            download.isEnabled = ready || !(checking || downloading)
            download.setText(when {
                ready -> R.string.setup_start
                checking && !downloading -> R.string.need_checking
                downloading -> R.string.setup_downloading
                else -> R.string.download_all
            })
        }
        download.setOnClickListener { if (list.allReady) finishSetup() else list.downloadAll() }
        skip.setOnClickListener { finishSetup() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishSetup()
        })
    }

    /** Downloads already started carry on in the background. */
    private fun finishSetup() {
        Downloads.setSetupDone(this)
        finish()
    }
}

/** Settings: downloads (any time), download reminders, light or dark, and the welcome screen again. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setTitle(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val progress = findViewById<LinearProgressIndicator>(R.id.settings_progress)
        val download = findViewById<MaterialButton>(R.id.settings_download)
        val list = DownloadList(this, findViewById(R.id.settings_list)) { ready, checking, downloading ->
            val busy = checking || downloading
            progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            download.isVisible = !ready
            download.isEnabled = !busy
            download.setText(when {
                downloading -> R.string.setup_downloading
                checking -> R.string.need_checking
                else -> R.string.download_all
            })
        }
        download.setOnClickListener { list.downloadAll() }

        findViewById<SwitchMaterial>(R.id.settings_remind).apply {
            isChecked = Downloads.remind(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Downloads.setRemind(this@SettingsActivity, on) }
        }

        val looks = mapOf("system" to R.id.look_system, "light" to R.id.look_light, "dark" to R.id.look_dark)
        val group = findViewById<android.widget.RadioGroup>(R.id.settings_look)
        group.check(looks[Appearance.current(this)] ?: R.id.look_system)
        group.setOnCheckedChangeListener { _, id ->
            val mode = looks.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedChangeListener
            if (mode != Appearance.current(this)) Appearance.set(this, mode)
        }

        findViewById<View>(R.id.settings_show_welcome).setOnClickListener {
            startActivity(android.content.Intent(this, SetupActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
