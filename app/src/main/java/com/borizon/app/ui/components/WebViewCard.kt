package com.borizon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.tween
import com.borizon.app.R
import com.borizon.app.ui.theme.LocalBorizonSemanticColors

@Composable
internal fun WebViewCard(
    title: String,
    url: String?,
    aspectRatio: Float = 1.333f,
) {
    var isFullscreen by remember { mutableStateOf(false) }
    var isStopped by remember { mutableStateOf(false) }

    if (isFullscreen && url != null) {
        FullscreenWebView(
            title = title,
            url = url,
            isStopped = isStopped,
            onCollapse = { isFullscreen = false },
            onStop = { isStopped = true },
        )
    } else {
        InlineWebViewCard(
            title = title,
            url = url,
            aspectRatio = aspectRatio,
            isStopped = isStopped,
            onExpand = { isFullscreen = true },
            onStop = { isStopped = true },
        )
    }
}

@Composable
private fun InlineWebViewCard(
    title: String,
    url: String?,
    aspectRatio: Float,
    isStopped: Boolean,
    onExpand: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val semanticColors = LocalBorizonSemanticColors.current

    val assetLoader = remember(context) {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir))
            .build()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = stringResource(R.string.chat_fullscreen),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .background(semanticColors.chat.webViewBackground),
                contentAlignment = Alignment.Center,
            ) {
                val isLocalUrl = url?.startsWith("https://appassets.androidplatform.net") == true
                if (url != null && !isStopped && isLocalUrl) {
                    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = false
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: android.webkit.WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                    ): android.webkit.WebResourceResponse? {
                                        return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                                            ?: super.shouldInterceptRequest(view, request)
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onJsAlert(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                        result?.cancel()
                                        return true
                                    }
                                    override fun onJsConfirm(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                        result?.cancel()
                                        return true
                                    }
                                    override fun onJsPrompt(view: android.webkit.WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                                        result?.cancel()
                                        return true
                                    }
                                }
                            }.also { webView = it }
                        },
                        update = { wv ->
                            if (wv.url != url) wv.loadUrl(url)
                        },
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            webView?.destroy()
                        }
                    }
                } else if (isStopped) {
                    Text(
                        text = stringResource(R.string.stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                } else {
                    Text(
                        text = if (isLocalUrl) stringResource(R.string.loading) else stringResource(R.string.blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenWebView(
    title: String,
    url: String,
    isStopped: Boolean,
    onCollapse: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val semanticColors = LocalBorizonSemanticColors.current

    val assetLoader = remember(context) {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir))
            .build()
    }

    val alpha by animateFloatAsState(
        targetValue = if (isStopped) 0.6f else 1f,
        animationSpec = tween(300),
        label = "contentAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(semanticColors.chat.webViewBackground)
    ) {
        val isLocalUrl = url.startsWith("https://appassets.androidplatform.net")
        if (url != null && !isStopped && isLocalUrl) {
            var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        setBackgroundColor(semanticColors.chat.webViewBackground.toArgb())
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                                    ?: super.shouldInterceptRequest(view, request)
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onJsAlert(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                result?.cancel()
                                return true
                            }
                            override fun onJsConfirm(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                result?.cancel()
                                return true
                            }
                            override fun onJsPrompt(view: android.webkit.WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                                result?.cancel()
                                return true
                            }
                        }
                    }.also { webView = it }
                },
                update = { wv ->
                    if (wv.url != url) wv.loadUrl(url)
                },
            )
            DisposableEffect(Unit) {
                onDispose {
                    webView?.destroy()
                }
            }
        } else if (isStopped || !isLocalUrl) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isStopped) stringResource(R.string.stopped) else stringResource(R.string.blocked),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onCollapse,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.4f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.FullscreenExit,
                        contentDescription = stringResource(R.string.exit_fullscreen),
                        modifier = Modifier.size(18.dp),
                        tint = Color.White,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }

            Surface(
                onClick = onStop,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.4f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
