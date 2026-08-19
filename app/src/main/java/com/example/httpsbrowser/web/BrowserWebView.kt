package com.example.httpsbrowser.web

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.UrlRuleBlocker
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class BrowserWebViewRegistry(
    private val context: Context,
    private val blocker: UrlRuleBlocker
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pageTranslator = PageTranslator()

    fun obtain(tab: BrowserTab, settings: BrowserSettings, callbacks: BrowserWebCallbacks): WebView {
        val entry = entries[tab.id] ?: Entry(createWebView(tab.id)).also { entries[tab.id] = it }
        entry.callbacks = callbacks
        entry.settings = settings
        val darkModeChanged = entry.appliedForceDark != settings.forceDarkPages
        configure(entry.webView, settings, isVideoSensitivePage(tab.lastRequestedUrl))
        entry.appliedForceDark = settings.forceDarkPages
        if (darkModeChanged && entry.loadedUrl != null) {
            // 再読み込みせず、現在のページへ直ちに暗色スタイルだけを反映する。
            applyDeepDarkCss(entry.webView, settings.forceDarkPages && !isVideoSensitivePage(entry.loadedUrl.orEmpty()))
        }
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            if (isHttps(url)) {
                entry.loadedUrl = url
                entry.webView.loadUrl(url)
            } else entry.callbacks.onBlockedNavigation(url)
        }
    }

    fun reload(tabId: String) = entries[tabId]?.webView?.reload()
    fun goBack(tabId: String) = entries[tabId]?.webView?.takeIf { it.canGoBack() }?.goBack()
    fun canGoBack(tabId: String): Boolean = entries[tabId]?.webView?.canGoBack() == true
    fun translateToJapanese(tabId: String) = entries[tabId]?.let { entry ->
        pageTranslator.translatePage(entry.webView) { message -> entry.callbacks.onNotice(message) }
    }

    /** 現ページを MHTML として一時保存し、UI 側でユーザーが選んだ保存先へ書き出す。 */
    fun savePageArchive(tabId: String, title: String) = entries[tabId]?.let { entry ->
        val archiveDirectory = File(context.cacheDir, "page_archives").apply { mkdirs() }
        val baseName = title.ifBlank { "page" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "page" }
        val target = File(archiveDirectory, "${baseName}_${System.currentTimeMillis()}.mht")
        entry.webView.saveWebArchive(target.absolutePath, false) { savedPath ->
            val archive = savedPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (archive != null) entry.callbacks.onPageArchiveReady(archive.absolutePath, archive.name)
            else entry.callbacks.onNotice("ページを保存できませんでした。読み込み完了後にもう一度お試しください。")
        }
    }
    fun goForward(tabId: String) = entries[tabId]?.webView?.takeIf { it.canGoForward() }?.goForward()
    fun scrollBy(tabId: String, deltaY: Int) = entries[tabId]?.webView?.scrollBy(0, deltaY)
    fun scrollToTop(tabId: String) = entries[tabId]?.webView?.scrollTo(0, 0)
    fun scrollToBottom(tabId: String) = entries[tabId]?.webView?.let { it.scrollTo(0, (it.contentHeight * it.scale).toInt()) }
    fun scrollToFraction(tabId: String, fraction: Float) = entries[tabId]?.webView?.let { view ->
        val maximum = ((view.contentHeight * view.scale).toInt() - view.height).coerceAtLeast(0)
        view.scrollTo(0, (maximum * fraction.coerceIn(0f, 1f)).toInt())
    }

    fun pause(tabId: String) = entries[tabId]?.webView?.onPause()
    fun resume(tabId: String) = entries[tabId]?.webView?.onResume()

    fun remove(tabId: String) {
        entries.remove(tabId)?.webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            destroy()
        }
    }

    fun destroyAll() {
        entries.keys.toList().forEach(::remove)
        pageTranslator.close()
    }

    fun clearAllBrowsingData() {
        entries.values.forEach { entry ->
            entry.webView.clearHistory()
            entry.webView.clearCache(true)
            entry.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        destroyAll()
    }

    private fun createWebView(tabId: String): WebView = object : WebView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val scrollRange = ((contentHeight * scale).toInt() - height).coerceAtLeast(0)
            val fraction = if (scrollRange == 0) 0f else t.toFloat() / scrollRange
            entries[tabId]?.callbacks?.onScrollPosition(tabId, fraction.coerceIn(0f, 1f))
        }
    }.apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 一部の Google/YouTube 埋め込みが WebView 専用表示で白画面になるのを避ける。
            userAgentString = userAgentString.replace("; wv", "")
            // モバイルサイトの viewport meta を尊重し、YouTube/Shorts を端末幅の縦長レイアウトで描画する。
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 動画ページのプレーヤー初期化やログイン確認を妨げない。
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = true
        }
        // ログイン状態と埋め込みプレーヤーの認証をアプリ内で維持する。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = SecureClient(tabId)
        webChromeClient = SecureChromeClient(tabId)
        setDownloadListener(SecureDownloadListener(tabId))
        setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                entries[tabId]?.callbacks?.onPageInteraction()
            }
            false
        }
        setOnLongClickListener {
            val url = when (hitTestResult.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> hitTestResult.extra
                else -> null
            }
            if (!url.isNullOrBlank()) entries[tabId]?.callbacks?.onLinkLongPressed(url)
            false
        }
    }

    private fun configure(view: WebView, settings: BrowserSettings, videoPage: Boolean) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        // プレーヤー DOM を直接書き換えず、WebView 標準の暗色化だけを許可する。
        // 映像は GPU の別レイヤーで描画されるため、CSS 反転より再生を壊しにくい。
        val allowDarkTransforms = settings.forceDarkPages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.setForceDarkAllowed(allowDarkTransforms)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, allowDarkTransforms)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                view.settings,
                if (allowDarkTransforms) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
        if (allowDarkTransforms && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                view.settings,
                WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY
            )
        }
    }

    private fun applyCosmeticAdFilters(view: WebView, url: String, enabled: Boolean) {
        // YouTube の内部要素へ汎用 AdGuard CSS を注入するとプレーヤー・レイアウトを隠すことがある。
        // YouTube は専用の限定 selector だけを別処理で適用する。
        if (isYoutubePlaybackResource(url)) {
            view.evaluateJavascript("(function() { document.getElementById('__https_browser_adblock_css')?.remove(); })();", null)
            return
        }
        val script = if (enabled) {
            val css = blocker.cosmeticCssFor(url)
            """
                (function() {
                  var id = '__https_browser_adblock_css';
                  var style = document.getElementById(id);
                  if (!style) { style = document.createElement('style'); style.id = id; document.documentElement.appendChild(style); }
                  style.textContent = ${JSONObject.quote(css)};
                })();
            """.trimIndent()
        } else {
            "(function() { document.getElementById('__https_browser_adblock_css')?.remove(); })();"
        }
        view.evaluateJavascript(script, null)
    }

    /** プレーヤー本体に触れず、YouTube ページの広告枠・プロモーション枠だけを非表示にする。 */
    private fun applyYoutubeAdUiFilters(view: WebView, url: String, enabled: Boolean) {
        if (!isYoutubePlaybackResource(url)) return
        val css = if (enabled) """
            ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-promoted-video-renderer,
            ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,
            ytd-action-companion-ad-renderer,ytm-ad-slot-renderer,
            ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,
            #player-ads,.ytp-ad-overlay-container,.ytp-ad-module {
              display:none!important;visibility:hidden!important;
            }
        """.trimIndent() else ""
        val script = """
            (function() {
              var id = '__https_browser_youtube_ad_css';
              var style = document.getElementById(id);
              if (!style) { style = document.createElement('style'); style.id = id; document.documentElement.appendChild(style); }
              style.textContent = ${JSONObject.quote(css)};
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun applyDeepDarkCss(view: WebView, enabled: Boolean) {
        val script = if (enabled) """
            (function() {
              var id = '__https_browser_deep_dark';
              var style = document.getElementById(id);
              if (!style) { style = document.createElement('style'); style.id = id; document.documentElement.appendChild(style); }
              style.textContent = 'html{background:#000!important;color-scheme:dark!important}' +
                'body{background:#fff!important;color:#111!important;filter:invert(1) hue-rotate(180deg)!important}' +
                'img,video,canvas,iframe,svg,picture,object,embed{filter:invert(1) hue-rotate(180deg)!important}' +
                'input,textarea,select{background:#e8e8e8!important;color:#111!important}';
            })();
        """.trimIndent() else """
            (function() { document.getElementById('__https_browser_deep_dark')?.remove(); })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private inner class SecureClient(private val tabId: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) return false
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                val fallback = intentFallbackUrl(url)
                if (fallback != null) entries[tabId]?.callbacks?.onHttpsUpgrade(fallback)
                else entries[tabId]?.callbacks?.onExternalAppRequested(url)
                return true
            }
            val secureUrl = upgradeToHttps(url)
            return when {
                secureUrl == null -> {
                    entries[tabId]?.callbacks?.onBlockedNavigation(url)
                    true
                }
                secureUrl != url -> {
                    entries[tabId]?.callbacks?.onHttpsUpgrade(secureUrl)
                    true
                }
                else -> false
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val entry = entries[tabId] ?: return null
            val url = request.url.toString()
            // YouTube は動画本体・内部 API を URL 規則で遮断しない。広告・計測に使われる
            // 専用ドメインだけに URL フィルタを限定し、プレーヤーの起動と全画面表示を保護する。
            val shouldCheck = if (isYoutubePlaybackResource(url)) isYoutubeAdOrTrackingNetwork(url) else true
            if (entry.settings.adBlockingEnabled && shouldCheck && blocker.shouldBlock(url)) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 204, "No Content",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0))
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val entry = entries[tabId]
            view.setBackgroundColor(android.graphics.Color.BLACK)
            entry?.let { configure(view, it.settings, isVideoSensitivePage(url)) }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            // 初回描画の時点で黒背景を注入し、読み込み完了まで白く見える時間を短くする。
            applyCosmeticAdFilters(view, url, entry?.settings?.adBlockingEnabled == true)
            applyYoutubeAdUiFilters(view, url, entry?.settings?.adBlockingEnabled == true)
            applyDeepDarkCss(view, entry?.settings?.forceDarkPages == true && !isVideoSensitivePage(url))
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId]
            applyCosmeticAdFilters(view, url, entry?.settings?.adBlockingEnabled == true)
            applyYoutubeAdUiFilters(view, url, entry?.settings?.adBlockingEnabled == true)
            applyDeepDarkCss(view, entry?.settings?.forceDarkPages == true && !isVideoSensitivePage(url))
            CookieManager.getInstance().flush()
            entry?.callbacks?.onPageFinished(tabId, url, view.title)
            entries[tabId]?.callbacks?.onHistoryState(tabId, view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel() // 証明書エラーを無視して接続することは絶対にしない。
            entries[tabId]?.callbacks?.onSslError(error.url)
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // OS が WebView レンダラを終了した場合のみ、同じタブの URL を静かに再生成する。
            // 通知ダイアログやタブ削除は行わない。
            entries[tabId]?.callbacks?.onRendererGone(tabId)
            remove(tabId)
            return true
        }
    }

    private inner class SecureChromeClient(private val tabId: String) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String) {
            entries[tabId]?.callbacks?.onTitle(tabId, title)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            entries[tabId]?.callbacks?.onProgress(tabId, newProgress)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            entries[tabId]?.callbacks?.onShowFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            entries[tabId]?.callbacks?.onHideFullscreen()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources.toSet()
            entries[tabId]?.callbacks?.onWebPermissionRequest(request.origin.toString(), resources) { accepted ->
                if (accepted) request.grant(resources.toTypedArray()) else request.deny()
            } ?: request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback
        ) {
            entries[tabId]?.callbacks?.onGeolocationPermission(origin) { accepted ->
                callback.invoke(origin, accepted, false)
            } ?: callback.invoke(origin, false, false)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) return false // 自動ポップアップは拒否する。
            val current = entries[tabId] ?: return false
            val newTabId = current.callbacks.onPopupRequested() ?: return false
            val popupView = createWebView(newTabId)
            configure(popupView, current.settings, false)
            // 新規ウィンドウの WebView を、そのまま新しいタブへ接続する。
            // 空文字を loadedUrl に入れると Compose 再構成時に読み込み状態が不整合になるため null を維持する。
            entries[newTabId] = Entry(
                webView = popupView,
                callbacks = current.callbacks,
                settings = current.settings,
                appliedForceDark = current.settings.forceDarkPages
            )
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = popupView
            resultMsg.sendToTarget()
            return true
        }
    }

    private inner class SecureDownloadListener(private val tabId: String) : DownloadListener {
        override fun onDownloadStart(
            url: String, userAgent: String, contentDisposition: String,
            mimeType: String, contentLength: Long
        ) {
            if (!isHttps(url)) {
                entries[tabId]?.callbacks?.onBlockedNavigation(url)
                return
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setDescription("HTTPS Tab Browser からのダウンロード")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            entries[tabId]?.callbacks?.onDownloadStarted(fileName, "Downloads/$fileName")
        }
    }

    private data class Entry(
        val webView: WebView,
        var loadedUrl: String? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        var appliedForceDark: Boolean? = null
    )

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    private fun isVideoSensitivePage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val query = uri.query.orEmpty()
        return isYoutubePlaybackResource(url) ||
            (host.endsWith("google.com") && uri.path == "/search" && (query.contains("tbm=vid") || query.contains("udm=7")))
    }

    private fun intentFallbackUrl(url: String): String? = runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.let(::upgradeToHttps)
    }.getOrNull()

    private fun isYoutubePlaybackResource(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "ytimg.com" || host.endsWith(".ytimg.com") ||
            host == "youtubei.googleapis.com"
    }

    /** YouTube の再生系・内部 API を避け、広告配信・計測専用と判断できるドメインだけを遮断候補にする。 */
    private fun isYoutubeAdOrTrackingNetwork(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return host == "ads.youtube.com" || host.endsWith(".ads.youtube.com") ||
            host == "doubleclick.net" || host.endsWith(".doubleclick.net") ||
            host == "googlesyndication.com" || host.endsWith(".googlesyndication.com") ||
            host == "googleadservices.com" || host.endsWith(".googleadservices.com") ||
            host == "googletagservices.com" || host.endsWith(".googletagservices.com") ||
            ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
                (path.startsWith("/api/stats/ads") || path.startsWith("/_get_ads") ||
                    path.startsWith("/pcs/activeview") || path.startsWith("/pagead")))
    }

    private fun upgradeToHttps(url: String): String? = runCatching {
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "https" -> uri.toString()
            "http" -> URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            else -> null
        }
    }.getOrNull()
}

