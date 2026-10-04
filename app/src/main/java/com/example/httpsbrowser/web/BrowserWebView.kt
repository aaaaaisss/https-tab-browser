package com.example.httpsbrowser.web

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.URLUtil
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import com.example.httpsbrowser.CrashDiagnostics
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BraveAdBlockEngine
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class BrowserWebViewRegistry(
    private val context: Context,
    private val blocker: BraveAdBlockEngine
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pageTranslator = PageTranslator()

    fun obtain(tab: BrowserTab, settings: BrowserSettings, callbacks: BrowserWebCallbacks): WebView {
        val entry = entries[tab.id] ?: Entry(createWebView(tab.id)).also { entries[tab.id] = it }
        entry.callbacks = callbacks
        entry.settings = settings
        entry.adBlockingEnabled = settings.adBlockingEnabled
        ensureYoutubePictureInPictureScript(entry)
        ensureYoutubeAggressiveScripts(entry)
        // Fulguris由来のWebView設定だけを適用する。ページ内CSS/JS注入を使わないため、
        // タブ選択やダークモード切替で動画・履歴を再読み込みしない。
        configure(entry.webView, entry, tab.lastRequestedUrl)
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.activeDocumentUrl = tab.lastRequestedUrl
            CrashDiagnostics.recordWebViewNavigation(tab.lastRequestedUrl)
            prepareYoutubeDocumentStartScript(entry, tab.lastRequestedUrl)
            prepareSiteDocumentStartScript(entry, tab.lastRequestedUrl)
            prepareDarkDocumentStartScript(entry, tab.lastRequestedUrl)
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    /** Composeの再構成からWebViewを分離し、Activity直下のhostへ接続する。 */
    fun attachToNativeHost(tabId: String, host: ViewGroup): Boolean {
        val entry = entries[tabId] ?: return false
        val view = entry.webView
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        return true
    }

    fun detachFromNativeHost(tabId: String, host: ViewGroup? = null) {
        val view = entries[tabId]?.webView ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (host == null || parent === host) parent.removeView(view)
    }

    /** 全画面動画中はページ暗色化の再適用で映像面を壊さない。 */
    fun setFullscreenVideoDarkeningSuppressed(tabId: String, suppressed: Boolean) {
        val entry = entries[tabId] ?: return
        if (entry.fullscreenVideoDarkeningSuppressed == suppressed) return
        entry.fullscreenVideoDarkeningSuppressed = suppressed
        if (!suppressed) {
            val url = entry.activeDocumentUrl ?: entry.loadedUrl ?: return
            configure(entry.webView, entry, url)
            CrashDiagnostics.record("video_dark_css_suppressed", "tab=$tabId\nsuppressed=false\nurl=$url")
        } else {
            CrashDiagnostics.record("video_dark_css_suppressed", "tab=$tabId\nsuppressed=true")
        }
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            entry.cancelBackNavigation()
            if (isHttps(url)) {
                entry.loadedUrl = url
                // shouldInterceptRequest はUIスレッド外から呼ばれ得るため、
                // コールバック内で WebView.url を読む代わりに遷移前に親URLを保持する。
                entry.activeDocumentUrl = url
                CrashDiagnostics.recordWebViewNavigation(url)
                prepareYoutubeDocumentStartScript(entry, url)
                prepareSiteDocumentStartScript(entry, url)
                prepareDarkDocumentStartScript(entry, url)
                ensureYoutubeAggressiveScripts(entry)
                configure(entry.webView, entry, url)
                beginDarkRevealGuard(entry.webView, entry, url)
                entry.webView.loadUrl(url)
            } else entry.callbacks.onBlockedNavigation(url)
        }
    }

    fun reload(tabId: String) = entries[tabId]?.webView?.reload()
    /** APK-era asynchronous back-navigation queue. */
    fun goBack(tabId: String): Boolean {
        val entry = entries[tabId] ?: return false
        if (!entry.beginBackNavigation()) return true
        val view = entry.webView
        if (!canNavigateHistory(view, -1)) {
            entry.cancelBackNavigation()
            return false
        }
        val history = view.copyBackForwardList()
        val targetIndex = history.currentIndex - 1
        val targetUrl = history.getItemAtIndex(targetIndex)?.url
        if (targetUrl.isNullOrBlank()) {
            entry.cancelBackNavigation()
            return false
        }
        entry.activeDocumentUrl = targetUrl
        beginDarkRevealGuard(view, entry, targetUrl)
        entry.rearmPageLifecycle(targetUrl)
        view.goBack()
        return true
    }

    private fun canNavigateHistory(view: WebView, delta: Int): Boolean {
        val history = view.copyBackForwardList()
        val target = history.currentIndex + delta
        return target >= 0 && target < history.size
    }

    private fun beginDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        entry.darkRevealPending = true
        view.alpha = 0f
    }

    private fun releaseDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        entry.darkRevealPending = false
        view.alpha = 1f
    }

    private fun completeBackNavigation(tabId: String, entry: Entry, view: WebView) {
        if (!entry.completeBackNavigation()) return
        view.post { goBack(tabId) }
    }
    fun setVideoPlaybackRate(tabId: String, rate: Float) {
        val safeRate = rate.coerceIn(0.25f, 3f)
        entries[tabId]?.webView?.evaluateJavascript(String.format(java.util.Locale.US, VIDEO_PLAYBACK_RATE_SCRIPT, safeRate), null)
    }

    fun seekVideo(tabId: String, deltaSeconds: Int) {
        val delta = deltaSeconds.coerceIn(-300, 300)
        entries[tabId]?.webView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){v.currentTime=Math.max(0,Math.min(v.duration||Infinity,v.currentTime+" + delta + "));}})();",
            null
        )
    }

    fun toggleVideoPlayback(tabId: String) {
        entries[tabId]?.webView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}})();",
            null
        )
    }

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
    fun goForward(tabId: String): Boolean {
        val entry = entries[tabId] ?: return false
        val view = entry.webView
        if (!canNavigateHistory(view, 1)) return false
        val history = view.copyBackForwardList()
        val targetUrl = history.getItemAtIndex(history.currentIndex + 1)?.url
        if (targetUrl.isNullOrBlank()) return false
        entry.activeDocumentUrl = targetUrl
        entry.rearmPageLifecycle(targetUrl)
        view.goForward()
        return true
    }
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
        entries.remove(tabId)?.let { entry ->
            entry.isActive = false
            runCatching { entry.documentStartScriptHandler?.remove() }
            entry.documentStartScriptHandler = null
            runCatching { entry.darkDocumentStartScriptHandler?.remove() }
            entry.darkDocumentStartScriptHandler = null
            runCatching { entry.siteDocumentStartScriptHandler?.remove() }
            entry.siteDocumentStartScriptHandler = null
            runCatching { entry.youtubePictureInPictureScriptHandler?.remove() }
            entry.youtubePictureInPictureScriptHandler = null
            runCatching { entry.youtubeAdSanitizerScriptHandler?.remove() }
            entry.youtubeAdSanitizerScriptHandler = null
            runCatching { entry.youtubeNoAdWarmPlayerScriptHandler?.remove() }
            entry.youtubeNoAdWarmPlayerScriptHandler = null
            runCatching { entry.youtubeSabrPatchOnlyScriptHandler?.remove() }
            entry.youtubeSabrPatchOnlyScriptHandler = null
            entry.cookieFlushRunnable?.let(entry.webView::removeCallbacks)
            entry.cookieFlushRunnable = null
            entry.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
        }
    }

    fun destroyAll() {
        entries.keys.toList().forEach(::remove)
    }

    /** 画面そのものが閉じる時だけ、翻訳モデルとネイティブフィルタを解放する。 */
    fun close() {
        destroyAll()
        // 遅延集約中のCookieもアプリ終了時には確実にディスクへ反映する。
        runCatching { CookieManager.getInstance().flush() }
        pageTranslator.close()
        blocker.close()
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

    private fun ensureYoutubePictureInPictureScript(entry: Entry) {
        if (entry.youtubePictureInPictureScriptHandler != null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            CrashDiagnostics.record("youtube_pip_unlock_unsupported", "reason=document_start_api_unavailable")
            return
        }
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                entry.webView,
                YOUTUBE_PIP_UNLOCK_SCRIPT,
                originRules
            )
        }.onSuccess { handler ->
            entry.youtubePictureInPictureScriptHandler = handler
            CrashDiagnostics.record("youtube_pip_unlock_ready", "documentStart=true")
        }.onFailure { throwable ->
            CrashDiagnostics.record(
                "youtube_pip_unlock_unsupported",
                "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            )
        }
    }

    /**
     * APKに存在したYouTube向けの追加document-start処理。
     * aggressive modeのときだけ登録し、設定変更時には既存ハンドラを入れ替える。
     */
    private fun ensureYoutubeAggressiveScripts(entry: Entry) {
        if (!entry.settings.aggressiveAdBlockingEnabled ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            runCatching { entry.youtubeAdSanitizerScriptHandler?.remove() }
            runCatching { entry.youtubeNoAdWarmPlayerScriptHandler?.remove() }
            runCatching { entry.youtubeSabrPatchOnlyScriptHandler?.remove() }
            entry.youtubeAdSanitizerScriptHandler = null
            entry.youtubeNoAdWarmPlayerScriptHandler = null
            entry.youtubeSabrPatchOnlyScriptHandler = null
            return
        }
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        if (entry.youtubeAdSanitizerScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YoutubeAdScripts.adSanitizer, originRules)
            }.onSuccess {
                entry.youtubeAdSanitizerScriptHandler = it
                CrashDiagnostics.record("youtube_ad_sanitizer_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_ad_sanitizer_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
        if (entry.youtubeNoAdWarmPlayerScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YoutubeAdScripts.noAdWarmPlayer, originRules)
            }.onSuccess {
                entry.youtubeNoAdWarmPlayerScriptHandler = it
                CrashDiagnostics.record("youtube_no_ad_warm_player_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_no_ad_warm_player_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
        if (entry.youtubeSabrPatchOnlyScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YoutubeAdScripts.sabrPatchOnly, originRules)
            }.onSuccess {
                entry.youtubeSabrPatchOnlyScriptHandler = it
                CrashDiagnostics.record("youtube_sabr_patch_only_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_sabr_patch_only_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
    }

    // Recovered from the APK's createSafeBraveRedirectResponse/isSafeRedirectMimeType path.
    // Only data: resources with explicitly safe resource/mime combinations are reconstructed.
    private fun isSafeRedirectMimeType(resourceType: String, mimeType: String): Boolean {
        return when (resourceType.lowercase()) {
            "stylesheet" -> mimeType == "text/css"
            "image" -> mimeType in SAFE_REDIRECT_IMAGE_MIME_TYPES
            "script" -> mimeType in SAFE_REDIRECT_SCRIPT_MIME_TYPES
            else -> false
        }
    }

    private fun createSafeBraveRedirectResponse(
        url: String,
        resourceType: String,
        mimeType: String
    ): WebResourceResponse? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        if (url.length > MAX_SAFE_REDIRECT_DATA_URL_CHARS) return null
        if (!isSafeRedirectMimeType(resourceType, mimeType.lowercase())) return null

        val match = Regex("^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(url) ?: return null
        val encoded = match.groupValues[2]
        val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }
            .getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_SAFE_REDIRECT_BYTES) return null

        val textLike = mimeType.startsWith("text/") &&
            (mimeType.contains("javascript") || mimeType.endsWith("+xml"))
        val charset = if (textLike) "utf-8" else "utf-8"
        return WebResourceResponse(
            mimeType,
            charset,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(bytes)
        )
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
        // WebViewへ恒久的なオフスクリーンGPUレイヤーを強制しない。
        // HTML5動画はChromiumが専用の合成面を管理するため、通常のLAYER_TYPE_NONEに委ねる。
        // これによりGoogle動画プレビューの映像面と親WebViewの黒白レイヤーの競合を避ける。
        setLayerType(View.LAYER_TYPE_NONE, null)
        // 独自の右端レールを使うため、横方向のedge effect/scrollbarが動画の左端に
        // 白いレイヤーとして露出しないよう、WebView標準のスクロール装飾を無効化する。
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // WebView専用UA分岐を避け、通常のモバイルChrome相当のページを要求する。
            // Version/端末情報は残し、WebView識別子だけを取り除く。
            userAgentString = userAgentString.replace("; wv", "")
            // WebViewではwide viewportが既定で無効なため、サイト側のmeta viewportを尊重する。
            // YouTube/Google埋め込みのモバイル幅・初期倍率はページ側に委ねる。
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
        addJavascriptInterface(object {
            @JavascriptInterface
            fun report(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                entries[tabId]?.callbacks?.onVideoDimensions(tabId, width, height)
            }

            @JavascriptInterface
            fun reportPlaybackState(hasVideo: Boolean, isPlaying: Boolean, width: Int, height: Int, playbackRate: Float) {
                val entry = entries[tabId] ?: return
                if (hasVideo && width > 0 && height > 0) {
                    entry.callbacks.onVideoDimensions(tabId, width, height)
                }
                val safeRate = playbackRate.takeIf { it in 0.25f..3f } ?: 1f
                entry.callbacks.onVideoPlaybackState(tabId, hasVideo, isPlaying, safeRate)
            }
        }, VIDEO_DIMENSIONS_BRIDGE_NAME)
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

    private fun configure(view: WebView, entry: Entry, url: String = entry.activeDocumentUrl.orEmpty()) {
        val settings = entry.settings
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        val isVideoPage = isVideoPlaybackDocumentUrl(url)
        val excluded = isDarkModeExcluded(settings, url)
        val applyForceDark = settings.forceDarkPages && (!isVideoPage || settings.forceDarkVideoPages) && !excluded
        val applied = FulgurisDarkModeController.apply(view, applyForceDark)
        entry.appliedForceDark = applyForceDark
        entry.appliedForceDarkVideoPages = settings.forceDarkVideoPages
        entry.appliedSkipDarkeningAlreadyDarkPages = settings.skipDarkeningAlreadyDarkPages
        entry.appliedDarkModeExcludedHosts = settings.darkModeExcludedHosts
        CrashDiagnostics.record("dark_mode_configured", "engine=apk_compatible\nforceRequested=" + settings.forceDarkPages + "\nforceApplied=" + applyForceDark + "\nvideoPage=" + isVideoPage + "\nvideoForce=" + settings.forceDarkVideoPages + "\nalreadyDarkSkip=" + settings.skipDarkeningAlreadyDarkPages + "\nexcluded=" + excluded + "\nalgorithmic=" + applied.algorithmicDarkening + "\nforceDark=" + applied.forceDark + "\nforceDarkStrategy=" + applied.forceDarkStrategy)
        if (settings.forceDarkPages && !excluded && (!isVideoPage || settings.forceDarkVideoPages) && !entry.documentIsAlreadyDark && !entry.fullscreenVideoDarkeningSuppressed) {
            val css = if (isYoutubeDocumentUrl(url)) YOUTUBE_PAGE_DARK_CSS else DEEP_DARK_CSS
            view.evaluateJavascript("(function(){var id='__https_browser_deep_dark';var style=document.getElementById(id);if(!style){style=document.createElement('style');style.id=id;(document.documentElement||document.head).appendChild(style);}style.textContent=" + JSONObject.quote(css) + ";})();", null)
        } else {
            view.evaluateJavascript("(function(){var e=document.getElementById('__https_browser_deep_dark');if(e)e.remove();})();", null)
        }
    }

    private fun isDarkModeExcluded(settings: BrowserSettings, url: String): Boolean {
        val host = youtubeHost(url)?.removePrefix("www.").orEmpty()
        if (host.isBlank()) return false
        return settings.darkModeExcludedHosts.any {
            val normalized = it.trim().lowercase().removePrefix("www.")
            normalized.isNotBlank() && (host == normalized || host.endsWith("." + normalized))
        }
    }

    private fun prepareDarkDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.darkDocumentStartScriptHandler?.remove() }
        entry.darkDocumentStartScriptHandler = null
        if (!entry.settings.forceDarkPages || isDarkModeExcluded(entry.settings, url)) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val isVideo = isVideoPlaybackDocumentUrl(url)
        if (isVideo && !entry.settings.forceDarkVideoPages) return
        val css = if (isYoutubeDocumentUrl(url)) YOUTUBE_PAGE_DARK_CSS else DEEP_DARK_CSS
        val script = "(function(){var alreadyDark=" + ALREADY_DARK_DOCUMENT_DETECTOR_SCRIPT + ";if(" + entry.settings.skipDarkeningAlreadyDarkPages + "&&alreadyDark)return;var id=\"__https_browser_deep_dark\";var style=document.getElementById(id);if(!style){style=document.createElement(\"style\");style.id=id;(document.documentElement||document.head).appendChild(style);}style.textContent=" + JSONObject.quote(css) + ";})();"
        val originRules = originRulesFor(url)
        if (originRules.isEmpty()) return
        runCatching { WebViewCompat.addDocumentStartJavaScript(entry.webView, script, originRules) }
            .onSuccess { entry.darkDocumentStartScriptHandler = it }
            .onFailure { throwable -> CrashDiagnostics.record("dark_document_start_unsupported", throwable.javaClass.simpleName + ": " + throwable.message.orEmpty()) }
    }

    private fun originRulesFor(url: String): Set<String> {
        val host = youtubeHost(url) ?: return emptySet()
        return setOf("https://" + host, "https://*." + host)
    }

    private fun detectAlreadyDarkDocument(view: WebView, entry: Entry, url: String) {
        if (!entry.settings.skipDarkeningAlreadyDarkPages) { entry.documentIsAlreadyDark = false; return }
        view.evaluateJavascript(ALREADY_DARK_DOCUMENT_DETECTOR_SCRIPT) { raw ->
            entry.documentIsAlreadyDark = raw == "true"
            CrashDiagnostics.record("dark_document_detected", "url=" + url + "\nalreadyDark=" + entry.documentIsAlreadyDark)
        }
    }
    /**
     * 指定標準リストからBraveが解決したYouTube scriptletだけを、ページのJSより先に注入する。
     * 任意追加リストのscriptletにはRust側で権限を与えていないため、ここで返らない。
     */
    private fun prepareSiteDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.siteDocumentStartScriptHandler?.remove() }
        entry.siteDocumentStartScriptHandler = null
        entry.siteDocumentStartScriptUrl = null
        if (!entry.adBlockingEnabled || !blocker.isReady()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = runCatching {
            JSONObject(blocker.cosmeticResources(url)).optString("injected_script").trim()
        }.getOrDefault("")
        if (script.isBlank()) return
        val originRules = originRulesFor(url)
        if (originRules.isEmpty()) return
        runCatching { WebViewCompat.addDocumentStartJavaScript(entry.webView, script, originRules) }
            .onSuccess {
                entry.siteDocumentStartScriptHandler = it
                entry.siteDocumentStartScriptUrl = url
            }
            .onFailure { throwable ->
                CrashDiagnostics.record("adblock_site_scriptlet_unsupported", throwable.javaClass.simpleName + ": " + throwable.message.orEmpty())
            }
    }

    private fun prepareYoutubeDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.documentStartScriptHandler?.remove() }
        entry.documentStartScriptHandler = null
        entry.documentStartScriptUrl = null
        // 親ページがGoogleでもYouTube iframeは同一WebView内で作られる。
        // origin ruleでYouTubeだけに限定するため、親URLがYouTubeでなくても事前登録する。
        if (!entry.adBlockingEnabled || !blocker.isReady()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            CrashDiagnostics.record("adblock_youtube_scriptlet_unsupported", "reason=document_start_api_unavailable")
            return
        }
        val script = blocker.documentStartScript(YOUTUBE_SCRIPTLET_DOCUMENT_URL)
        if (script.isBlank()) {
            CrashDiagnostics.record("adblock_youtube_scriptlet_prepared", "scriptlet=false\nparentHost=${youtubeHost(url).orEmpty()}")
            return
        }
        val parentHost = youtubeHost(url).orEmpty()
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(entry.webView, script, originRules)
        }.onSuccess { handler ->
            entry.documentStartScriptHandler = handler
            entry.documentStartScriptUrl = url
            CrashDiagnostics.record("adblock_youtube_scriptlet_prepared", "scriptlet=true\nparentHost=$parentHost\nchars=${script.length}")
        }.onFailure { throwable ->
            CrashDiagnostics.record("adblock_youtube_scriptlet_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
        }
    }

    /**
     * Brave エンジンが返す hostname-specific selector と、実際に DOM に存在する class/id に
     * 対応する generic selector だけを注入する。例外規則は native engine が評価する。
     */
    private fun applyBraveCosmeticFilters(view: WebView, url: String, enabled: Boolean, includeGeneric: Boolean) {
        val entry = entries.entries.firstOrNull { it.value.webView === view }?.value ?: return
        // YouTubeはWeb ComponentsとSPA遷移でDOM構造が頻繁に変わる。BraveのURL評価による
        // ネットワーク遮断は維持しつつ、広範なhostname/generic CSSとclass/id走査だけを
        // 適用しない。プレーヤー周辺を隠さない限定広告枠CSSは別経路で注入する。
        if (isYoutubeDocumentUrl(url)) {
            applyYoutubeCosmeticFilters(view, entry, url, enabled)
            return
        }
        // Google検索は動画タブへの遷移やプレビュー展開を同一文書内で行うことがある。
        // 汎用cosmetic規則がplayer/overlay由来のclass・idを隠すと、音声だけ残して黒白の
        // プレビュー層が見えることがあるため、Google検索にはネットワーク規則だけを適用する。
        if (isGoogleSearchDocumentUrl(url)) {
            clearCosmeticFilters(view, entry)
            return
        }
        if (!enabled || !blocker.isReady()) {
            if (entry.cosmeticAppliedUrl != null || entry.genericCosmeticAppliedUrl != null || entry.youtubeCosmeticAppliedUrl != null) {
                entry.cosmeticAppliedUrl = null
                entry.genericCosmeticAppliedUrl = null
                entry.youtubeCosmeticAppliedUrl = null
                view.evaluateJavascript(
                    "(function(){document.getElementById('__https_browser_adblock_static')?.remove();document.getElementById('__https_browser_adblock_generic')?.remove();document.getElementById('__https_browser_youtube_ad_css')?.remove();})();",
                    null
                )
            }
            return
        }
        val resources = runCatching { JSONObject(blocker.cosmeticResources(url)) }.getOrDefault(JSONObject())
        // サイト専用CSSは初期描画から一度だけ有効にする。
        if (entry.cosmeticAppliedUrl != url) {
            entry.cosmeticAppliedUrl = url
            val selectors = resources.optJSONArray("hide_selectors").toStringList()
            val staticCss = selectors.take(MAX_STATIC_COSMETIC_SELECTORS)
                .joinToString(",")
                .takeIf { it.isNotBlank() }
                ?.plus("{display:none!important;visibility:hidden!important;}")
                .orEmpty()
            view.evaluateJavascript(
                """
                (function(){
                  var id='__https_browser_adblock_static';
                  var style=document.getElementById(id);
                  if(!style){style=document.createElement('style');style.id=id;document.documentElement.appendChild(style);}
                  style.textContent=${JSONObject.quote(staticCss)};
                })();
                """.trimIndent(),
                null
            )
        }
        // generic selector抽出は初期描画と競合させない。ページ完了後に一度だけ遅延し、
        // 同じURLのWebViewがすでに別ページへ移った場合は実行しない。
        if (includeGeneric && entry.genericCosmeticAppliedUrl != url) {
            entry.genericCosmeticAppliedUrl = url
            val exceptions = resources.optJSONArray("exceptions")?.toString() ?: "[]"
            view.postDelayed({
                if (entry.isActive && entry.genericCosmeticAppliedUrl == url && entry.cosmeticAppliedUrl == url) {
                    applyGenericCosmeticFilters(view, exceptions)
                }
            }, GENERIC_COSMETIC_DELAY_MS)
        }
    }

    /** 一般ページ用のcosmetic CSSを確実に取り除く。ネットワーク規則は停止しない。 */
    private fun clearCosmeticFilters(view: WebView, entry: Entry) {
        entry.cosmeticAppliedUrl = null
        entry.genericCosmeticAppliedUrl = null
        entry.youtubeCosmeticAppliedUrl = null
        entry.youtubeCosmeticAggressiveApplied = false
        view.evaluateJavascript(
            "(function(){document.getElementById('__https_browser_adblock_static')?.remove();document.getElementById('__https_browser_adblock_generic')?.remove();document.getElementById('__https_browser_youtube_ad_css')?.remove();})();",
            null
        )
    }

    /** YouTubeのプレーヤー本体・サイズ計算へ触れず、明示的な広告枠だけを非表示にする。 */
    private fun applyYoutubeCosmeticFilters(view: WebView, entry: Entry, url: String, enabled: Boolean) {
        val aggressive = enabled && entry.settings.aggressiveAdBlockingEnabled
        if (entry.youtubeCosmeticAppliedUrl == url &&
            entry.youtubeCosmeticAggressiveApplied == aggressive
        ) return
        entry.youtubeCosmeticAppliedUrl = if (enabled) url else null
        entry.youtubeCosmeticAggressiveApplied = aggressive
        entry.cosmeticAppliedUrl = null
        entry.genericCosmeticAppliedUrl = null
        // APKには通常のYouTube cosmetic状態と、積極的ブロック用の状態が別々に存在する。
        // プレーヤー本体やサイズ計算へ触れず、積極モード時だけ明示的な広告枠CSSを適用する。
        val css = if (aggressive) YOUTUBE_AD_CSS else ""
        view.evaluateJavascript(
            """
            (function(){
              document.getElementById('__https_browser_adblock_static')?.remove();
              document.getElementById('__https_browser_adblock_generic')?.remove();
              var id='__https_browser_youtube_ad_css';
              var style=document.getElementById(id);
              if(!style){style=document.createElement('style');style.id=id;document.documentElement.appendChild(style);}
              style.textContent=${JSONObject.quote(css)};
            })();
            """.trimIndent(),
            null
        )
    }

    private fun applyGenericCosmeticFilters(view: WebView, exceptionsJson: String) {
        view.evaluateJavascript(COLLECT_COSMETIC_KEYS_SCRIPT) { raw ->
            val serialized = runCatching { JSONTokener(raw ?: "\"\"").nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val keys = runCatching { JSONObject(serialized) }.getOrNull() ?: return@evaluateJavascript
            val css = blocker.genericCosmeticCss(
                classesJson = keys.optJSONArray("classes")?.toString() ?: "[]",
                idsJson = keys.optJSONArray("ids")?.toString() ?: "[]",
                exceptionsJson = exceptionsJson
            )
            view.evaluateJavascript(
                """
                (function(){
                  var id='__https_browser_adblock_generic';
                  var style=document.getElementById(id);
                  if(!style){style=document.createElement('style');style.id=id;document.documentElement.appendChild(style);}
                  style.textContent=${JSONObject.quote(css)};
                })();
                """.trimIndent(),
                null
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { array -> List(array.length()) { index -> array.optString(index) }.filter(String::isNotBlank) }.orEmpty()

    private inner class SecureClient(private val tabId: String) : WebViewClientCompat() {
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
            // Brave エンジンが ABP/AdGuard の例外、第三者判定、resource type を評価する。
            // 独自の YouTube 除外や簡易 URL 判定は行わず、正規のフィルタ規則をそのまま尊重する。
            // shouldInterceptRequest はUIスレッド外から呼ばれ得る。ここで WebView.url など
            // View の状態には触れず、UIスレッドで保持した親ページURLだけを利用する。
            val documentUrl = entry.activeDocumentUrl.orEmpty().ifBlank { url }
            val resourceType = resourceTypeFor(request)
            // YouTube/Googlevideo/ytimgのiframe bootstrap、player JS、映像chunk、内部APIは
            // 再生必須として保護する。Google検索動画タブで起動したiframeと映像も同様に保護する。
            // 明示的なYouTube広告・計測専用ホスト/パスだけは規則評価を継続する。
            val protectedPlaybackResource = isYoutubePlaybackResource(url) ||
                isGoogleVideoPreviewResource(documentUrl, resourceType)
            val shouldCheck = !protectedPlaybackResource || isYoutubeAdOrTrackingNetwork(url)
            val minimalBlocked = entry.settings.aggressiveAdBlockingEnabled &&
                MinimalAdBlockClient.shouldBlockMinimalAd(url)
            if (minimalBlocked || (entry.adBlockingEnabled && shouldCheck && blocker.shouldBlock(
                    url = url,
                    documentUrl = documentUrl,
                    resourceType = resourceType
                ))
            ) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 204, "No Content",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0))
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CrashDiagnostics.recordWebViewNavigation(url)
            val entry = entries[tabId]
            entry?.rearmPageLifecycle(url)
            entry?.cosmeticAppliedUrl = null
            entry?.genericCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAppliedUrl = null
            entry?.activeDocumentUrl = url
            view.setBackgroundColor(android.graphics.Color.BLACK)
            // ページ内CSSは注入しない。Fulguris由来のネイティブ設定を再適用して、
            // SPA遷移後もWebViewの色テーマ契約だけを維持する。
            entry?.let { configure(view, it, url); detectAlreadyDarkDocument(view, it, url) }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = false)
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId]
            if (entry != null) {
                if (entry.lifecycleUrl != url || entry.pageFinishedDone) return
                entry.pageFinishedDone = true
            }
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = true)
            AdBlockInjector.inject(view, entry?.settings?.aggressiveAdBlockingEnabled == true)
            entry?.let { applyVideoPlaybackRate(view, it.settings.videoPlaybackRate); releaseDarkRevealGuard(view, it, url); completeBackNavigation(tabId, it, view) }
            view.evaluateJavascript(VIDEO_DIMENSIONS_REPORTER_SCRIPT, null)
            if (isVideoPlaybackDocumentUrl(url)) recordVideoViewportMetrics(view, url)
            entry?.let { scheduleCookieFlush(view, it) }
            entry?.callbacks?.onPageFinished(tabId, url, view.title)
            entries[tabId]?.callbacks?.onHistoryState(tabId, view.canGoBack(), view.canGoForward())
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {\n            entries[tabId]?.pageScale = newScale\n            super.onScaleChanged(view, oldScale, newScale)\n        }\n\n        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel() // 証明書エラーを無視して接続することは絶対にしない。
            entries[tabId]?.callbacks?.onSslError(error.url)
        }

        /**
         * WebViewの既定interstitialへ進ませず、危険ページでは直前の安全な文書へ戻す。
         * falseを渡すことで、個人利用・最小通信の方針に合わせてこの判定を報告しない。
         */
        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponseCompat
        ) {
            CrashDiagnostics.record("webview_safe_browsing_blocked", "threatType=$threatType")
            when {
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_BACK_TO_SAFETY) -> {
                    callback.backToSafety(false)
                    entries[tabId]?.callbacks?.onNotice("安全でない可能性があるページをブロックしました。")
                }
                WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL) -> {
                    // 古いWebViewでは、Chromium標準の警告画面を表示して利用者に判断を委ねる。
                    callback.showInterstitial(false)
                }
                else -> {
                    // 応答APIが不完全な実装では、既定の警告を試みる。失敗時もアプリ本体は落とさない。
                    runCatching { callback.showInterstitial(false) }
                }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // 同じURLを即時に再生成すると、壊れたページ・メモリ不足でレンダラーが再度落ちる無限ループになる。
            // 既に描画プロセスを失ったWebViewには loadUrl/clearHistory/stopLoading を実行せず、destroyだけを行う。
            val entry = entries.remove(tabId)
            entry?.isActive = false
            entry?.cookieFlushRunnable?.let(view::removeCallbacks)
            entry?.cookieFlushRunnable = null
            val callbacks = entry?.callbacks ?: BrowserWebCallbacks.Empty
            CrashDiagnostics.recordWebViewRendererGone(detail.didCrash(), detail.rendererPriorityAtExit())
            runCatching { view.destroy() }
            callbacks.onRendererGone(tabId)
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
            // 新規ウィンドウの WebView を、そのまま新しいタブへ接続する。
            // 空文字を loadedUrl に入れると Compose 再構成時に読み込み状態が不整合になるため null を維持する。
            val popupEntry = Entry(
                webView = popupView,
                callbacks = current.callbacks,
                settings = current.settings
            )
            entries[newTabId] = popupEntry
            configure(popupView, popupEntry)
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
                setDescription("ねこぶらうざからのダウンロード")
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
        var cosmeticAppliedUrl: String? = null,
        var genericCosmeticAppliedUrl: String? = null,
        var youtubeCosmeticAppliedUrl: String? = null,
        var documentStartScriptHandler: ScriptHandler? = null,
        var documentStartScriptUrl: String? = null,
        var youtubePictureInPictureScriptHandler: ScriptHandler? = null,
        var youtubeAdSanitizerScriptHandler: ScriptHandler? = null,
        var youtubeNoAdWarmPlayerScriptHandler: ScriptHandler? = null,
        var youtubeSabrPatchOnlyScriptHandler: ScriptHandler? = null,
        var youtubeCosmeticAggressiveApplied: Boolean = false,
        var appliedDarkModeExcludedHosts: List<String> = emptyList(),
        var appliedForceDark: Boolean? = null,
        var appliedForceDarkVideoPages: Boolean? = null,
        var appliedSkipDarkeningAlreadyDarkPages: Boolean? = null,
        var darkDocumentStartScriptHandler: ScriptHandler? = null,
        var darkRevealPending: Boolean = false,
        var documentIsAlreadyDark: Boolean = false,
        var lifecycleUrl: String? = null,
        var pageFinishedDone: Boolean = false,
        var pageScale: Float = 1f,
        var siteDocumentStartScriptHandler: ScriptHandler? = null,
        var siteDocumentStartScriptUrl: String? = null,
        var backNavigationInFlight: Boolean = false,
        var queuedBackRequests: Int = 0,
        var cookieFlushRunnable: Runnable? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        @Volatile var fullscreenVideoDarkeningSuppressed: Boolean = false,
        @Volatile var activeDocumentUrl: String? = null,
        @Volatile var adBlockingEnabled: Boolean = true,
        @Volatile var isActive: Boolean = true
    ) {
        @Synchronized fun beginBackNavigation(): Boolean {
            if (backNavigationInFlight) {
                queuedBackRequests = (queuedBackRequests + 1).coerceAtMost(MAX_QUEUED_BACK_REQUESTS)
                return false
            }
            backNavigationInFlight = true
            return true
        }
        @Synchronized fun cancelBackNavigation() {
            backNavigationInFlight = false
            queuedBackRequests = 0
        }
        @Synchronized fun completeBackNavigation(): Boolean {
            if (!backNavigationInFlight) return false
            backNavigationInFlight = false
            if (queuedBackRequests <= 0) return false
            queuedBackRequests -= 1
            return true
        }
        @Synchronized fun rearmPageLifecycle(url: String) {
            lifecycleUrl = url
            pageFinishedDone = false
        }
    )

    /** Cookie書込みをページ完了ごとに同期実行せず、連続遷移をまとめてから一度だけ行う。 */
    private fun scheduleCookieFlush(view: WebView, entry: Entry) {
        entry.cookieFlushRunnable?.let(view::removeCallbacks)
        val runnable = Runnable {
            entry.cookieFlushRunnable = null
            if (entry.isActive) runCatching { CookieManager.getInstance().flush() }
        }
        entry.cookieFlushRunnable = runnable
        view.postDelayed(runnable, COOKIE_FLUSH_DEBOUNCE_MS)
    }

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    private fun intentFallbackUrl(url: String): String? = runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.let(::upgradeToHttps)
    }.getOrNull()

    private fun youtubeHost(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun isYoutubeDocumentUrl(url: String): Boolean {
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    /** APKにも存在するShorts文書判定。通常のYouTube文書判定とは別に保持する。 */
    private fun isSafeYoutubeAdSelector(selector: String): Boolean {
        val normalized = selector.trim().lowercase()
        if (normalized.isBlank() || normalized.length > MAX_AGGRESSIVE_YOUTUBE_SELECTORS) return false
        val adTokens = listOf("ad", "promoted", "sponsor", "masthead", "merchandise", "paid", "brand")
        val layoutTokens = listOf("#player", "video", "iframe", "ytd-app", "ytm-app", "ytd-page-manager", "html", "body")
        return adTokens.any(normalized::contains) && layoutTokens.any(normalized::contains)
    }

    private fun isYoutubeShortsDocumentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!isYoutubeDocumentUrl(url)) return false
        return uri.path?.startsWith("/shorts/") == true
    }

    /** Google検索は動画タブとプレビュー展開を同じ検索文書上で行うため、広いcosmetic適用を避ける。 */
    private fun isGoogleSearchDocumentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return (host == "google.com" || host.endsWith(".google.com")) && uri.path == "/search"
    }

    private fun isGoogleVideoSearchDocumentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!isGoogleSearchDocumentUrl(url)) return false
        val query = uri.rawQuery.orEmpty()
        return GOOGLE_VIDEO_SEARCH_QUERY_REGEX.containsMatchIn(query)
    }

    /** ダークCSSと動画映像面の競合を避ける必要がある文書。 */
    private fun isVideoPlaybackDocumentUrl(url: String): Boolean =
        isYoutubeDocumentUrl(url) || isGoogleVideoSearchDocumentUrl(url)

    /** Google動画タブが生成するiframeと映像要求は、広告規則の誤遮断から守る。 */
    private fun isGoogleVideoPreviewResource(documentUrl: String, resourceType: String): Boolean =
        isGoogleVideoSearchDocumentUrl(documentUrl) && (resourceType == "media" || resourceType == "subdocument")

    /** YouTubeのiframe bootstrap・player JS・映像chunk・内部APIを広告規則の誤判定から守る。 */
    private fun isYoutubePlaybackResource(url: String): Boolean {
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "ytimg.com" || host.endsWith(".ytimg.com") ||
            host == "youtubei.googleapis.com"
    }

    /** 再生保護の例外として、広告・計測専用と明示できる宛先だけ規則評価を継続する。 */
    private fun isYoutubeAdOrTrackingNetwork(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        return host == "ads.youtube.com" || host.endsWith(".ads.youtube.com") ||
            host == "doubleclick.net" || host.endsWith(".doubleclick.net") ||
            host == "googlesyndication.com" || host.endsWith(".googlesyndication.com") ||
            host == "googleadservices.com" || host.endsWith(".googleadservices.com") ||
            host == "googletagservices.com" || host.endsWith(".googletagservices.com") ||
            ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
                (path.startsWith("/api/stats/ads") || path.startsWith("/_get_ads") ||
                    path.startsWith("/pcs/activeview") || path.startsWith("/pagead") ||
                    path.contains("/youtubei/v1/player/ad_break") || path.startsWith("/get_midroll_")))
    }

    private fun recordVideoViewportMetrics(view: WebView, url: String) {
        view.evaluateJavascript(VIDEO_VIEWPORT_METRICS_SCRIPT) { raw ->
            val metrics = runCatching { JSONTokener(raw ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
            if (metrics.isNotBlank()) {
                CrashDiagnostics.record(
                    "youtube_viewport_metrics",
                    "host=${youtubeHost(url).orEmpty()}\nwebViewWidth=${view.width}\nwebViewHeight=${view.height}\nscrollX=${view.scrollX}\nscrollY=${view.scrollY}\nscale=${view.scale}\n$metrics"
                )
            }
        }
    }

    private fun applyVideoPlaybackRate(view: WebView, rate: Float) {\n        val safeRate = rate.coerceIn(0.25f, 3f)\n        view.evaluateJavascript(String.format(java.util.Locale.US, VIDEO_PLAYBACK_RATE_SCRIPT, safeRate), null)\n    }\n\n    private fun resourceTypeFor(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val headers = request.requestHeaders
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value?.lowercase()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value?.lowercase().orEmpty()
        return when (destination) {
            "script" -> "script"
            "style" -> "stylesheet"
            "image" -> "image"
            "font" -> "font"
            "audio", "video", "track" -> "media"
            "iframe", "frame" -> "subdocument"
            "empty" -> "xmlhttprequest"
            else -> when {
                "text/css" in accept -> "stylesheet"
                "javascript" in accept || "ecmascript" in accept -> "script"
                "image/" in accept -> "image"
                "video/" in accept || "audio/" in accept -> "media"
                "application/json" in accept || "text/event-stream" in accept -> "xmlhttprequest"
                request.url.path?.endsWith(".js", true) == true -> "script"
                request.url.path?.endsWith(".css", true) == true -> "stylesheet"
                request.url.path?.matches(IMAGE_EXTENSION_REGEX) == true -> "image"
                request.url.path?.matches(MEDIA_EXTENSION_REGEX) == true -> "media"
                else -> "other"
            }
        }
    }

    private companion object {
        const val MAX_QUEUED_BACK_REQUESTS = 2000
        const val MAX_AGGRESSIVE_YOUTUBE_SELECTORS = 500
        const val MAX_SAFE_REDIRECT_BYTES = 131072
        const val MAX_SAFE_REDIRECT_DATA_URL_CHARS = 262144
        val SAFE_REDIRECT_IMAGE_MIME_TYPES = setOf(
            "image/gif", "image/jpeg", "image/png", "image/webp"
        )
        val SAFE_REDIRECT_SCRIPT_MIME_TYPES = setOf(
            "application/javascript", "application/json", "application/octet-stream",
            "application/wasm", "text/javascript", "text/plain"
        )

        const val VIDEO_DIMENSIONS_BRIDGE_NAME = "NekoBrowserVideo"
        const val DEEP_DARK_CSS = "html{background:#000!important;color-scheme:dark!important}body{background:#fff!important;color:#111!important;filter:invert(1) hue-rotate(180deg)!important}img,canvas,iframe,svg,picture,object,embed{filter:invert(1) hue-rotate(180deg)!important}video,video::-webkit-media-controls-panel,video::-webkit-media-controls-enclosure{filter:invert(1) hue-rotate(180deg)!important}input,textarea,select{background:#e8e8e8!important;color:#111!important}"
        val ALREADY_DARK_DOCUMENT_DETECTOR_SCRIPT = """(function(){
          function parseColor(value){
            var m=String(value||'').match(/rgba?\(\s*([\d.]+)[,\s]+\s*([\d.]+)[,\s]+\s*([\d.]+)(?:[,\s]+\s*([\d.]+))?\s*\)/i);
            if(!m){
              var hex=String(value||'').match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
              if(!hex) return null;
              var raw=hex[1];
              if(raw.length===3) raw=raw.replace(/(.)/g,'$1$1');
              return [parseInt(raw.slice(0,2),16),parseInt(raw.slice(2,4),16),parseInt(raw.slice(4,6),16)];
            }
            var a=m[4]===undefined?1:parseFloat(m[4]);
            return a>0.02?[parseFloat(m[1]),parseFloat(m[2]),parseFloat(m[3])]:null;
          }
          function luminance(rgb){return (0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2])/255;}
          function backgroundOf(node){
            var current=node;
            for(var i=0;current&&i<8;i++,current=current.parentElement){
              var color=parseColor(getComputedStyle(current).backgroundColor);
              if(color) return luminance(color);
            }
            return null;
          }
          var bodyBackground=backgroundOf(document.body);
          var background=bodyBackground===null?backgroundOf(document.documentElement):bodyBackground;
          var rootStyle=getComputedStyle(document.documentElement);
          var bodyStyle=document.body?getComputedStyle(document.body):null;
          var scheme=(rootStyle.colorScheme+' '+(bodyStyle?bodyStyle.colorScheme:'')).toLowerCase();
          var meta=document.querySelector('meta[name="theme-color"]');
          var metaColor=meta?parseColor(meta.content):null;
          if(background!==null) return background<=0.18||(background<=0.35&&scheme.indexOf('dark')!==-1);
          return scheme.indexOf('dark')!==-1||(metaColor!==null&&luminance(metaColor)<=0.18);
        })()"""
        val YOUTUBE_PAGE_DARK_CSS = "html,body,ytd-app,ytm-app{background:#0f0f0f!important;color:#f1f1f1!important;color-scheme:dark!important}#masthead-container,#masthead,ytd-masthead,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer{background:#0f0f0f!important;color:#f1f1f1!important}ytd-app *,ytm-app *{border-color:#3f3f3f!important}ytd-app a,ytm-app a,ytd-app yt-formatted-string,ytm-app yt-formatted-string,ytd-app h1,ytd-app h2,ytd-app h3,ytd-app h4,ytd-app span,ytm-app span{color:#f1f1f1!important}input,textarea,select{background:#202020!important;color:#f1f1f1!important;border-color:#555!important}video,video *,#player video,ytm-player video{filter:none!important;background:#000!important;color-scheme:normal!important}.ytp-gradient-top,.ytp-gradient-bottom{filter:none!important}"
        const val VIDEO_PLAYBACK_RATE_SCRIPT = """(function(rate){
          window.__httpsBrowserPlaybackRate=rate;
          function applyRate(){
            document.querySelectorAll('video').forEach(function(v){if(v.playbackRate!==rate)v.playbackRate=rate;});
          }
          applyRate();
          if(!window.__httpsBrowserPlaybackRateObserver){
            var observer=new MutationObserver(applyRate);
            observer.observe(document.documentElement||document,{childList:true,subtree:true});
            document.addEventListener('loadedmetadata',applyRate,true);
            document.addEventListener('canplay',applyRate,true);
            window.__httpsBrowserPlaybackRateObserver=observer;
          }
        })(%f);"""

        val YOUTUBE_PIP_UNLOCK_SCRIPT = """
            (function(){
              function modifyYtcfgFlags(){
                try{
                  if(!window.ytcfg || typeof window.ytcfg.get!=='function') return;
                  var config=window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS');
                  config=config&&config.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH;
                  if(!config || typeof config.serializedExperimentFlags!=='string') return;
                  var flags=config.serializedExperimentFlags;
                  [
                    ['html5_picture_in_picture_blocking_ontimeupdate=true','html5_picture_in_picture_blocking_ontimeupdate=false'],
                    ['html5_picture_in_picture_blocking_onresize=true','html5_picture_in_picture_blocking_onresize=false'],
                    ['html5_picture_in_picture_blocking_document_fullscreen=true','html5_picture_in_picture_blocking_document_fullscreen=false'],
                    ['html5_picture_in_picture_blocking_standard_api=true','html5_picture_in_picture_blocking_standard_api=false'],
                    ['html5_picture_in_picture_logging_onresize=true','html5_picture_in_picture_logging_onresize=false']
                  ].forEach(function(pair){flags=flags.replace(pair[0],pair[1]);});
                  config.serializedExperimentFlags=flags;
                }catch(_e){}
              }
              function unlock(video){
                if(!video) return;
                try{video.disablePictureInPicture=false;}catch(_e){}
                try{video.removeAttribute('disablePictureInPicture');}catch(_e){}
              }
              function unlockAll(){document.querySelectorAll('video').forEach(unlock);}
              function startVideos(){
                unlockAll();
                var root=document.documentElement||document;
                new MutationObserver(function(records){
                  records.forEach(function(record){
                    if(record.type==='attributes' && record.target && record.target.tagName==='VIDEO') unlock(record.target);
                    record.addedNodes&&record.addedNodes.forEach(function(node){
                      if(node.nodeType!==1) return;
                      if(node.tagName==='VIDEO') unlock(node);
                      if(node.querySelectorAll) node.querySelectorAll('video').forEach(unlock);
                    });
                  });
                }).observe(root,{subtree:true,childList:true,attributes:true,attributeFilter:['disablepictureinpicture']});
              }
              modifyYtcfgFlags();
              if(!window.ytcfg){
                document.addEventListener('load',function(event){
                  if(event.target&&event.target.tagName==='SCRIPT') modifyYtcfgFlags();
                },true);
              }
              if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',startVideos,{once:true}); else startVideos();
            })();
        """.trimIndent()
        val VIDEO_DIMENSIONS_REPORTER_SCRIPT = """
            (function(){
              if (window.__nekoBrowserVideoReporterInstalled) return;
              window.__nekoBrowserVideoReporterInstalled = true;
              function report(){
                try {
                  var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                  var visible = videos.filter(function(v){
                    var r=v.getBoundingClientRect();
                    return r.width>2 && r.height>2;
                  });
                  var video = visible.sort(function(a,b){
                    return b.getBoundingClientRect().width*b.getBoundingClientRect().height -
                           a.getBoundingClientRect().width*a.getBoundingClientRect().height;
                  })[0];
                  var r = video && video.getBoundingClientRect();
                  var w = video && (video.videoWidth || Math.round(r.width));
                  var h = video && (video.videoHeight || Math.round(r.height));
                  var has = !!video && w>0 && h>0;
                  var playing = has && !video.paused && !video.ended && video.readyState>2;
                  var rate = has ? Number(video.playbackRate || 1) : 1;
                  if (window.NekoBrowserVideo) {
                    window.NekoBrowserVideo.reportPlaybackState(!!has, !!playing, has?w:0, has?h:0, rate);
                  }
                } catch(e) {}
              }
              ['loadedmetadata','play','pause','ended','ratechange','resize'].forEach(function(name){
                document.addEventListener(name, report, true);
              });
              new MutationObserver(report).observe(document.documentElement || document, {childList:true,subtree:true});
              report();
              setInterval(report, 1000);
            })();
        """.trimIndent()
        const val MAX_STATIC_COSMETIC_SELECTORS = 500
        const val GENERIC_COSMETIC_DELAY_MS = 350L
        const val COOKIE_FLUSH_DEBOUNCE_MS = 750L
        const val YOUTUBE_SCRIPTLET_DOCUMENT_URL = "https://www.youtube.com/"
        // 指定101リストのyoutube.com/m.youtube.com専用cosmetic規則だけを固定適用する。
        // #player、video、ytm-player、grid/layoutコンテナは意図的に含めない。
        val YOUTUBE_AD_CSS = """
            #player-ads,.ytp-ad-overlay-container,.ytp-ad-module,
            ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-promoted-video-renderer,
            ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,
            ytd-action-companion-ad-renderer,ytm-ad-slot-renderer,
            ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,
            ytd-rich-item-renderer:has(> ytd-ad-slot-renderer),
            ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer),
            ytd-search-pyv-renderer.ytd-item-section-renderer,
            ytd-watch-next-secondary-results-renderer > ytd-ad-slot-renderer,
            ytd-rich-item-renderer > ytd-ad-slot-renderer,
            ytd-item-section-renderer > ytd-ad-slot-renderer,
            ytm-rich-item-renderer > ad-slot-renderer,
            lazy-list > ad-slot-renderer,
            ytm-companion-slot[data-content-type] > ytm-companion-ad-renderer,
            #masthead-ad.ytd-rich-grid-renderer,
            .ytp-suggested-action > .ytp-suggested-action-badge,
            yt-overlay-product-sticker {
              display:none!important;visibility:hidden!important;
            }
        """.trimIndent()
        // Google動画タブを含む動画文書で、映像面と重なり要素を実寸診断する。
        val VIDEO_VIEWPORT_METRICS_SCRIPT = """
            (function(){
              function rect(selector){
                var e=document.querySelector(selector),r=e&&e.getBoundingClientRect();
                return r?{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}:null;
              }
              return JSON.stringify({
                innerWidth:window.innerWidth,
                clientWidth:document.documentElement.clientWidth,
                scrollWidth:document.documentElement.scrollWidth,
                visualWidth:window.visualViewport?Math.round(window.visualViewport.width):null,
                visualOffsetLeft:window.visualViewport?Math.round(window.visualViewport.offsetLeft):null,
                scrollX:window.scrollX,
                documentOverflowX:getComputedStyle(document.documentElement).overflowX,
                bodyOverflowX:document.body?getComputedStyle(document.body).overflowX:null,
                leftStack:(document.elementsFromPoint?document.elementsFromPoint(1,Math.max(1,Math.min(window.innerHeight-1,160))):[]).slice(0,5).map(function(e){var s=getComputedStyle(e);return {tag:e.tagName,id:e.id,cls:(e.className&&String(e.className).slice(0,120))||'',position:s.position,z:s.zIndex,bg:s.backgroundColor};}),
                body:rect('body'),
                player:rect('#player,ytm-player'),
                video:rect('video')
              });
            })();
        """.trimIndent()
        // 各リソース要求ごとに Regex を生成しない。ページの大量リソース読み込み時の
        // Kotlinヒープ確保を抑え、ネイティブフィルタ評価だけに処理を限定する。
        val IMAGE_EXTENSION_REGEX = Regex(".*\\.(png|jpe?g|gif|webp|svg|avif)$", RegexOption.IGNORE_CASE)
        val MEDIA_EXTENSION_REGEX = Regex(".*\\.(mp4|webm|m3u8|mpd|mp3|m4a)$", RegexOption.IGNORE_CASE)
        val GOOGLE_VIDEO_SEARCH_QUERY_REGEX = Regex("(?:^|&)(?:tbm=vid|udm=7)(?:&|$)")
        val COLLECT_COSMETIC_KEYS_SCRIPT = """
            (function(){
              var classes=[],ids=[],seenClasses=new Set(),seenIds=new Set();
              var elements=document.querySelectorAll('[class],[id]');
              for(var i=0;i<elements.length;i++){
                if(ids.length>=800 && classes.length>=1200) break;
                var element=elements[i];
                if(element.id && !seenIds.has(element.id) && ids.length<800){seenIds.add(element.id);ids.push(element.id);}
                if(element.classList){element.classList.forEach(function(name){if(!seenClasses.has(name) && classes.length<1200){seenClasses.add(name);classes.push(name);}});}
              }
              return JSON.stringify({classes:classes,ids:ids});
            })();
        """.trimIndent()
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
    fun onBackHistoryExhausted(tabId: String) = Unit
    fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    fun onVideoDimensions(tabId: String, width: Int, height: Int) = Unit
    fun onVideoPlaybackState(tabId: String, hasVideo: Boolean, isPlaying: Boolean, playbackRate: Float) = Unit
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
        override fun onBackHistoryExhausted(tabId: String) = Unit
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
