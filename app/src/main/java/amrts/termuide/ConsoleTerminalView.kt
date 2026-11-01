package amrts.termuide

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import org.json.JSONObject

/** Offline terminal renderer. Only the bundled page can access the active session's input bridge. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class ConsoleTerminalView(context: Context, private val console: RunConsole, private val onError: () -> Unit) : WebView(context) {
    private val id = console.state.value.id
    private var ready = false
    private var released = false
    private var acknowledged = 0L
    private var pending: Long? = null
    private var options = JSONObject()
    private val listener: () -> Unit = { pump() }

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        isFocusableInTouchMode = true
        addJavascriptInterface(Bridge(), "ConsoleBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url.toString()
                if (url !in ASSETS) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))
                val name = url.removePrefix(ROOT)
                val mime = when (name.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "css" -> "text/css"
                    else -> "application/javascript"
                }
                return WebResourceResponse(mime, "utf-8", context.assets.open("console/$name"))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && valid()) onError()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (valid()) {
                    onError()
                    (parent as? android.view.ViewGroup)?.removeView(this@ConsoleTerminalView)
                    release()
                }
                return true
            }
        }
        loadUrl(PAGE)
        postDelayed({ if (valid() && !ready) onError() }, 5000)
    }

    fun configure(background: Int, foreground: Int, cursor: Int) {
        setBackgroundColor(background)
        fun css(color: Int) = String.format(java.util.Locale.ROOT, "#%06x", color and 0xffffff)
        options = JSONObject().put("background", css(background)).put("foreground", css(foreground))
            .put("cursor", css(cursor)).put("fontSize", Application.textsize.coerceIn(8, 32))
            .put("language", java.util.Locale.getDefault().toLanguageTag())
            .put("label", context.getString(R.string.console_input))
            .put("omitted", context.getString(R.string.console_output_omitted))
        if (ready) configurePage()
    }
    private fun valid() = !released && console.state.value.id == id
    private fun configurePage() {
        evaluateJavascript("consoleTerminal.configure($options)") {
            if (valid()) { updateInput(); pump() }
        }
    }
    fun updateInput() {
        if (ready && valid()) evaluateJavascript("consoleTerminal.enable(${console.state.value.phase == RunConsole.Phase.RUNNING})", null)
    }
    private fun pump() {
        if (!ready || !valid() || pending != null) return
        val batch = console.output.after(acknowledged) ?: return
        pending = batch.sequence
        val bytes = JSONObject.quote(Base64.encodeToString(batch.bytes, Base64.NO_WRAP))
        evaluateJavascript("consoleTerminal.write($bytes,${JSONObject.quote(batch.sequence.toString())},${batch.reset})", null)
    }
    fun release() {
        if (released) return
        released = true
        console.removeOutputObserver(listener)
        removeJavascriptInterface("ConsoleBridge")
        stopLoading()
        destroy()
    }
    private inner class Bridge {
        @JavascriptInterface fun ready() {
            post {
                if (valid()) {
                    ready = true
                    console.observeOutput(listener)
                    configurePage()
                }
            }
        }
        @JavascriptInterface fun input(data: String) {
            post { if (valid()) console.input(data) }
        }
        @JavascriptInterface fun resize(columns: Int, rows: Int) { post { if (valid()) console.resize(columns, rows) } }
        @JavascriptInterface fun rendered(sequence: String) {
            post {
                if (valid() && pending?.toString() == sequence) {
                    acknowledged = pending!!
                    pending = null
                    pump()
                }
            }
        }
    }
    companion object {
        private const val ROOT = "https://termuide.invalid/console/"
        private const val PAGE = ROOT + "index.html"
        private val ASSETS = setOf(PAGE, ROOT + "xterm.js", ROOT + "xterm.css", ROOT + "addon-fit.js", ROOT + "console.css", ROOT + "console.js")
    }
}
