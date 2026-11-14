package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

internal class YouTubeBrowser(
    context: Context,
    private val onVideoSelected: (String) -> Unit,
    private val onHistoryChanged: () -> Unit,
    private val onPlaylistSelected: (String) -> Unit,
) {
    val view = WebView(context)
    private var lastBrowseUrl = HOME_URL
    private var restoringBrowsePage = false

    fun destroy() {
        view.stopLoading()
        view.destroy()
    }

    fun canGoBack(): Boolean = previousBrowseOffset() != null

    fun currentPlaylist(): String? = view.url?.let { playlistUrl(Uri.parse(it)) }

    fun goBack(): Boolean {
        val offset = previousBrowseOffset() ?: return false
        view.goBackOrForward(offset)
        return true
    }

    private fun previousBrowseOffset(): Int? {
        val history = view.copyBackForwardList()
        val currentUrl = history.currentItem?.url
        for (index in history.currentIndex - 1 downTo 0) {
            val url = history.getItemAtIndex(index)?.url ?: continue
            if (url == currentUrl) continue
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host !in BROWSE_HOSTS || videoUrl(uri) != null) {
                continue
            }
            return index - history.currentIndex
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun load() {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (" Mobile " !in userAgentString) {
                userAgentString = userAgentString.replace(" Safari/", " Mobile Safari/")
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = request.isForMainFrame && handleNavigation(request.url)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                recoverFromVideoNavigation(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!recoverFromVideoNavigation(view, url)) rememberBrowseUrl(url)
                onHistoryChanged()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val uri = request.url
                val host = uri.host.orEmpty()
                val isMediaHost = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
                if (uri.scheme == "http" || host == CLICK_HOST || isMediaHost) {
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 403, "Forbidden",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view.url == url && rememberBrowseUrl(url)) {
                    restoringBrowsePage = false
                    view.evaluateJavascript(CLICK_HANDLER, null)
                }
            }
        }
        view.loadUrl(HOME_URL)
    }

    private fun rememberBrowseUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host !in YOUTUBE_HOSTS || videoUrl(uri) != null) {
            return false
        }
        lastBrowseUrl = url
        return true
    }

    private fun recoverFromVideoNavigation(view: WebView, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.host != CLICK_HOST && videoUrl(uri) == null) return false
        if (restoringBrowsePage) return true
        restoringBrowsePage = true
        view.stopLoading()
        handleNavigation(uri)
        view.loadUrl(lastBrowseUrl)
        return true
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https") return true
        if (uri.host == CLICK_HOST) {
            val target = uri.getQueryParameter("url")?.let(Uri::parse) ?: return true
            if (uri.getQueryParameter("playlist") == "1") {
                playlistUrl(target)?.let(onPlaylistSelected)
            } else {
                videoUrl(target)?.let(onVideoSelected)
            }
            return true
        }
        videoUrl(uri)?.let {
            onVideoSelected(it)
            return true
        }
        return uri.host !in BROWSE_HOSTS
    }

    private fun playlistUrl(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host !in YOUTUBE_HOSTS) return null
        val id = uri.getQueryParameter("list")?.takeIf(PLAYLIST_ID::matches) ?: return null
        return "https://www.youtube.com/playlist?list=$id"
    }

    private fun videoUrl(uri: Uri): String? {
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)
        val id = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host !in YOUTUBE_HOSTS -> return null
            uri.path == "/watch" -> uri.getQueryParameter("v")
            uri.pathSegments.firstOrNull() in VIDEO_PATHS -> uri.pathSegments.getOrNull(1)
            else -> null
        }
        return id?.takeIf(VIDEO_ID::matches)?.let { "https://www.youtube.com/watch?v=$it" }
    }

    companion object {
        fun clearSession(context: Context, onComplete: () -> Unit) {
            val cookies = CookieManager.getInstance()
            val cacheCleaner = WebView(context)
            try {
                cacheCleaner.clearCache(true)
                cacheCleaner.clearFormData()
            } finally {
                cacheCleaner.destroy()
            }
            WebStorage.getInstance().deleteAllData()
            cookies.removeAllCookies {
                cookies.flush()
                onComplete()
            }
        }

        private const val HOME_URL = "https://m.youtube.com/"
        private const val CLICK_HOST = "youtube-click.invalid"
        private val YOUTUBE_HOSTS = setOf("m.youtube.com", "www.youtube.com", "youtube.com")
        private val BROWSE_HOSTS = YOUTUBE_HOSTS + setOf("consent.youtube.com", "consent.google.com")
        private val VIDEO_PATHS = setOf("shorts", "live", "embed", "v")
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{1,100}")

        // Capture anchors before YouTube handles them as single-page navigation.
        private val CLICK_HANDLER = """
            (() => {
                if (window.waguriVideoClickInstalled) return;
                window.waguriVideoClickInstalled = true;

                function isVideoLink(url) {
                    if (url.protocol !== 'https:') return false;
                    if (url.hostname === 'youtu.be') {
                        return /^[A-Za-z0-9_-]{11}$/.test(url.pathname.split('/')[1] || '');
                    }
                    if (!['m.youtube.com', 'www.youtube.com', 'youtube.com'].includes(url.hostname)) {
                        return false;
                    }
                    const parts = url.pathname.split('/');
                    const id = url.pathname === '/watch'
                        ? url.searchParams.get('v')
                        : ['shorts', 'live', 'embed', 'v'].includes(parts[1]) ? parts[2] : null;
                    return /^[A-Za-z0-9_-]{11}$/.test(id || '');
                }

                document.addEventListener('click', event => {
                    const link = event.composedPath().find(node => node instanceof HTMLAnchorElement);
                    if (!link || !link.href) return;
                    let url;
                    try {
                        url = new URL(link.href, location.href);
                    } catch {
                        return;
                    }
                    if (!isVideoLink(url)) return;
                    const isPlayAll = location.pathname === '/playlist' && url.searchParams.has('list') &&
                        link.closest('ytm-playlist-header-renderer, ytm-playlist-header, ytm-playlist-byline-renderer, ytm-playlist-hero-renderer, ytm-playlist-panel-header-renderer, ytd-playlist-header-renderer, #play-button');
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    location.assign('https://$CLICK_HOST/?url=' + encodeURIComponent(url.href) +
                        (isPlayAll ? '&playlist=1' : ''));
                }, true);
            })();
        """.trimIndent()
    }
}
