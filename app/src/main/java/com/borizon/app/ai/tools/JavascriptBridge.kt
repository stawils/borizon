package com.borizon.app.ai.tools

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Handles JavaScript skill execution via a hidden WebView.
 *
 * Uses "borizon_skill_execute" as the JS entry point and "BorizonBridge"
 * as the JavaScript interface name .
 */
class JavascriptBridge(private val context: Context) {

    companion object {
        private const val TAG = "JavascriptBridge"
        private const val LOCAL_URL_BASE = "https://appassets.androidplatform.net"
        private const val JS_TIMEOUT_MS = 60_000L
        private const val FN_POLL_TIMEOUT_MS = 10_000L
    }

    private var webView: WebView? = null
    private var pageLoadCallback: (() -> Unit)? = null
    private val executionMutex = Mutex()
    private val bridgeInterface = object {
        @JavascriptInterface
        fun onResultReady(result: String) {
            resultListener?.invoke(result)
        }
        var resultListener: ((String) -> Unit)? = null
    }

    /** Create the WebView on the main thread during init. */
    init {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (webView == null) {
                webView = createWebView()
            }
        }
    }

    /** Build the WebView with WebViewAssetLoader. Must be called on main thread. */
    private fun createWebView(): WebView {
        val skillDir = File(context.filesDir, "skills").also { it.mkdirs() }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/skills/", WebViewAssetLoader.InternalStoragePathHandler(context, skillDir))
            .build()

        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(bridgeInterface, "BorizonBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    if (url.startsWith(LOCAL_URL_BASE)) {
                        if (!url.startsWith("$LOCAL_URL_BASE/assets/")) {
                            val path = request.url.path ?: ""
                            val localFile = File(skillDir, path.removePrefix("/"))
                            if (!localFile.exists() || localFile.isDirectory || !localFile.canonicalPath.startsWith(skillDir.canonicalPath)) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                        }
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                    // Block all non-local requests
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoadCallback?.invoke()
                    pageLoadCallback = null
                }
            }
        }
    }

    /** Get the WebView, creating it on main thread if needed. Suspends instead of blocking. */
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        createWebView().also { webView = it }
    }

    /**
     * Execute a JS skill: load URL on main thread, wait for page, call borizon_skill_execute(data), return result.
     */
    suspend fun executeJs(url: String, data: String): String = executionMutex.withLock {
        if (!url.startsWith(LOCAL_URL_BASE)) {
            return "{\"error\": \"Only local asset URLs are allowed.\"}"
        }
        if (url.contains("..")) {
            return "{\"error\": \"Path traversal not allowed.\"}"
        }
        val wv = ensureWebView()

        // 0. Clear DOM storage to prevent data leaking between skill executions
        withContext(Dispatchers.Main) {
            wv.clearCache(true)
            WebStorage.getInstance().deleteAllData()
        }

        // 1. Load the skill URL on the main thread and wait for page to finish
        val loaded = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                pageLoadCallback = { cont.resume(Unit) {} }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    wv.loadUrl(url)
                }
            }
        }
        if (loaded == null) {
            return "{\"error\": \"Page load timed out for $url\"}"
        }

        // 2. Set up result listener
        val deferred = CompletableDeferred<String>()
        bridgeInterface.resultListener = { result ->
            if (!deferred.isCompleted) deferred.complete(result)
        }

        // 3. Inject JS to call borizon_skill_execute on the main thread
        val escapedData = org.json.JSONObject.quote(data)
        val script = """
            (async function() {
                var startTs = Date.now();
                while(true) {
                    if (typeof borizon_skill_execute === 'function') break;
                    await new Promise(function(r){setTimeout(r,100)});
                    if (Date.now() - startTs > $FN_POLL_TIMEOUT_MS) break;
                }
                var dataStr = JSON.parse($escapedData);
                var result = await borizon_skill_execute(dataStr);
                BorizonBridge.onResultReady(result);
            })()
        """.trimIndent()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wv.evaluateJavascript(script, null)
        }

        // 4. Wait for result with timeout
        withTimeoutOrNull(JS_TIMEOUT_MS) {
            deferred.await()
        } ?: "{\"error\": \"Skill execution timed out.\"}"
    }

    fun destroy() {
        // Complete any pending deferred so awaiting coroutines don't hang
        bridgeInterface.resultListener?.let { listener ->
            listener("{\"error\": \"Bridge destroyed during execution.\"}")
        }
        bridgeInterface.resultListener = null
        pageLoadCallback = null
        webView?.stopLoading()
        webView?.clearCache(true)
        webView?.destroy()
        webView = null
    }
}