interface BrowserWebCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String?)
    fun onTitle(tabId: String, title: String)
    fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgress(tabId: String, progress: Int)
    fun onScrollPosition(tabId: String, fraction: Float)
    fun onHttpsUpgrade(url: String)
    fun onBlockedNavigation(url: String)
    fun onSslError(url: String)
    fun onRendererGone(tabId: String)
    fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit)
    fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit)
    fun onPopupRequested(): String?
    fun onLinkLongPressed(url: String)
    fun onDownloadStarted(fileName: String, destination: String)
    fun onPageArchiveReady(sourcePath: String, fileName: String)
    fun onExternalAppRequested(url: String)
    fun onPageInteraction()
    fun onNotice(message: String)

    data object Empty : BrowserWebCallbacks {
        override fun onPageStarted(tabId: String, url: String) = Unit
        override fun onPageFinished(tabId: String, url: String, title: String?) = Unit
        override fun onTitle(tabId: String, title: String) = Unit
        override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = Unit
        override fun onProgress(tabId: String, progress: Int) = Unit
        override fun onScrollPosition(tabId: String, fraction: Float) = Unit
        override fun onHttpsUpgrade(url: String) = Unit
        override fun onBlockedNavigation(url: String) = Unit
        override fun onSslError(url: String) = Unit
        override fun onRendererGone(tabId: String) = Unit
        override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onHideFullscreen() = Unit
        override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = reply(false)
        override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = reply(false)
        override fun onPopupRequested(): String? = null
        override fun onLinkLongPressed(url: String) = Unit
        override fun onDownloadStarted(fileName: String, destination: String) = Unit
        override fun onPageArchiveReady(sourcePath: String, fileName: String) = Unit
        override fun onExternalAppRequested(url: String) = Unit
        override fun onPageInteraction() = Unit
        override fun onNotice(message: String) = Unit
    }
}
