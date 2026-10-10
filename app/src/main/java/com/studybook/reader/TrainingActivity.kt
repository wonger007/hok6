package com.studybook.reader

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Writing practice: worksheets with stroke order (Hanzi Writer), written on with finger or stylus,
 * printable or saved as PDF. The UI is the offline web app in assets/training.
 */
class TrainingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        /** With EXTRA_TEXT: open a worksheet for those characters straight away. */
        const val EXTRA_AUTO_START = "auto_start"
        private const val HOST_URL = "https://appassets.androidplatform.net/assets/training/index.html"
    }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var exportPdf: PdfDocument? = null
    private var exportFailed = false
    private var exportName = "worksheet"
    private val store by lazy { TrainingStore(this) }
    // After a restore, reload the page so it shows the restored history and writing.
    private val backup = BackupActions(this) { web.reload() }
    private var pendingSpeech: Triple<String, String, Float>? = null
    private val handwriting = Handwriting()
    /** Languages whose missing voice or handwriting has been offered on this visit, so it's asked only once. */
    private val askedVoice = HashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.setColor(this, getColor(R.color.bar_status))
        // Debug builds can be inspected from Chrome DevTools (chrome://inspect).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        // The page takes a second to load; Hok6's splash covers the blank screen until it's there.
        val root = FrameLayout(this)
        root.addView(web)
        val splashView = layoutInflater.inflate(R.layout.splash_overlay, root, false)
        root.addView(splashView)
        setContentView(root)
        val splash = Splash(splashView, minMs = 400)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // Stroke data, one character per request: /hanzi/<hex code point>, or /hanzi/ja/<hex> (Japanese) and
            // /hanzi/ko/<hex> (Korean hanja)
            .addPathHandler("/hanzi/") { path -> strokeData(path) }
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        // Text follows Android's font size setting, like the rest of Hok6 (a web page ignores it otherwise).
        web.settings.textZoom = (resources.configuration.fontScale * 100).roundToInt()
        // The page's confirm() and alert() as Hok6's own dialogs (the WebView's name the page's web address).
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@TrainingActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@TrainingActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = splash.done()

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        if (savedInstanceState == null) web.loadUrl(HOST_URL) else web.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.handleBack && handleBack()") { handled ->
                    if (handled != "true") finish()
                }
            }
        })
    }

    private val hanzi by lazy { HanziBundle.open(this) }
    private val kanji by lazy { HanziBundle.open(this, HanziBundle.JAPANESE) }
    private val hanja by lazy { HanziBundle.open(this, HanziBundle.KOREAN) }

    private fun strokeData(path: String): WebResourceResponse {
        val bundle = when (path.substringBefore('/', "")) {
            "ja" -> kanji
            "ko" -> hanja
            else -> hanzi
        }
        val json = path.substringAfterLast('/').toIntOrNull(16)?.let { bundle.json(it) }
        return if (json != null) {
            WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(json))
        } else {
            WebResourceResponse("application/json", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onStop() {
        super.onStop()
        AutoBackup.saveLater(this)
    }

    override fun onDestroy() {
        tts?.shutdown()
        handwriting.close()
        web.destroy()
        super.onDestroy()
    }

    private fun speakNow(text: String, lang: String, rate: Float) {
        val engine = tts ?: return
        val locale = Voices.find(engine, lang)
        if (locale == null) return voiceMissing(lang)
        engine.language = locale
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak")
    }

    /** Offers to add the voice, once per visit to this screen; a short note otherwise (or once reminders are off). */
    private fun voiceMissing(lang: String) {
        if (Downloads.remind(this) && askedVoice.add(lang)) {
            Downloads.ask(this, Downloads.Need.VOICE, lang) { Voices.install(this) }
            return
        }
        val name = Downloads.name(this, lang)
        Toast.makeText(this, getString(R.string.tts_missing, name), Toast.LENGTH_LONG).show()
    }

    private fun recognizeNow(id: Int, lang: String, strokes: String, width: Float, height: Float) {
        handwriting.recognize(lang, JSONArray(strokes), width, height) { result ->
            val json = when (result) {
                is Handwriting.Result.Candidates -> JSONObject().put("candidates", JSONArray(result.texts))
                Handwriting.Result.Downloading -> JSONObject().put("downloading", true)
                Handwriting.Result.Missing -> JSONObject().put("missing", true)
                is Handwriting.Result.Failed -> JSONObject().put("error", result.message)
            }
            if (isDestroyed) return@recognize
            // Missing: the page offers ⬇ Download handwriting under the pad (no pop-up).
            web.evaluateJavascript("window.inkResult && inkResult($id, $json)", null)
        }
    }

    private fun print(title: String) {
        // The saved PDF is named after the job. Samsung's Save as PDF treats anything after a dot as the extension
        // ("K1 Ch.1 Homework" became "K1 Ch.PDF"), so dots become spaces.
        val jobName = title.replace('.', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { "worksheet" }
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val inner = web.createPrintDocumentAdapter(jobName)
        // Wrap the adapter so the page can restore itself (e.g. show the writing again) once printing ends.
        val adapter = object : PrintDocumentAdapter() {
            override fun onStart() = inner.onStart()
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?,
            ) = inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

            override fun onWrite(
                pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback,
            ) = inner.onWrite(pages, destination, cancellationSignal, callback)

            override fun onFinish() {
                inner.onFinish()
                web.evaluateJavascript("window.afterPrint && afterPrint()", null)
            }
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, adapter, attributes)
    }

    /** Methods the web page calls as `Android.*`. They arrive on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun speak(text: String, lang: String) = speakAt(text, lang, 1f)

        /** [rate] 1 is normal speed; lower is slower. */
        @JavascriptInterface
        fun speakAt(text: String, lang: String, rate: Float): Unit = runOnUiThread {
            // The first time, say that Hok6 speaks with Google's voices rather than the tablet's own engine.
            if (Voices.needsNotice(this@TrainingActivity)) {
                return@runOnUiThread Voices.noticeSwitch(this@TrainingActivity) {
                    if (!Voices.useGoogle(this@TrainingActivity)) { tts?.shutdown(); tts = null; ttsReady = false }
                    speakAt(text, lang, rate)
                }
            }
            if (tts == null) {
                tts = Voices.open(this@TrainingActivity) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    pendingSpeech?.let { (t, l, r) -> if (ttsReady) speakNow(t, l, r) }
                    pendingSpeech = null
                }
            }
            if (ttsReady) speakNow(text, lang, rate) else pendingSpeech = Triple(text, lang, rate)
        }

        /** Stylus or finger (see [Stylus]): whether the screen takes a stylus, and who writes. */
        @JavascriptInterface
        fun stylusSupported() = Stylus.supported(this@TrainingActivity)

        @JavascriptInterface
        fun fingersDraw() = Stylus.fingersDraw(this@TrainingActivity)

        @JavascriptInterface
        fun setFingersDraw(on: Boolean) = Stylus.setFingersDraw(this@TrainingActivity, on)

        /** A stylus touched the page; true the first time ever (Hok6 then switches to stylus mode). */
        @JavascriptInterface
        fun stylusUsed() = Stylus.used(this@TrainingActivity)

        @JavascriptInterface
        fun setLanguage(lang: String) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("language", lang).apply()
        }

        @JavascriptInterface
        fun print(jobName: String) = runOnUiThread { this@TrainingActivity.print(jobName) }

        @JavascriptInterface
        fun close() = runOnUiThread { finish() }

        /**
         * Handwriting pad: recognises [strokes] (JSON `[[x, y, t, …], …]` on a [width] × [height] area) and answers
         * with `inkResult(id, {candidates: [...]} | {downloading: true} | {missing: true} | {error: "..."})`.
         * When the model is missing it offers the download, and answers again once it's done.
         */
        /** ⬇ Download handwriting on the page: gets [lang]'s recognition, then tells the page (handReady). */
        @JavascriptInterface
        fun downloadHandwriting(lang: String) = runOnUiThread {
            Downloads.downloadHandwriting(lang) { error ->
                if (!isDestroyed) web.evaluateJavascript("window.handReady && handReady('$lang', ${error == null})", null)
            }
        }

        @JavascriptInterface
        fun recognizeInk(id: Int, lang: String, strokes: String, width: Float, height: Float) = runOnUiThread {
            recognizeNow(id, lang, strokes, width, height)
        }

        @JavascriptInterface
        fun settings() = runOnUiThread { startActivity(Intent(this@TrainingActivity, SettingsActivity::class.java)) }

        @JavascriptInterface
        fun about() = runOnUiThread { startActivity(Intent(this@TrainingActivity, AboutActivity::class.java)) }

        @JavascriptInterface
        fun version() = Updates.version(this@TrainingActivity)

        /** Share PDF: the page sends each worksheet page as shapes and text (see [PageDrawing]), then [shareFinish] opens the share menu. */
        @JavascriptInterface
        fun shareStart(name: String) {
            exportName = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "worksheet" }
            exportPdf?.close()
            exportPdf = PdfDocument()
            exportFailed = false
        }

        @JavascriptInterface
        fun sharePage(drawing: String) {
            val pdf = exportPdf ?: return
            // US Letter in points.
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, pdf.pages.size + 1).create())
            try {
                PageDrawing.draw(page.canvas, drawing, 612f)
            } catch (e: Exception) {
                exportFailed = true
            } finally {
                pdf.finishPage(page)
            }
        }

        @JavascriptInterface
        fun shareFinish() {
            val pdf = exportPdf ?: return
            exportPdf = null
            val file = if (exportFailed) {
                pdf.close()
                null
            } else try {
                // Only the latest export is kept, in the app's cache, for the app it was shared with to read.
                val dir = File(cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
                File(dir, "$exportName.pdf").also { out -> out.outputStream().use { pdf.writeTo(it) } }
            } catch (e: Exception) {
                null
            } finally {
                pdf.close()
            }
            runOnUiThread {
                if (file == null) {
                    Toast.makeText(this@TrainingActivity, R.string.share_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this@TrainingActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, exportName)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share_worksheet)))
            }
        }

        /** Writing practice data, kept in app storage (see [TrainingStore]); null when there's none. */
        @JavascriptInterface
        fun storeGet(key: String): String? = store.get(key)

        @JavascriptInterface
        fun storeSet(key: String, value: String) = store.set(key, value)

        @JavascriptInterface
        fun storeDel(key: String) = store.delete(key)

        @JavascriptInterface
        fun backUp() = runOnUiThread { backup.backUp() }

        @JavascriptInterface
        fun restore() = runOnUiThread { backup.restore() }



        @JavascriptInterface
        fun initialText(): String = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        @JavascriptInterface
        fun initialTitle(): String = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        /** Screen pixels (CSS px) per real millimetre, so on-screen boxes can match their printed size. */
        @JavascriptInterface
        fun cssPxPerMm(): Double = resources.displayMetrics.let { it.xdpi / 25.4 / it.density }.toDouble()

        @JavascriptInterface
        fun isPhone(): Boolean = resources.configuration.smallestScreenWidthDp < 600

        @JavascriptInterface
        fun autoStart(): Boolean = intent.getBooleanExtra(EXTRA_AUTO_START, false)
    }
}
