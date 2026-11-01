package amrts.termuide

import android.annotation.SuppressLint
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import org.json.JSONObject
import kotlin.math.roundToInt

/** Offline terminal renderer. Only the bundled page can access the active session's input bridge. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class ConsoleTerminalView(private val wrappedContext: MutableContextWrapper, private val console: RunConsole,
                                   private val replayThrough: Long) : WebView(wrappedContext) {
    private val id = console.state.value.id
    private var ready = false
    private var released = false
    private var acknowledged = 0L
    private var pending: Long? = null
    private var options = JSONObject()
    private val listener: () -> Unit = { pump() }
    private var errorListener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var pinching = false
    private var scaledTextSize = console.textSize.toFloat()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaledTextSize = console.textSize.toFloat()
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor.isFinite() && factor > 0) {
                scaledTextSize = (scaledTextSize * factor).coerceIn(8f, 32f)
                console.setTextSize(scaledTextSize.roundToInt())
            }
            return true
        }
    }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        if (!pinching && event.pointerCount > 1) {
            pinching = true
            // Cancel WebView's pending scroll/long press before taking ownership of this gesture.
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        scaleDetector.onTouchEvent(event)
        if (pinching) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return super.onTouchEvent(event)
    }

    fun attach(host: FrameLayout, onError: () -> Unit) {
        (parent as? android.view.ViewGroup)?.removeView(this)
        wrappedContext.baseContext = host.context
        errorListener = onError
        host.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    fun detach(host: FrameLayout) {
        if (parent !== host) return
        host.removeView(this)
        clearFocus()
        errorListener = null
        wrappedContext.baseContext = wrappedContext.applicationContext
    }
    private fun failed() {
        if (!valid()) return
        val notify = errorListener
        console.rendererFailed(this)
        notify?.invoke()
    }

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
                if (request.isForMainFrame) failed()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                failed()
                return true
            }
        }
        loadUrl(PAGE)
        main.postDelayed({ if (valid() && !ready) failed() }, 5000)
    }

    fun configure(background: Int, foreground: Int, cursor: Int) {
        setBackgroundColor(background)
        fun css(color: Int) = String.format(java.util.Locale.ROOT, "#%06x", color and 0xffffff)
        options = JSONObject().put("background", css(background)).put("foreground", css(foreground))
            .put("cursor", css(cursor)).put("fontSize", console.textSize)
            .put("language", java.util.Locale.getDefault().toLanguageTag())
            .put("label", context.getString(R.string.console_input))
            .put("omitted", context.getString(R.string.console_output_omitted))
        if (ready) configurePage()
    }
    fun setTextSize(size: Int) {
        options.put("fontSize", size)
        if (ready && valid()) evaluateJavascript("consoleTerminal.setFontSize($size)", null)
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
        val historical = acknowledged < replayThrough
        val batch = console.output.after(acknowledged, if (historical) replayThrough else Long.MAX_VALUE)
        if (batch == null) {
            if (historical) { acknowledged = replayThrough; pump() }
            return
        }
        pending = batch.sequence
        val bytes = JSONObject.quote(Base64.encodeToString(batch.bytes, Base64.NO_WRAP))
        evaluateJavascript("consoleTerminal.write($bytes,${JSONObject.quote(batch.sequence.toString())},${batch.reset},$historical)", null)
    }
    fun release() {
        if (released) return
        released = true
        errorListener = null
        (parent as? android.view.ViewGroup)?.removeView(this)
        wrappedContext.baseContext = wrappedContext.applicationContext
        console.removeOutputObserver(listener)
        removeJavascriptInterface("ConsoleBridge")
        stopLoading()
        destroy()
    }
    private inner class Bridge {
        @JavascriptInterface fun failed() { main.post { this@ConsoleTerminalView.failed() } }
        @JavascriptInterface fun ready() {
            main.post {
                if (valid()) {
                    ready = true
                    console.observeOutput(listener)
                    configurePage()
                }
            }
        }
        @JavascriptInterface fun input(data: String) {
            main.post { if (valid()) console.input(data) }
        }
        @JavascriptInterface fun resize(columns: Int, rows: Int) { main.post { if (valid()) console.resize(columns, rows) } }
        @JavascriptInterface fun rendered(sequence: String) {
            main.post {
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
