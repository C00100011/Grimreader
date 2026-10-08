package com.vdelaar.mylibby.ui.discover

import android.annotation.SuppressLint
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Runs Shelfmark's OIDC login (`/api/auth/oidc/login`) in an in-app browser. Once the identity
 * provider redirects back and Shelfmark lands on its own UI, the session cookie is handed to
 * [onSignedIn] so the app can use the API with it.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OidcLoginDialog(
    baseUrl: String,
    providerLabel: String?,
    onSignedIn: (cookieHeader: String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var currentHost by remember { mutableStateOf("") }
    val shelfmark = remember(baseUrl) { Uri.parse(baseUrl) }

    val webView = remember {
        WebView(context).apply {
            // MATCH_PARENT: with Compose's default WRAP_CONTENT, WebView lays out with a 0px viewport.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    val noSessionMessage = stringResource(com.vdelaar.mylibby.R.string.sso_no_session)
    var finished by remember { mutableStateOf(false) }
    fun check(url: String?): Boolean {
        if (finished || url == null) return false
        val uri = Uri.parse(url)
        currentHost = uri.host.orEmpty()
        val sameServer = uri.host == shelfmark.host && (uri.port == shelfmark.port || uri.port == -1 || shelfmark.port == -1)
        // Shelfmark may be hosted under a sub-path (e.g. https://host/shelfmark/).
        val basePath = shelfmark.path.orEmpty().trimEnd('/')
        val fullPath = uri.path.orEmpty()
        if (!sameServer || !fullPath.startsWith(basePath)) return false
        val path = fullPath.removePrefix(basePath)
        if (path.startsWith("/api/auth/oidc")) return false
        // Back on Shelfmark itself: either an error page or its UI (= signed in).
        finished = true
        val error = uri.getQueryParameter("oidc_error")
        if (error != null) {
            onError(error)
        } else {
            CookieManager.getInstance().flush()
            val cookies = CookieManager.getInstance().getCookie(baseUrl)
            if (cookies.isNullOrBlank()) onError(noSessionMessage)
            else onSignedIn(cookies)
        }
        return true
    }

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && check(request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                if (check(url)) view.stopLoading()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                check(url)
            }
        }
        webView.loadUrl(baseUrl.trimEnd('/') + "/api/auth/oidc/login")
        onDispose { webView.stopLoading(); webView.destroy() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(providerLabel?.let { stringResource(com.vdelaar.mylibby.R.string.sso_title_with, it) } ?: stringResource(com.vdelaar.mylibby.R.string.sso_title))
                            if (currentHost.isNotBlank()) {
                                Text(currentHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(com.vdelaar.mylibby.R.string.cancel_sign_in)) } },
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
                Text(
                    stringResource(com.vdelaar.mylibby.R.string.sso_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
