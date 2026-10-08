package com.vdelaar.mylibby.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream

enum class SelectionAction(val id: Int, @androidx.annotation.StringRes val label: Int) {
    HIGHLIGHT(1001, com.vdelaar.mylibby.R.string.sel_highlight),
    NOTE(1002, com.vdelaar.mylibby.R.string.sel_note),
    COPY(1003, com.vdelaar.mylibby.R.string.sel_copy),
    SHARE(1004, com.vdelaar.mylibby.R.string.sel_share),
    SPEAK(1005, com.vdelaar.mylibby.R.string.sel_speak),
}

/**
 * WebView hosting foliate-js. Assets and book files are served from a virtual https origin
 * (no file:// access), and the text selection menu is replaced with reader actions.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(
    context: Context,
    private val onEvent: (String, String) -> Unit,
    private val onSelectionAction: (SelectionAction) -> Unit,
) : WebView(context) {

    private val main = Handler(Looper.getMainLooper())

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", TypedAssetsHandler(context))
        .addPathHandler("/books/", FileHandler(File(context.filesDir, "books")))
        .addPathHandler("/cache/", FileHandler(File(context.cacheDir, "books")))
        .addPathHandler("/local/", FileHandler(File(context.filesDir, "local")))
        .build()

    init {
        // WRAP_CONTENT (Compose's default) makes WebView size its viewport from content: 100vh = 0.
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.textZoom = 100
        settings.setSupportZoom(false)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "MyLibby")
        if (com.vdelaar.mylibby.BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("MyLibbyReader", "${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // Don't take the whole app down if the WebView renderer crashes or is killed.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { onEvent("error", kotlinx.serialization.json.buildJsonObject { put("message", kotlinx.serialization.json.JsonPrimitive(context.getString(com.vdelaar.mylibby.R.string.reader_crashed))) }.toString()) }
                return true
            }

            // Chapters load in blob: iframes, which also pass through here; only block the
            // main frame from navigating away from the reader page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && request.url.host != "appassets.androidplatform.net"
        }
    }

    fun run(script: String) = main.post { evaluateJavascript(script, null) }

    private inner class Bridge {
        @JavascriptInterface
        fun post(type: String, json: String) {
            main.post { onEvent(type, json) }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(SelectionCallback(callback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(SelectionCallback(callback))

    private inner class SelectionCallback(private val wrapped: ActionMode.Callback?) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            wrapped?.onCreateActionMode(mode, menu)
            menu.clear()
            SelectionAction.entries.forEachIndexed { i, a -> menu.add(Menu.NONE, a.id, i, context.getString(a.label)) }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries.firstOrNull { it.id == item.itemId } ?: return false
            onSelectionAction(action)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            wrapped?.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }
}

private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "js", "mjs" -> "text/javascript"
    "html" -> "text/html"
    "css" -> "text/css"
    "woff2" -> "font/woff2"
    "woff" -> "font/woff"
    "ttf" -> "font/ttf"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "epub" -> "application/epub+zip"
    else -> "application/octet-stream"
}

/** Serves app assets with correct MIME types (ES modules require text/javascript). */
private class TypedAssetsHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = try {
        WebResourceResponse(mimeFor(path), if (mimeFor(path).startsWith("text")) "utf-8" else null, context.assets.open(path))
    } catch (_: Exception) {
        WebResourceResponse("text/plain", null, 404, "Not found", null, null)
    }
}

/** Serves a downloaded book file from app-private storage. */
private class FileHandler(private val dir: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val file = File(dir, path)
        if (!file.canonicalPath.startsWith(dir.canonicalPath) || !file.exists()) {
            return WebResourceResponse("text/plain", null, 404, "Not found", null, null)
        }
        return WebResourceResponse(mimeFor(path), null, FileInputStream(file))
    }
}
