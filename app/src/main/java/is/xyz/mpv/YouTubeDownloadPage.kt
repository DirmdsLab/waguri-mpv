package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Hosts the local server's format picker separately from the YouTube browsing session. */
internal class YouTubeDownloadPage(context: Context, onSave: (String) -> Unit) {
    val view = createWebView(context, onSave)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, onSave: (String) -> Unit) = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return true
                return handleNavigation(request.url, request.hasGesture())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(Uri.parse(url), userGesture = true)

            private fun handleNavigation(uri: Uri, userGesture: Boolean): Boolean {
                if (!isServerUrl(uri)) return true
                val path = uri.encodedPath
                if (path == "/") return false
                if (path != null && uri.encodedQuery == null && userGesture &&
                    YouTubeLibraryCopy.isManifestPath(path)
                ) {
                    onSave(path)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (isServerUrl(uri)) return null
                val host = uri.host.orEmpty()
                if (!request.isForMainFrame && uri.scheme == "https" &&
                    (host == "ytimg.com" || host.endsWith(".ytimg.com"))
                ) return null
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    fun load(videoUrl: String?) {
        val pageUrl = Uri.parse("$SERVER_ORIGIN/").buildUpon().appendQueryParameter("client", "waguri")
        videoUrl?.let { pageUrl.appendQueryParameter("url", it) }
        view.loadUrl(pageUrl.build().toString())
    }

    fun destroy() {
        view.stopLoading()
        view.destroy()
    }

    companion object {
        const val SERVER_ORIGIN = "http://127.0.0.1:7010"

        private fun isServerUrl(uri: Uri): Boolean =
            uri.scheme == "http" && uri.encodedAuthority == "127.0.0.1:7010"
    }
}
