package com.example.httpsbrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.URLUtil
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import com.example.httpsbrowser.CrashDiagnostics
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserDownloadRequest
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BraveAdBlockEngine
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BrowserWebViewRegistry(
    private val context: Context,
    private val blocker: BraveAdBlockEngine
) {
    private val entries = ConcurrentHashMap<String, Entry>()

    fun obtain(tab: BrowserTab, settings: BrowserSettings, callbacks: BrowserWebCallbacks): WebView {
        val entry = entries[tab.id] ?: Entry(createWebView(tab.id)).also { entries[tab.id] = it }
        entry.callbacks = callbacks
        entry.settings = settings
        entry.adBlockingEnabled = settings.adBlockingEnabled
        val aggressiveModeChanged = entry.aggressiveAdBlockingEnabled != settings.aggressiveAdBlockingEnabled
        entry.aggressiveAdBlockingEnabled = settings.aggressiveAdBlockingEnabled
        ensureYoutubePictureInPictureScript(entry)
        // 121e47bの構成を基準にする。動画文書を対象へ含めるかは明示設定で選択する。
        val darkModeChanged = entry.appliedForceDark != settings.forceDarkPages ||
            entry.appliedForceDarkVideoPages != settings.forceDarkVideoPages ||
            entry.appliedSkipDarkeningAlreadyDarkPages != settings.skipDarkeningAlreadyDarkPages ||
            entry.appliedDarkModeExcludedHosts != settings.darkModeExcludedHosts
        val currentUrl = entry.loadedUrl ?: tab.lastRequestedUrl
        configure(
            entry.webView,
            settings,
            isVideoPlaybackDocumentUrl(currentUrl),
            entry.documentIsAlreadyDark && settings.skipDarkeningAlreadyDarkPages,
            currentUrl
        )
        entry.appliedForceDark = settings.forceDarkPages
        entry.appliedForceDarkVideoPages = settings.forceDarkVideoPages
        entry.appliedSkipDarkeningAlreadyDarkPages = settings.skipDarkeningAlreadyDarkPages
        entry.appliedDarkModeExcludedHosts = settings.darkModeExcludedHosts
        if (darkModeChanged && entry.loadedUrl != null) {
            // 暗色化切替だけでWebViewを再読込しない。
            val url = entry.loadedUrl.orEmpty()
            applyDeepDarkCss(
                entry.webView,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && settings.skipDarkeningAlreadyDarkPages
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
        }
        if (aggressiveModeChanged && entry.loadedUrl != null) {
            // モード切替だけでは再読込せず、表示中文書へcosmetic規則を即時再適用する。
            applyBraveCosmeticFilters(
                entry.webView,
                entry.loadedUrl.orEmpty(),
                entry.adBlockingEnabled,
                includeGeneric = true,
                aggressive = entry.aggressiveAdBlockingEnabled
            )
        }
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.activeDocumentUrl = tab.lastRequestedUrl
            entry.rearmPageLifecycle(tab.lastRequestedUrl)
            CrashDiagnostics.recordWebViewNavigation(tab.lastRequestedUrl)
            prepareDarkDocumentStartScript(entry, tab.lastRequestedUrl)
            prepareSiteDocumentStartScript(entry, tab.lastRequestedUrl)
            prepareYoutubeDocumentStartScript(entry, tab.lastRequestedUrl)
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    /**
     * 標準フィルタの初回コンパイルまたは更新後に、すでに生成済みのWebViewへ遮断規則を再適用する。
     * 初期load時にengine未準備だった場合でも、次の遷移を待たずYouTube document-start scriptletを登録する。
     */
    fun refreshContentFiltering() {
        entries.values.forEach { entry ->
            val url = entry.webView.url ?: entry.loadedUrl ?: entry.activeDocumentUrl.orEmpty()
            if (url.isBlank()) return@forEach
            prepareSiteDocumentStartScript(entry, url)
            prepareYoutubeDocumentStartScript(entry, url)
            applyBraveCosmeticFilters(
                entry.webView,
                url,
                entry.adBlockingEnabled,
                includeGeneric = true,
                aggressive = entry.aggressiveAdBlockingEnabled
            )
            CrashDiagnostics.record("adblock_reapplied_after_engine_ready", "url=$url\naggressive=${entry.aggressiveAdBlockingEnabled}")
        }
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            if (isHttps(url)) {
                // 独自ホーム待機中にだけ旧文書callbackを遮断する保護を、次の明示遷移で解除する。
                entry.homeResetInProgress = false
                entry.loadedUrl = url
                entry.documentIsAlreadyDark = false
                entry.rearmPageLifecycle(url)
                // shouldInterceptRequest はUIスレッド外から呼ばれ得るため、
                // コールバック内で WebView.url を読む代わりに遷移前に親URLを保持する。
                entry.activeDocumentUrl = url
                // onPageStartedより前に旧文書の白い最終フレームを隠す。暗色化除外サイトから
                // 通常サイトへ連続遷移しても、白いページが先に見える状態を作らない。
                beginDarkRevealGuard(entry.webView, entry, url)
                CrashDiagnostics.recordWebViewNavigation(url)
                prepareDarkDocumentStartScript(entry, url)
                prepareSiteDocumentStartScript(entry, url)
                prepareYoutubeDocumentStartScript(entry, url)
                entry.webView.loadUrl(url)
            } else entry.callbacks.onBlockedNavigation(url)
        }
    }

    fun reload(tabId: String) = entries[tabId]?.let { entry ->
        val url = entry.webView.url.orEmpty()
        entry.activeDocumentUrl = url
        beginDarkRevealGuard(entry.webView, entry, url)
        entry.rearmPageLifecycle(url)
        entry.webView.reload()
    }

    /** Fulgurisと同様、キャッシュからの履歴遷移でも完了処理が再実行できるよう先に再armする。 */
    fun goBack(tabId: String) = entries[tabId]?.let { entry ->
        entry.webView.takeIf { canGoBack(tabId) }?.let { view ->
            val history = view.copyBackForwardList()
            val targetUrl = history.getItemAtIndex(history.currentIndex - 1).url
            entry.activeDocumentUrl = targetUrl
            beginDarkRevealGuard(view, entry, targetUrl)
            entry.rearmPageLifecycle(targetUrl)
            view.goBack()
        }
    }

    /** 独自ホーム復帰用のabout:blankを戻る先にせず、直前がHTTPS文書の時だけ戻る。 */
    fun canGoBack(tabId: String): Boolean = entries[tabId]?.webView?.let { view ->
        val history = view.copyBackForwardList()
        val previousIndex = history.currentIndex - 1
        previousIndex >= 0 && isHttps(history.getItemAtIndex(previousIndex).url)
    } == true

    /** 全画面custom video surfaceへページCSSの反転が及ばないよう、表示中だけ暗色CSSを外す。 */
    fun setFullscreenVideoDarkeningSuppressed(tabId: String, suppressed: Boolean) {
        entries[tabId]?.let { entry ->
            entry.fullscreenVideoDarkeningSuppressed = suppressed
            val url = entry.webView.url ?: entry.activeDocumentUrl.orEmpty()
            val videoPage = isVideoPlaybackDocumentUrl(url)
            applyDeepDarkCss(
                entry.webView,
                enabled = !suppressed && shouldApplyPageCssDarkening(
                    entry.settings,
                    videoPage,
                    url,
                    entry.documentIsAlreadyDark && entry.settings.skipDarkeningAlreadyDarkPages
                ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            CrashDiagnostics.record("video_dark_css_suppressed", "tab=$tabId\nsuppressed=$suppressed\nurl=$url")
        }
    }

    /**
     * 独自ホームへ復帰する際、同タブのChromium履歴を破棄する。
     * UIだけをホームへ切り替えると、次に開いたページから以前のサイトやforward履歴へ戻れてしまう。
     */
    fun resetForHome(tabId: String) {
        entries[tabId]?.let { entry ->
            entry.cookieFlushRunnable?.let(entry.webView::removeCallbacks)
            entry.cookieFlushRunnable = null
            entry.homeResetInProgress = true
            entry.documentIsAlreadyDark = false
            entry.loadedUrl = null
            entry.activeDocumentUrl = null
            entry.rearmPageLifecycle(ABOUT_BLANK_URL)
            entry.webView.apply {
                stopLoading()
                loadUrl(ABOUT_BLANK_URL)
                clearHistory()
                scrollTo(0, 0)
            }
            entry.callbacks.onHistoryState(tabId, false, false)
            CrashDiagnostics.record("webview_history_reset_for_home", "tab=$tabId")
        }
    }

    /** SPA遷移を含む実際の表示URL。共有とrenderer再作成ではタブ保存値より優先する。 */
    fun currentUrl(tabId: String): String? = entries[tabId]?.let { entry ->
        entry.webView.url?.takeIf(::isHttps)
            ?: entry.activeDocumentUrl?.takeIf(::isHttps)
            ?: entry.loadedUrl?.takeIf(::isHttps)
    }

    /**
     * 選択タブの通常WebViewをActivity rootのnative hostへ接続する。
     * Compose AndroidViewを介さないため、再構成時にAwContentsの親・測定経路を変えない。
     */
    fun attachToNativeHost(tabId: String, host: ViewGroup): Boolean {
        val view = entries[tabId]?.webView ?: return false
        // タブ切替で前面WebViewを一括除去したり不可視化すると、Chromiumは
        // 描画先の消滅として扱う場合がある。YouTubeの再生sessionを維持するため、
        // 既存WebViewはhost内で可視のまま重ね、選択タブだけを前面へ移動する。
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        return true
    }
    /**
     * 非選択タブはhost内に残す。親Viewからの切離しは、タブを閉じるかレジストリを
     * 破棄する時だけに限定し、動画・音声・ログインのWebView状態を保つ。
     */
    fun detachFromNativeHost(tabId: String, host: ViewGroup? = null) {
        val view = entries[tabId]?.webView ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (host == null) parent.removeView(view)
    }


    /**
     * Fulgurisと同じく、翻訳はGoogle Translateのページ遷移として実行する。
     * 端末内モデルや本文DOMの置換を使わないため、動的ページの部分翻訳・再スキャン・
     * 大容量JNIを持たず、ブラウザの戻る操作で原文へ戻れる。
     */
    fun translateToJapanese(tabId: String) = entries[tabId]?.let { entry ->
        val sourceUrl = entry.webView.url.orEmpty()
        val translateUrl = googleTranslateUrl(sourceUrl)
        when {
            translateUrl == null -> entry.callbacks.onNotice("HTTPSページの読み込み完了後に翻訳してください。")
            isGoogleTranslateDocumentUrl(sourceUrl) -> entry.callbacks.onNotice("このページはすでにGoogle翻訳で開かれています。")
            else -> load(tabId, translateUrl)
        }
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
    fun goForward(tabId: String) = entries[tabId]?.let { entry ->
        entry.webView.takeIf { it.canGoForward() }?.let { view ->
            val history = view.copyBackForwardList()
            val targetUrl = history.getItemAtIndex(history.currentIndex + 1).url
            entry.activeDocumentUrl = targetUrl
            beginDarkRevealGuard(view, entry, targetUrl)
            entry.rearmPageLifecycle(targetUrl)
            view.goForward()
        }
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
            runCatching { entry.siteDocumentStartScriptHandler?.remove() }
            runCatching { entry.youtubePictureInPictureScriptHandler?.remove() }
            runCatching { entry.youtubeAdSanitizerScriptHandler?.remove() }
            runCatching { entry.youtubeNoAdWarmPlayerScriptHandler?.remove() }
            runCatching { entry.youtubeSabrPatchOnlyScriptHandler?.remove() }
            entry.documentStartScriptHandler = null
            entry.siteDocumentStartScriptHandler = null
            entry.youtubePictureInPictureScriptHandler = null
            entry.youtubeAdSanitizerScriptHandler = null
            entry.youtubeNoAdWarmPlayerScriptHandler = null
            entry.youtubeSabrPatchOnlyScriptHandler = null
            entry.cookieFlushRunnable?.let(entry.webView::removeCallbacks)
            entry.cookieFlushRunnable = null
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
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

    /** 画面そのものが閉じる時だけ、ネイティブフィルタを解放する。 */
    fun close() {
        destroyAll()
        // 遅延集約中のCookieもアプリ終了時には確実にディスクへ反映する。
        runCatching { CookieManager.getInstance().flush() }
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

    private fun createWebView(tabId: String): WebView = object : WebView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val scrollRange = ((contentHeight * scale).toInt() - height).coerceAtLeast(0)
            val fraction = if (scrollRange == 0) 0f else t.toFloat() / scrollRange
            entries[tabId]?.callbacks?.onScrollPosition(tabId, fraction.coerceIn(0f, 1f))
        }
    }.apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        // FulgurisのWebViewEx/XML設定と同じく、ページ全体へかかるAndroidのfocus highlightを
        // 無効化する。WebViewの実描画面へ半透明のfocus層が残る端末差を避ける。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
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
            // Fulgurisと同じ通常モバイルviewport。wide viewportはdesktop mode専用であり、
            // 常時有効にするとYouTube/Google動画タブの幅・左端・初期縮尺が崩れ得る。
            useWideViewPort = false
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
            // Fulgurisの通常タブと同じく、初期フォーカスによるページ先頭への不要なscrollを避ける。
            setNeedInitialFocus(false)
        }
        // ログイン状態と埋め込みプレーヤーの認証をアプリ内で維持する。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // ページへ公開するのは再生videoの幅・高さを受け取るread-onlyの数値窓口だけ。
        // 任意URLやJavaScript実行機能を公開しない。
        addJavascriptInterface(object {
            @JavascriptInterface
            fun report(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    entries[tabId]?.callbacks?.onVideoDimensions(tabId, width, height)
                }
            }
        }, VIDEO_DIMENSIONS_BRIDGE_NAME)
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

    /**
     * `121e47b`で使用していた暗色化構成。
     *
     * WebView標準のAlgorithmic Darkening／Force Darkは全画面custom video surfaceにも及び得る。
     * そのため動画文書では、動画サイト暗色化設定がONでも標準暗色化を常に停止する。ページ本文だけの
     * CSS反転は別途許可し、`video`の二重反転で映像そのものを常に正常色に保つ。切替に伴うreloadはしない。
     */
    private fun configure(
        view: WebView,
        settings: BrowserSettings,
        videoPage: Boolean,
        documentIsAlreadyDark: Boolean = false,
        url: String = ""
    ) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        val allowPlatformDarkening = shouldApplyPlatformDarkening(settings, videoPage, documentIsAlreadyDark, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.setForceDarkAllowed(allowPlatformDarkening)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, allowPlatformDarkening)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                view.settings,
                if (allowPlatformDarkening) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
        if (allowPlatformDarkening && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                view.settings,
                WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY
            )
        }
        CrashDiagnostics.record(
            "dark_mode_configured",
            "engine=legacy_121e47b\nforceRequested=${settings.forceDarkPages}\nvideoPage=$videoPage\ndocumentIsAlreadyDark=$documentIsAlreadyDark\nmanualExclusion=${isDarkModeExcluded(settings, url)}\nplatformDarkening=$allowPlatformDarkening\npageCssDarkening=${shouldApplyPageCssDarkening(settings, videoPage, url, documentIsAlreadyDark)}"
        )
    }

    /** video surfaceの全画面合成を反転しないため、標準暗色化は動画文書では常に無効にする。 */
    private fun shouldApplyPlatformDarkening(
        settings: BrowserSettings,
        videoPage: Boolean,
        documentIsAlreadyDark: Boolean = false,
        url: String = ""
    ): Boolean = settings.forceDarkPages && !videoPage && !documentIsAlreadyDark && !isDarkModeExcluded(settings, url)

    /** 動画サイト上書きはページ本文のCSS反転だけを有効にする。Shortsは映像面を優先して常に除外する。 */
    private fun shouldApplyPageCssDarkening(
        settings: BrowserSettings,
        videoPage: Boolean,
        url: String = "",
        documentIsAlreadyDark: Boolean = false
    ): Boolean = settings.forceDarkPages && (!videoPage || settings.forceDarkVideoPages) &&
        !isYoutubeShortsDocumentUrl(url) && !documentIsAlreadyDark && !isDarkModeExcluded(settings, url)

    /** 設定したexample.comはwww・任意サブドメインを含め、host境界をまたいで誤一致しない。 */
    private fun isDarkModeExcluded(settings: BrowserSettings, url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull() ?: return false
        return settings.darkModeExcludedHosts.any { excluded ->
            val normalized = excluded.trim().lowercase(Locale.ROOT).removePrefix("www.")
            normalized.isNotBlank() && (host == normalized || host.endsWith(".$normalized"))
        }
    }

    /**
     * ページの背景・color-scheme・theme-colorを読むだけで、DOMを変更せずに既存暗色ページを判定する。
     * 背景輝度が0.18以下、またはページ自身がdark color-schemeを明示した場合のみ暗いとみなす。
     */
    private fun detectAlreadyDarkDocument(view: WebView, entry: Entry, url: String) {
        // YouTube・Google動画などは専用CSS/PiP保護経路を維持し、一般文書だけで判定する。
        if (!entry.settings.skipDarkeningAlreadyDarkPages || !isHttps(url) ||
            isVideoPlaybackDocumentUrl(url) || isDarkModeExcluded(entry.settings, url)
        ) {
            releaseDarkRevealGuard(view, entry, url)
            return
        }
        // 既に注入した反転CSSを外した本来のcomputed styleだけを読む。alpha=0の間に行うため白フラッシュは出ない。
        view.evaluateJavascript(DARK_DETECTOR_WITHOUT_OVERRIDE_SCRIPT) { result ->
            val isAlreadyDark = result.trim() == "true"
            // 非同期評価中に遷移・ホーム復帰した場合は、古い文書の結果を採用しない。
            if (entry.homeResetInProgress || entry.activeDocumentUrl != url) return@evaluateJavascript
            if (entry.documentIsAlreadyDark == isAlreadyDark) {
                releaseDarkRevealGuard(view, entry, url)
                return@evaluateJavascript
            }
            entry.documentIsAlreadyDark = isAlreadyDark
            val videoPage = isVideoPlaybackDocumentUrl(url)
            configure(view, entry.settings, videoPage, isAlreadyDark, url)
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(entry.settings, videoPage, url, isAlreadyDark),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            CrashDiagnostics.record("already_dark_document_detected", "url=$url\ndark=$isAlreadyDark")
            releaseDarkRevealGuard(view, entry, url)
        }
    }

    /** ページ開始時はnative WebView背景の黒を保ち、document-start CSS/暗色判定後にだけ本文を見せる。 */
    private fun beginDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        val shouldGuard = entry.settings.forceDarkPages && !isVideoPlaybackDocumentUrl(url) &&
            !isDarkModeExcluded(entry.settings, url)
        entry.darkRevealPending = shouldGuard
        view.alpha = if (shouldGuard) 0f else 1f
    }

    private fun releaseDarkRevealGuard(view: WebView, entry: Entry, url: String) {
        if (!entry.darkRevealPending || entry.activeDocumentUrl != url) return
        entry.darkRevealPending = false
        view.alpha = 1f
    }

    /**
     * 一般ページは121e47b型の反転CSSを維持する。一方YouTubeはWeb Componentsが多いため、
     * 反転でなく専用の前景・背景色を指定し、映像surfaceにfilterを一切適用しない。
     */
    /**
     * `<video>`の実符号化サイズを監視し、PiP用に横長・縦長をActivityへ通知する。
     * DOM変更・metadata・resize・再生開始のいずれでも再評価し、同じサイズはページ側で重複通知しない。
     */
    private fun installVideoDimensionsReporter(view: WebView) {
        view.evaluateJavascript(VIDEO_DIMENSIONS_REPORTER_SCRIPT, null)
    }

    private fun applyDeepDarkCss(view: WebView, enabled: Boolean, youtubePage: Boolean = false) {
        val css = when {
            !enabled -> ""
            youtubePage -> YOUTUBE_PAGE_DARK_CSS
            else -> DEEP_DARK_CSS
        }
        val script = """
            (function() {
              var id = '__https_browser_deep_dark';
              var style = document.getElementById(id);
              if (!style) { style = document.createElement('style'); style.id = id; document.documentElement.appendChild(style); }
              style.textContent = ${JSONObject.quote(css)};
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    /**
     * YouTube originだけでPiP阻害を解除し、広告遮断がONなら動画応答内の広告メタデータも
     * document start時から除去する。動画バイト列・認証Cookie・URL遷移には触れない。
     */
    private fun ensureYoutubePictureInPictureScript(entry: Entry) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            CrashDiagnostics.record("youtube_pip_unlock_unsupported", "reason=document_start_api_unavailable")
            return
        }
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        if (entry.youtubePictureInPictureScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_PIP_UNLOCK_SCRIPT, originRules)
            }.onSuccess { handler ->
                entry.youtubePictureInPictureScriptHandler = handler
                CrashDiagnostics.record("youtube_pip_unlock_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_pip_unlock_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
        if (!entry.adBlockingEnabled) {
            runCatching { entry.youtubeAdSanitizerScriptHandler?.remove() }
            runCatching { entry.youtubeNoAdWarmPlayerScriptHandler?.remove() }
            runCatching { entry.youtubeSabrPatchOnlyScriptHandler?.remove() }
            entry.youtubeAdSanitizerScriptHandler = null
            entry.youtubeNoAdWarmPlayerScriptHandler = null
            entry.youtubeSabrPatchOnlyScriptHandler = null
            return
        }
        if (entry.youtubeAdSanitizerScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_AD_SANITIZER_SCRIPT, originRules)
            }.onSuccess { handler ->
                entry.youtubeAdSanitizerScriptHandler = handler
                CrashDiagnostics.record("youtube_ad_sanitizer_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_ad_sanitizer_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
        // 広告を消せた安定sessionを作り直さず、SABR制御応答の待機値だけを短縮する。
        // 通常モードでは完全に外し、ユーザーが選ぶ攻めた広告遮断モードだけへ限定する。
        if (!entry.aggressiveAdBlockingEnabled) {
            runCatching { entry.youtubeNoAdWarmPlayerScriptHandler?.remove() }
            runCatching { entry.youtubeSabrPatchOnlyScriptHandler?.remove() }
            entry.youtubeNoAdWarmPlayerScriptHandler = null
            entry.youtubeSabrPatchOnlyScriptHandler = null
            return
        }
        // client側player requestが生じるSPA遷移だけへ作用し、初期responseや現在のsessionは変更しない。
        if (entry.youtubeNoAdWarmPlayerScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT, originRules)
            }.onSuccess { handler ->
                entry.youtubeNoAdWarmPlayerScriptHandler = handler
                CrashDiagnostics.record("youtube_no_ad_warm_player_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_no_ad_warm_player_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
        if (entry.youtubeSabrPatchOnlyScriptHandler == null) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_SABR_PATCH_ONLY_SCRIPT, originRules)
            }.onSuccess { handler ->
                entry.youtubeSabrPatchOnlyScriptHandler = handler
                CrashDiagnostics.record("youtube_sabr_patch_only_ready", "documentStart=true")
            }.onFailure { throwable ->
                CrashDiagnostics.record("youtube_sabr_patch_only_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }
    }

    /**
     * 指定2標準リストからBraveが解決したscriptletを、対応するHTTPS主文書のJSより先に注入する。
     * Rust側のtrust境界により、ユーザー追加URLの規則はscriptlet本文を返せない。
     * YouTubeはiframeにも同じscriptletを届ける専用経路があるため、ここでは二重注入を避ける。
     */
    /**
     * WebViewの初回可視化より前に一般ページの暗色CSSを登録し、白背景が一度描画されるのを防ぐ。
     * 指定ホスト・動画ページは登録しないため、既存のYouTube/PiP保護経路と手動除外を侵害しない。
     */
    private fun prepareDarkDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.darkDocumentStartScriptHandler?.remove() }
        entry.darkDocumentStartScriptHandler = null
        if (!shouldApplyPageCssDarkening(entry.settings, isVideoPlaybackDocumentUrl(url), url)) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = documentStartOriginRule(url) ?: return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(entry.webView, darkDocumentStartScript(), setOf(originRule))
        }.onSuccess { handler ->
            entry.darkDocumentStartScriptHandler = handler
        }.onFailure { throwable ->
            CrashDiagnostics.record("dark_document_start_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
        }
    }

    private fun prepareSiteDocumentStartScript(entry: Entry, url: String) {
        runCatching { entry.siteDocumentStartScriptHandler?.remove() }
        entry.siteDocumentStartScriptHandler = null
        entry.siteDocumentStartScriptUrl = null
        if (isYoutubeDocumentUrl(url) || !entry.adBlockingEnabled || !blocker.isReady()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            CrashDiagnostics.record("adblock_site_scriptlet_unsupported", "reason=document_start_api_unavailable")
            return
        }
        val originRule = documentStartOriginRule(url) ?: return
        val script = blocker.documentStartScript(url)
        if (script.isBlank()) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(entry.webView, script, setOf(originRule))
        }.onSuccess { handler ->
            entry.siteDocumentStartScriptHandler = handler
            entry.siteDocumentStartScriptUrl = url
            CrashDiagnostics.record(
                "adblock_site_scriptlet_prepared",
                "origin=$originRule\\nchars=${script.length}"
            )
        }.onFailure { throwable ->
            CrashDiagnostics.record(
                "adblock_site_scriptlet_unsupported",
                "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            )
        }
    }

    /**
     * 指定標準リストからBraveが解決したYouTube scriptletだけを、ページのJSより先に注入する。
     * 任意追加リストのscriptletにはRust側で権限を与えていないため、ここで返らない。
     */
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
    private fun applyBraveCosmeticFilters(
        view: WebView,
        url: String,
        enabled: Boolean,
        includeGeneric: Boolean,
        aggressive: Boolean = false
    ) {
        val entry = entries.entries.firstOrNull { it.value.webView === view }?.value ?: return
        // YouTubeはWeb ComponentsとSPA遷移でDOM構造が頻繁に変わる。BraveのURL評価による
        // ネットワーク遮断は維持しつつ、広範なhostname/generic CSSとclass/id走査だけを
        // 適用しない。プレーヤー周辺を隠さない限定広告枠CSSは別経路で注入する。
        if (isYoutubeDocumentUrl(url)) {
            applyYoutubeCosmeticFilters(view, entry, url, enabled, aggressive)
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
                entry.youtubeCosmeticAggressiveApplied = null
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
        entry.youtubeCosmeticAggressiveApplied = null
        view.evaluateJavascript(
            "(function(){document.getElementById('__https_browser_adblock_static')?.remove();document.getElementById('__https_browser_adblock_generic')?.remove();document.getElementById('__https_browser_youtube_ad_css')?.remove();})();",
            null
        )
    }

    /** YouTubeのプレーヤー本体・サイズ計算へ触れず、明示的な広告枠だけを非表示にする。 */
    private fun applyYoutubeCosmeticFilters(
        view: WebView,
        entry: Entry,
        url: String,
        enabled: Boolean,
        aggressive: Boolean
    ) {
        if (entry.youtubeCosmeticAppliedUrl == url && enabled &&
            entry.youtubeCosmeticAggressiveApplied == aggressive
        ) return
        entry.youtubeCosmeticAppliedUrl = if (enabled) url else null
        entry.youtubeCosmeticAggressiveApplied = if (enabled) aggressive else null
        // 同一ドキュメントで通常サイトからYouTubeへSPA遷移した場合にも、汎用CSSを残さない。
        entry.cosmeticAppliedUrl = null
        entry.genericCosmeticAppliedUrl = null
        // 通常モードでは指定フィルタのYouTube専用規則を、安全な広告selectorだけに絞る。
        // 攻めたモードでは後段でgeneric class/id規則も追加し、最大遮断を選べるようにする。
        val resources = if (enabled && blocker.isReady()) {
            runCatching { JSONObject(blocker.cosmeticResources(url)) }.getOrDefault(JSONObject())
        } else JSONObject()
        val filterCss = if (enabled) {
            val selectors = resources.optJSONArray("hide_selectors")
                .toStringList()
                .filter { selector -> aggressive || isSafeYoutubeAdSelector(selector) }
                .take(if (aggressive) MAX_AGGRESSIVE_YOUTUBE_SELECTORS else MAX_STATIC_COSMETIC_SELECTORS)
            selectors.joinToString(",").takeIf { it.isNotBlank() }
                ?.plus("{display:none!important;visibility:hidden!important;}")
                .orEmpty()
        } else ""
        val css = if (enabled) YOUTUBE_AD_CSS + "\n" + filterCss else ""
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
        // 攻めたモードではYouTubeでもBraveのgeneric cosmetic規則を一度だけ適用する。
        // 通常モードとGoogle動画タブには戻さず、再生安全性を維持する。
        if (aggressive && enabled && entry.genericCosmeticAppliedUrl != url) {
            entry.genericCosmeticAppliedUrl = url
            val exceptions = resources.optJSONArray("exceptions")?.toString() ?: "[]"
            view.postDelayed({
                if (entry.isActive && entry.genericCosmeticAppliedUrl == url &&
                    entry.youtubeCosmeticAppliedUrl == url && entry.aggressiveAdBlockingEnabled
                ) {
                    applyGenericCosmeticFilters(view, exceptions)
                }
            }, GENERIC_COSMETIC_DELAY_MS)
        } else if (!aggressive) {
            entry.genericCosmeticAppliedUrl = null
        }
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
            // Fulgurisと同様、main frame要求でも完了処理を再armする。履歴遷移・SPA・
            // YouTubeの複数onPageFinishedで、UI後処理が前回の状態に残るのを防ぐ。
            if (request.isForMainFrame) entry.rearmPageLifecycle(url)
            // Brave エンジンが ABP/AdGuard の例外、第三者判定、resource type を評価する。
            // 独自の YouTube 除外や簡易 URL 判定は行わず、正規のフィルタ規則をそのまま尊重する。
            // shouldInterceptRequest はUIスレッド外から呼ばれ得る。ここで WebView.url など
            // View の状態には触れず、UIスレッドで保持した親ページURLだけを利用する。
            val documentUrl = entry.activeDocumentUrl.orEmpty().ifBlank { url }
            val resourceType = resourceTypeFor(request)
            // YouTubeは映像chunkとiframeだけを再生必須として保護する。script/XHRまで全通過させると、
            // 指定フィルタが広告・計測要求を評価できなくなるため、そこは従来どおりBraveへ渡す。
            // Google検索の動画タブだけは黒画面回避のため、引き続き個別に全通過させる。
            val googleVideoPreviewDocument = isGoogleVideoSearchDocumentUrl(documentUrl)
            val protectedPlaybackResource = isYoutubePlaybackResource(url, resourceType) ||
                isGoogleVideoPreviewResource(documentUrl, resourceType)
            // 通常モードはGoogle動画タブと映像chunk/iframeを保護する。一方、攻めたモードは
            // Braveのfirst-party規則・redirect規則まで評価するため、これらの保護を意図的に外す。
            val shouldCheck = (!googleVideoPreviewDocument || entry.aggressiveAdBlockingEnabled) &&
                (!protectedPlaybackResource || isYoutubeAdOrTrackingNetwork(url) || entry.aggressiveAdBlockingEnabled)
            if (entry.adBlockingEnabled && shouldCheck) {
                val decision = blocker.networkDecision(
                    url = url,
                    documentUrl = documentUrl,
                    resourceType = resourceType
                )
                if (decision.shouldBlock) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", 204, "No Content",
                        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0))
                    )
                }
                // `$redirect`はBraveがdata URLとして返す。動画・主文書には適用せず、
                // 小さく検証済みのscript/style/image置換だけをWebViewへ返す。
                createSafeBraveRedirectResponse(decision.redirectDataUrl, resourceType)?.let { return it }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (!isHttps(url)) return
            val entry = entries[tabId] ?: return
            if (entry.homeResetInProgress) return
            // YouTube等のSPAはmain-frame loadを発生させずURLだけをhistory APIで更新する。
            // 共有・アドレスバー・renderer再作成用のタブURLをここで最新化する。
            entry.loadedUrl = url
            entry.activeDocumentUrl = url
            configure(
                view,
                entry.settings,
                isVideoPlaybackDocumentUrl(url),
                entry.documentIsAlreadyDark && entry.settings.skipDarkeningAlreadyDarkPages,
                url
            )
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        entry.settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && entry.settings.skipDarkeningAlreadyDarkPages
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            detectAlreadyDarkDocument(view, entry, url)
            entry.callbacks.onVisitedHistory(tabId, url)
            entry.callbacks.onHistoryState(tabId, canGoBack(tabId), view.canGoForward())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CrashDiagnostics.recordWebViewNavigation(url)
            val entry = entries[tabId]
            if (entry?.homeResetInProgress == true && url != ABOUT_BLANK_URL) {
                CrashDiagnostics.record("page_started_ignored_during_home_reset", "url=$url")
                return
            }
            entry?.documentIsAlreadyDark = false
            entry?.cosmeticAppliedUrl = null
            entry?.genericCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAggressiveApplied = null
            entry?.activeDocumentUrl = url
            entry?.rearmPageLifecycle(url)
            view.setBackgroundColor(android.graphics.Color.BLACK)
            entry?.let { beginDarkRevealGuard(view, it, url) }
            // 121e47bと同じく、遷移先が動画文書かどうかに応じて標準暗色化を再設定する。
            entry?.let {
                configure(view, it.settings, isVideoPlaybackDocumentUrl(url), url = url)
                // commit可視化まで待つとbodyの初期白背景が一瞬現れることがあるため、開始時にも適用する。
                applyDeepDarkCss(
                    view,
                    enabled = !it.fullscreenVideoDarkeningSuppressed &&
                        shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url),
                    youtubePage = isYoutubeDocumentUrl(url)
                )
            }
            installVideoDimensionsReporter(view)
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            if (entry?.homeResetInProgress == true && url != ABOUT_BLANK_URL) return
            // 121e47bの暗色化経路を、動画上書き設定を含めて初回可視化時から適用する。
            applyDeepDarkCss(
                view,
                enabled = entry?.let { !it.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url) } == true,
                youtubePage = isYoutubeDocumentUrl(url)
            )
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = false)
            if (entry != null) {
                if (entry.settings.skipDarkeningAlreadyDarkPages) detectAlreadyDarkDocument(view, entry, url)
                else releaseDarkRevealGuard(view, entry, url)
            }
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId] ?: return
            if (entry.homeResetInProgress) {
                // about:blank完了後も、次の明示遷移まで旧HTTPS callbackはすべて無視する。
                if (url != ABOUT_BLANK_URL) CrashDiagnostics.record("page_finished_ignored_during_home_reset", "url=$url")
                return
            }
            // FulgurisがYouTube/キャッシュ復帰で行うのと同じく、progress=100の最初の完了だけを
            // 採用する。重複したonPageFinishedでCSS注入・Cookie flush・履歴通知を繰り返さない。
            if (!entry.tryCompletePageLifecycle(view.progress)) {
                CrashDiagnostics.record("page_finished_skipped", "url=$url\\nprogress=${view.progress}")
                return
            }
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(
                        entry.settings,
                        isVideoPlaybackDocumentUrl(url),
                        url,
                        entry.documentIsAlreadyDark && entry.settings.skipDarkeningAlreadyDarkPages
                    ),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            applyBraveCosmeticFilters(view, url, entry.adBlockingEnabled, includeGeneric = true)
            if (entry.settings.skipDarkeningAlreadyDarkPages) detectAlreadyDarkDocument(view, entry, url)
            else releaseDarkRevealGuard(view, entry, url)
            if (isVideoPlaybackDocumentUrl(url)) recordVideoViewportMetrics(view, url)
            scheduleCookieFlush(view, entry)
            entry.callbacks.onPageFinished(tabId, url, view.title)
            entry.callbacks.onHistoryState(tabId, canGoBack(tabId), view.canGoForward())
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
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
            // native hostへ接続済みでも、終了済みrendererを親に残さない。
            (view.parent as? ViewGroup)?.removeView(view)
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
            configure(popupView, current.settings, false)
            // 新規ウィンドウの WebView を、そのまま新しいタブへ接続する。
            // 空文字を loadedUrl に入れると Compose 再構成時に読み込み状態が不整合になるため null を維持する。
            entries[newTabId] = Entry(
                webView = popupView,
                callbacks = current.callbacks,
                settings = current.settings,
                appliedForceDark = current.settings.forceDarkPages,
                appliedForceDarkVideoPages = current.settings.forceDarkVideoPages
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
            val entry = entries[tabId] ?: return
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            entry.callbacks.onDownloadRequested(
                BrowserDownloadRequest(
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    userAgent = userAgent,
                    cookie = CookieManager.getInstance().getCookie(url),
                    // 一部の配布サイトはRefererを要求するため、リンクを押したページのHTTPS URLも渡す。
                    referer = entry.webView.url?.takeIf(::isHttps)
                )
            )
        }
    }

    private data class Entry(
        val webView: WebView,
        var loadedUrl: String? = null,
        var cosmeticAppliedUrl: String? = null,
        var genericCosmeticAppliedUrl: String? = null,
        var youtubeCosmeticAppliedUrl: String? = null,
        var youtubeCosmeticAggressiveApplied: Boolean? = null,
        /** YouTube originを含むiframeへ登録するBrave scriptlet。 */
        var documentStartScriptHandler: ScriptHandler? = null,
        var documentStartScriptUrl: String? = null,
        /** 一般ページの白フラッシュを防ぐdocument-start暗色CSS。 */
        var darkDocumentStartScriptHandler: ScriptHandler? = null,
        /** 主文書のoriginにだけ登録する、指定2標準リスト由来のtrusted scriptlet。 */
        var siteDocumentStartScriptHandler: ScriptHandler? = null,
        var siteDocumentStartScriptUrl: String? = null,
        var youtubePictureInPictureScriptHandler: ScriptHandler? = null,
        /** 組込みYouTube広告メタデータ除去script。外部リストには実行権限を与えない。 */
        var youtubeAdSanitizerScriptHandler: ScriptHandler? = null,
        /** warm navigationのplayer requestへ広告なし指定を入れる組込みscript。攻めたモード限定。 */
        var youtubeNoAdWarmPlayerScriptHandler: ScriptHandler? = null,
        /** 既存sessionを再取得せずSABR backoffだけを短縮する組込みscript。攻めたモード限定。 */
        var youtubeSabrPatchOnlyScriptHandler: ScriptHandler? = null,
        var cookieFlushRunnable: Runnable? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        var appliedForceDark: Boolean? = null,
        var appliedForceDarkVideoPages: Boolean? = null,
        var appliedSkipDarkeningAlreadyDarkPages: Boolean? = null,
        var appliedDarkModeExcludedHosts: List<String>? = null,
        @Volatile var darkRevealPending: Boolean = false,
        /** PageCommitVisible後に読んだ、ページ自身の暗色テーマ状態。遷移・ホーム復帰では必ずfalseへ戻す。 */
        @Volatile var documentIsAlreadyDark: Boolean = false,
        /** about:blank完了まで、停止済みの旧HTTPS文書callbackをUIへ渡さない。 */
        @Volatile var homeResetInProgress: Boolean = false,
        @Volatile var aggressiveAdBlockingEnabled: Boolean = false,
        @Volatile var fullscreenVideoDarkeningSuppressed: Boolean = false,
        @Volatile var activeDocumentUrl: String? = null,
        @Volatile var adBlockingEnabled: Boolean = true,
        @Volatile var isActive: Boolean = true,
        @Volatile private var lifecycleUrl: String? = null,
        @Volatile private var pageFinishedDone: Boolean = false
    ) {
        /**
         * Fulguris WebPageClientの`onPageFinishedDone`再armに相当する最小状態。
         * `shouldInterceptRequest`からも呼ばれるため、UI状態やWebView本体には触れない。
         */
        @Synchronized
        fun rearmPageLifecycle(url: String) {
            lifecycleUrl = url
            pageFinishedDone = false
        }

        /** `progress == 100`の最初のonPageFinishedだけを後処理に通す。 */
        @Synchronized
        fun tryCompletePageLifecycle(progress: Int): Boolean {
            if (pageFinishedDone || progress != 100) return false
            pageFinishedDone = true
            return true
        }
    }

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

    /** FulgurisのGoogle Translate URL経路。中国語だけは地域を含む完全タグを渡す。 */
    private fun googleTranslateUrl(sourceUrl: String): String? {
        val source = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return null
        if (!source.scheme.equals("https", ignoreCase = true) || source.host.isNullOrBlank()) return null
        val locale = Locale.getDefault()
        val targetLanguage = if (locale.language.equals("zh", ignoreCase = true)) {
            locale.toLanguageTag()
        } else {
            locale.language.ifBlank { "en" }
        }
        return Uri.Builder()
            .scheme("https")
            .authority("translate.google.com")
            .appendPath("translate")
            .appendQueryParameter("sl", "auto")
            .appendQueryParameter("tl", targetLanguage)
            .appendQueryParameter("u", sourceUrl)
            .build()
            .toString()
    }

    private fun isGoogleTranslateDocumentUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host.equals("translate.google.com", ignoreCase = true) && uri.path == "/translate"
    }.getOrDefault(false)

    private fun intentFallbackUrl(url: String): String? = runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.let(::upgradeToHttps)
    }.getOrNull()

    private fun youtubeHost(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** document-start APIは完全なHTTPS origin ruleを要求する。 */
    private fun documentStartOriginRule(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) null
        else "https://${uri.host.lowercase()}"
    }.getOrNull()

    private fun isYoutubeDocumentUrl(url: String): Boolean {
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    /** ShortsはURLで判定できるため、ページ暗色化CSSを使わず映像surfaceを常にそのまま保つ。 */
    private fun isYoutubeShortsDocumentUrl(url: String): Boolean = runCatching {
        isYoutubeDocumentUrl(url) && URI(url).path.startsWith("/shorts/")
    }.getOrDefault(false)

    /** プレーヤー・ページ骨格に触れる規則を避け、広告・販促要素だけをYouTubeへ再適用する。 */
    private fun isSafeYoutubeAdSelector(selector: String): Boolean {
        val normalized = selector.lowercase().trim()
        if (normalized.isBlank() || normalized.length > 500) return false
        val adToken = listOf("ad", "promoted", "sponsor", "masthead", "merchandise", "paid", "brand").any(normalized::contains)
        val layoutToken = listOf("#player", "video", "iframe", "ytd-app", "ytm-app", "ytd-page-manager", "html", "body").any(normalized::contains)
        return adToken && !layoutToken
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

    /**
     * Google動画タブが生成するiframe、player script、映像chunk、内部APIは誤遮断から守る。
     * 画像・スタイル・fontなどの非必須要求は通常のBrave規則に渡し、広告枠の遮断余地を残す。
     */
    private fun isGoogleVideoPreviewResource(documentUrl: String, resourceType: String): Boolean =
        isGoogleVideoSearchDocumentUrl(documentUrl) && resourceType in PLAYBACK_CRITICAL_RESOURCE_TYPES

    /**
     * 映像復号とiframe表示に必須な要求だけを保護する。script/XHRは通常のBrave規則へ渡すことで、
     * 以前機能していたYouTube広告・計測のネットワーク遮断を回復する。
     */
    private fun isYoutubePlaybackResource(url: String, resourceType: String): Boolean {
        if (resourceType !in YOUTUBE_PLAYBACK_PROTECTED_RESOURCE_TYPES) return false
        val host = youtubeHost(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
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

    /**
     * Braveの`$redirect`はdata URLとして返る。WebViewではリダイレクト先URLを安全に再発行できないため、
     * 埋込み可能な小さなscript/style/imageだけを検証して返す。主文書、iframe、media、XHRは対象外とする。
     */
    private fun createSafeBraveRedirectResponse(dataUrl: String?, resourceType: String): WebResourceResponse? {
        if (dataUrl.isNullOrBlank() || resourceType !in SAFE_REDIRECT_RESOURCE_TYPES ||
            dataUrl.length > MAX_SAFE_REDIRECT_DATA_URL_CHARS
        ) return null
        val match = DATA_URL_BASE64_REGEX.matchEntire(dataUrl) ?: return null
        val mimeType = match.groupValues[1].lowercase()
        if (!isSafeRedirectMimeType(resourceType, mimeType)) return null
        val bytes = runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_SAFE_REDIRECT_BYTES }
            ?: return null
        val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.endsWith("+xml")) {
            "utf-8"
        } else null
        return WebResourceResponse(
            mimeType, encoding, 200, "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(bytes)
        )
    }

    private fun isSafeRedirectMimeType(resourceType: String, mimeType: String): Boolean = when (resourceType) {
        "script" -> mimeType in SAFE_REDIRECT_SCRIPT_MIME_TYPES
        "stylesheet" -> mimeType == "text/css"
        "image" -> mimeType in SAFE_REDIRECT_IMAGE_MIME_TYPES
        else -> false
    }

    private fun resourceTypeFor(request: WebResourceRequest): String {
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
        const val ABOUT_BLANK_URL = "about:blank"
        const val VIDEO_DIMENSIONS_BRIDGE_NAME = "NekoBrowserVideoDimensions"
        const val MAX_STATIC_COSMETIC_SELECTORS = 500
        const val MAX_AGGRESSIVE_YOUTUBE_SELECTORS = 2_000
        const val GENERIC_COSMETIC_DELAY_MS = 350L
        const val MAX_SAFE_REDIRECT_DATA_URL_CHARS = 256 * 1024
        const val MAX_SAFE_REDIRECT_BYTES = 128 * 1024
        val DATA_URL_BASE64_REGEX = Regex("^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$", RegexOption.IGNORE_CASE)
        val SAFE_REDIRECT_RESOURCE_TYPES = setOf("script", "stylesheet", "image")
        val SAFE_REDIRECT_SCRIPT_MIME_TYPES = setOf("application/javascript", "application/x-javascript", "text/javascript")
        val SAFE_REDIRECT_IMAGE_MIME_TYPES = setOf("image/gif", "image/png", "image/svg+xml")

        const val COOKIE_FLUSH_DEBOUNCE_MS = 750L
        const val YOUTUBE_SCRIPTLET_DOCUMENT_URL = "https://www.youtube.com/"
        /**
         * 既存ページのDOM・style・viewportを一切変更しない暗色テーマ検出。
         * 透明背景は親要素を遡り、0.18以下の実背景、または0.35以下かつdark color-schemeを採用する。
         */
        /** ページ自身のstyleを変えずに、既存のねこぶらうざ暗色styleだけを一時停止して本来の色を返す。 */
        val DARK_DETECTOR_WITHOUT_OVERRIDE_SCRIPT = """
            (function(){
              var style=document.getElementById('__https_browser_deep_dark');
              var previous=style?style.textContent:null;
              if(style) style.textContent='';
              try {
                function parseColor(value){
                  var m=String(value||'').match(/rgba?\(\s*([\d.]+)[,\s]+\s*([\d.]+)[,\s]+\s*([\d.]+)(?:[,\s]+\s*([\d.]+))?\s*\)/i);
                  if(!m) return null;
                  var a=m[4]===undefined?1:parseFloat(m[4]);
                  return a>0.02?[parseFloat(m[1]),parseFloat(m[2]),parseFloat(m[3])]:null;
                }
                function lum(rgb){return (0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2])/255;}
                function background(node){
                  for(var current=node,i=0;current&&i<8;i++,current=current.parentElement){
                    var color=parseColor(getComputedStyle(current).backgroundColor);
                    if(color) return lum(color);
                  }
                  return null;
                }
                var body=background(document.body);
                var value=body===null?background(document.documentElement):body;
                var root=getComputedStyle(document.documentElement);
                var bodyStyle=document.body?getComputedStyle(document.body):null;
                var scheme=(root.colorScheme+' '+(bodyStyle?bodyStyle.colorScheme:'')).toLowerCase();
                return value!==null ? (value<=0.18 || (value<=0.35&&scheme.indexOf('dark')!==-1)) : scheme.indexOf('dark')!==-1;
              } finally { if(style) style.textContent=previous; }
            })();
        """.trimIndent()

        val ALREADY_DARK_DOCUMENT_DETECTOR_SCRIPT = """
            (function(){
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
              function luminance(rgb){
                return (0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2])/255;
              }
              function backgroundOf(node){
                var current=node;
                for(var i=0; current && i<8; i++,current=current.parentElement){
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
              if(background!==null) return background<=0.18 || (background<=0.35 && scheme.indexOf('dark')!==-1);
              return scheme.indexOf('dark')!==-1 || (metaColor!==null && luminance(metaColor)<=0.18);
            })();
        """.trimIndent()

        /** 一般ページを最初の描画前から黒い暗色CSSで覆い、document-start未対応時はnative背景の黒を保つ。 */
        fun darkDocumentStartScript(): String = """
            (function(){
              var id='__https_browser_deep_dark';
              var style=document.getElementById(id);
              if(!style){style=document.createElement('style');style.id=id;(document.documentElement||document.head).appendChild(style);}
              style.textContent=${JSONObject.quote(DEEP_DARK_CSS)};
            })();
        """.trimIndent()

        // Brave Android PR #28593と同じく、YouTubeのページ側PiP阻害フラグを最小限だけ無効化する。
        // config未生成・対応外構造ではno-opとし、複数回のSPA遷移でも追加の要素を作らない。
        val VIDEO_DIMENSIONS_REPORTER_SCRIPT = """
            (function(){
              if(window.__nekoBrowserVideoDimensionsReporter) return;
              window.__nekoBrowserVideoDimensionsReporter=true;
              var last='';
              function bestVideo(){
                var videos=Array.prototype.slice.call(document.querySelectorAll('video'));
                videos.sort(function(a,b){
                  var as=(a.videoWidth||0)*(a.videoHeight||0),bs=(b.videoWidth||0)*(b.videoHeight||0);
                  if(!a.paused) as+=1000000000;
                  if(!b.paused) bs+=1000000000;
                  return bs-as;
                });
                return videos[0];
              }
              function report(){
                var video=bestVideo();
                if(!video || !video.videoWidth || !video.videoHeight) return;
                var value=video.videoWidth+'x'+video.videoHeight;
                if(value===last) return;
                last=value;
                try{window.NekoBrowserVideoDimensions.report(video.videoWidth,video.videoHeight);}catch(_e){}
              }
              function track(video){
                if(!video || video.__nekoBrowserDimensionsTracked) return;
                video.__nekoBrowserDimensionsTracked=true;
                ['loadedmetadata','resize','playing','loadeddata'].forEach(function(name){video.addEventListener(name,report,{passive:true});});
              }
              function scan(){document.querySelectorAll('video').forEach(track);report();}
              scan();
              new MutationObserver(scan).observe(document.documentElement||document,{subtree:true,childList:true});
            })();
        """.trimIndent()

        // uBlock Originの現行YouTube規則（adPlacements/adSlots/playerAds/Shorts）を、
        // WebViewで利用可能なdocument-start JavaScriptへ最小限に翻訳した組込み補助。
        // 通常動画の応答はJSON全走査をせずキー名だけを無効化し、Shortsだけで広告entryを解析する。
        val YOUTUBE_AD_SANITIZER_SCRIPT = """
            (function(){
              if(window.__nekoBrowserYouTubeAdSanitizer) return;
              window.__nekoBrowserYouTubeAdSanitizer=true;
              var adKeys=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
              function disablePlayerFields(value){
                if(!value || typeof value!=='object') return value;
                var roots=[value,value.playerResponse,value.response];
                roots.forEach(function(root){
                  if(!root || typeof root!=='object') return;
                  adKeys.forEach(function(key){try{delete root[key];}catch(_e){root[key]=undefined;}});
                });
                return value;
              }
              function disablePlayerText(text){
                if(typeof text!=='string' || text.length===0) return text;
                adKeys.forEach(function(key){
                  var expression=new RegExp('"'+key+'"','g');
                  text=text.replace(expression,'"no_ads"');
                });
                return text;
              }
              function isShortsAd(entry){
                if(!entry || typeof entry!=='object') return false;
                var reel=entry.command&&entry.command.reelWatchEndpoint;
                var params=reel&&reel.adClientParams;
                return entry.isAd===true || !!entry.adVideoId || !!entry.adBadge ||
                  !!(params&&(params.isAd===true || params.adVideoId || params.adBadge));
              }
              function pruneShorts(value,seen){
                if(!value || typeof value!=='object') return value;
                seen=seen||[]; if(seen.indexOf(value)>=0) return value; seen.push(value);
                if(Array.isArray(value)){
                  for(var i=value.length-1;i>=0;i--){if(isShortsAd(value[i])) value.splice(i,1); else pruneShorts(value[i],seen);}
                }else{
                  Object.keys(value).forEach(function(key){
                    if(adKeys.indexOf(key)>=0) {try{delete value[key];}catch(_e){value[key]=undefined;}}
                    else pruneShorts(value[key],seen);
                  });
                }
                return value;
              }
              function responseKind(url){
                url=String(url||'');
                if(/reel_watch_sequence/.test(url)) return 'shorts';
                return /(?:youtubei\/v1\/player|get_watch|playlist\?list=)/.test(url)?'player':'';
              }
              function sanitizeText(text,kind){
                if(kind==='player') return disablePlayerText(text);
                if(kind!=='shorts' || typeof text!=='string' || text.length===0) return text;
                try{return JSON.stringify(pruneShorts(JSON.parse(text)));}catch(_e){return text;}
              }
              function hookInitial(name){
                try{
                  var value=window[name];
                  Object.defineProperty(window,name,{configurable:true,get:function(){return value;},set:function(next){value=disablePlayerFields(next);}});
                  if(value) window[name]=value;
                }catch(_e){}
              }
              hookInitial('ytInitialPlayerResponse'); hookInitial('playerResponse');
              try{
                var originalFetch=window.fetch;
                window.fetch=function(){
                  var args=arguments,request=args[0],url=typeof request==='string'?request:(request&&request.url),kind=responseKind(url);
                  return originalFetch.apply(this,args).then(function(response){
                    if(!kind) return response;
                    return response.clone().text().then(function(text){
                      var clean=sanitizeText(text,kind); if(clean===text) return response;
                      return new Response(clean,{status:response.status,statusText:response.statusText,headers:response.headers});
                    }).catch(function(){return response;});
                  });
                };
              }catch(_e){}
              try{
                var proto=XMLHttpRequest.prototype,open=proto.open;
                proto.open=function(method,url){this.__nekoYouTubeAdResponseKind=responseKind(url);return open.apply(this,arguments);};
                var descriptor=Object.getOwnPropertyDescriptor(proto,'responseText');
                if(descriptor&&descriptor.get) Object.defineProperty(proto,'responseText',{configurable:true,get:function(){
                  var value=descriptor.get.call(this); return sanitizeText(value,this.__nekoYouTubeAdResponseKind);
                }});
              }catch(_e){}
            })();
        """.trimIndent()

        /**
         * warm navigationでクライアントが生成するplayer requestだけを補正する。
         * sessionのcancel/reloadや初期responseの削除を行わず、JSON.stringifyを先にhookして
         * YouTubeのlocker scriptより前に広告なしplayer contextを渡す。Object.assignは補助経路。
         */
        val YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT = """
            (function(){
              if(window.__nekoBrowserNoAdWarmPlayer) return;
              window.__nekoBrowserNoAdWarmPlayer=true;
              function patchText(text){
                if(typeof text!=='string'||text.indexOf('contentPlaybackContext')<0||text.indexOf('isInlinePlaybackNoAd')>=0) return text;
                return text.replace(/"contentPlaybackContext"\s*:\s*\{(?!\s*"isInlinePlaybackNoAd"\s*:\s*true)/,'"contentPlaybackContext":{"isInlinePlaybackNoAd":true,');
              }
              function patchCarrier(value){
                try{
                  if(value&&typeof value.body==='string') value.body=patchText(value.body);
                }catch(_e){}
                return value;
              }
              try{
                var realStringify=JSON.stringify;
                JSON.stringify=function(){return patchText(realStringify.apply(this,arguments));};
              }catch(_e){}
              try{
                var realAssign=Object.assign;
                Object.assign=new Proxy(realAssign,{apply:function(target,thisArg,args){
                  var result=Reflect.apply(target,thisArg,args);
                  patchCarrier(result);
                  if(args&&args.length>0) patchCarrier(args[0]);
                  return result;
                }});
              }catch(_e){}
            })();
        """.trimIndent()

        /**
         * Brave公式SABR対策から、既存の再生sessionを再取得する処理を除いた最小版。
         * googlevideoの小さな`sabr=1`制御応答だけをteeして、protobuf field 4のbackoffTimeMsを
         * 同じvarint長で50〜150msへ置き換える。映像chunk（1000 bytes以上）は無加工で返す。
         * 読取失敗を偽の成功responseへ変換せず、Chromium/YouTube本来の再試行へ委ねて再読込loopを防ぐ。
         */
        val YOUTUBE_SABR_PATCH_ONLY_SCRIPT = """
            (function(){
              if(window.__nekoBrowserSabrPatchOnly) return;
              window.__nekoBrowserSabrPatchOnly=true;
              var realFetch=window.fetch;
              if(typeof realFetch!=='function') return;
              var premiumCached=null;
              function isPremium(){
                if(premiumCached!==null) return premiumCached;
                var logo=document.querySelector('a#logo[title]');
                if(!logo) return false;
                premiumCached=/premium/i.test(logo.getAttribute('title')||'');
                return premiumCached;
              }
              function readSmall(reader){
                var chunks=[],total=0;
                return reader.read().then(function pump(result){
                  if(result.done){
                    var merged=new Uint8Array(total),offset=0;
                    for(var i=0;i<chunks.length;i++){merged.set(chunks[i],offset);offset+=chunks[i].length;}
                    return merged;
                  }
                  chunks.push(result.value);total+=result.value.length;
                  if(total>=1000){try{reader.cancel();}catch(_e){}return null;}
                  return reader.read().then(pump);
                });
              }
              function patchBackoff(bytes){
                var patched=false;
                for(var i=0;i<bytes.length-2;i++){
                  if(bytes[i]!==0x20) continue;
                  var value=0,shift=0,end=i+1;
                  while(end<bytes.length&&shift<35){
                    value|=(bytes[end]&0x7f)<<shift;
                    if(!(bytes[end]&0x80)){end++;break;}
                    shift+=7;end++;
                  }
                  if(value>500&&value<100000){
                    // 初期sessionの再取得はせず、YouTubeが送った長いSABR待機値だけを25〜74msへ縮める。
                    // ゼロ固定にはせず僅かなjitterを残し、同時再試行の集中と無限loopを避ける。
                    var target=25+Math.floor(Math.random()*50),pos=i+1,remaining=target;
                    while(pos<end-1){bytes[pos++]=(remaining&0x7f)|0x80;remaining>>>=7;}
                    bytes[pos]=remaining&0x7f;patched=true;
                  }
                }
                return patched;
              }
              function isSabrControlUrl(url){return url.indexOf('googlevideo.com')>=0&&url.indexOf('sabr=1')>=0;}
              function patchArrayBuffer(buffer){
                if(!buffer||!buffer.byteLength||buffer.byteLength>=1000) return buffer;
                var bytes=new Uint8Array(buffer.slice(0));
                return patchBackoff(bytes)?bytes.buffer:buffer;
              }
              window.fetch=function(resource,init){
                var url=typeof resource==='string'?resource:(resource&&resource.url)||'';
                if(!isSabrControlUrl(url)||isPremium()) return realFetch.apply(this,arguments);
                return realFetch.apply(this,arguments).then(function(response){
                  if(!response.ok||!response.body) return response;
                  var pass,scan,reinit;
                  try{
                    var streams=response.body.tee();pass=streams[0];scan=streams[1];
                    reinit={status:response.status,statusText:response.statusText,headers:response.headers};
                  }catch(_e){return response;}
                  function passThrough(){return new Response(pass,reinit);}
                  return readSmall(scan.getReader()).then(function(bytes){
                    if(bytes===null) return passThrough();
                    patchBackoff(bytes);
                    var out=new Response(bytes,reinit);
                    try{Object.defineProperty(out,'url',{value:response.url,configurable:true});Object.defineProperty(out,'type',{value:response.type,configurable:true});}catch(_e){}
                    return out;
                  });
                });
              };
              // 一部のWebView配信経路はfetchでなくXHRを使う。arraybuffer型の小さなSABR制御応答だけを
              // 読み替え、映像chunk・初期player response・認証情報・network request自体には触れない。
              try{
                var xhrProto=typeof XMLHttpRequest==='function'&&XMLHttpRequest.prototype;
                if(xhrProto){
                  var realOpen=xhrProto.open,realSend=xhrProto.send;
                  xhrProto.open=function(method,url){
                    this.__nekoSabrControl=isSabrControlUrl(String(url||''));
                    return realOpen.apply(this,arguments);
                  };
                  xhrProto.send=function(){
                    if(this.__nekoSabrControl&&!isPremium()){
                      var responsePatched=false;
                      var patchResponse=function(){
                        if(responsePatched) return;
                        try{
                          var original=this.response;
                          if(!(original instanceof ArrayBuffer)) return;
                          var patched=patchArrayBuffer(original);
                          if(patched===original){responsePatched=true;return;}
                          Object.defineProperty(this,'response',{configurable:true,get:function(){return patched;}});
                          if(this.response===patched) responsePatched=true;
                        }catch(_e){}
                      };
                      // playerがloadで先にresponseを読む経路に備え、readyState=4/loadで先にpatchする。
                      this.addEventListener('readystatechange',function(){if(this.readyState===4) patchResponse.call(this);});
                      this.addEventListener('load',patchResponse);
                      this.addEventListener('loadend',patchResponse,{once:true});
                    }
                    return realSend.apply(this,arguments);
                  };
                }
              }catch(_e){}
            })();
        """.trimIndent()

        val YOUTUBE_PIP_UNLOCK_SCRIPT = """
            (function(){
              function modifyYtcfgFlags(){
                try{
                  if(!window.ytcfg || typeof window.ytcfg.get!=='function') return;
                  var config=window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS');
                  config=config&&config.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH;
                  if(!config || typeof config.serializedExperimentFlags!=='string') return;
                  var flags=config.serializedExperimentFlags;
                  var replacements=[
                    ['html5_picture_in_picture_blocking_ontimeupdate=true','html5_picture_in_picture_blocking_ontimeupdate=false'],
                    ['html5_picture_in_picture_blocking_onresize=true','html5_picture_in_picture_blocking_onresize=false'],
                    ['html5_picture_in_picture_blocking_document_fullscreen=true','html5_picture_in_picture_blocking_document_fullscreen=false'],
                    ['html5_picture_in_picture_blocking_standard_api=true','html5_picture_in_picture_blocking_standard_api=false'],
                    ['html5_picture_in_picture_logging_onresize=true','html5_picture_in_picture_logging_onresize=false']
                  ];
                  replacements.forEach(function(pair){flags=flags.replace(pair[0],pair[1]);});
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
        // 指定101リストのyoutube.com/m.youtube.com専用cosmetic規則だけを固定適用する。
        // #player、video、ytm-player、grid/layoutコンテナは意図的に含めない。
        val DEEP_DARK_CSS = "html{background:#000!important;color-scheme:dark!important}" +
            "body{background:#fff!important;color:#111!important;filter:invert(1) hue-rotate(180deg)!important}" +
            "img,canvas,iframe,svg,picture,object,embed{filter:invert(1) hue-rotate(180deg)!important}" +
            "video,video::-webkit-media-controls-panel,video::-webkit-media-controls-enclosure{filter:invert(1) hue-rotate(180deg)!important}" +
            "input,textarea,select{background:#e8e8e8!important;color:#111!important}"
        // YouTubeは反転ではなく前景・背景を直接指定し、動画surfaceは常にfilter:noneで保護する。
        val YOUTUBE_PAGE_DARK_CSS = "html,body,ytd-app,ytm-app{background:#0f0f0f!important;color:#f1f1f1!important;color-scheme:dark!important}" +
            "#masthead-container,#masthead,ytd-masthead,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer{background:#0f0f0f!important;color:#f1f1f1!important}" +
            "ytd-app *,ytm-app *{border-color:#3f3f3f!important}" +
            "ytd-app a,ytm-app a,ytd-app yt-formatted-string,ytm-app yt-formatted-string,ytd-app h1,ytd-app h2,ytd-app h3,ytd-app h4,ytd-app span,ytm-app span{color:#f1f1f1!important}" +
            "input,textarea,select{background:#202020!important;color:#f1f1f1!important;border-color:#555!important}" +
            "video,video *,#player video,ytm-player video{filter:none!important;background:#000!important;color-scheme:normal!important}" +
            ".ytp-gradient-top,.ytp-gradient-bottom{filter:none!important}"
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
        val PLAYBACK_CRITICAL_RESOURCE_TYPES = setOf("media", "subdocument", "script", "xmlhttprequest")
        val YOUTUBE_PLAYBACK_PROTECTED_RESOURCE_TYPES = setOf("media", "subdocument")
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
    fun onVisitedHistory(tabId: String, url: String)
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
    fun onVideoDimensions(tabId: String, width: Int, height: Int)
    fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit)
    fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit)
    fun onPopupRequested(): String?
    fun onLinkLongPressed(url: String)
    fun onDownloadRequested(request: BrowserDownloadRequest)
    fun onPageArchiveReady(sourcePath: String, fileName: String)
    fun onExternalAppRequested(url: String)
    fun onPageInteraction()
    fun onNotice(message: String)

    data object Empty : BrowserWebCallbacks {
        override fun onPageStarted(tabId: String, url: String) = Unit
        override fun onPageFinished(tabId: String, url: String, title: String?) = Unit
        override fun onVisitedHistory(tabId: String, url: String) = Unit
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
        override fun onVideoDimensions(tabId: String, width: Int, height: Int) = Unit
        override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = reply(false)
        override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = reply(false)
        override fun onPopupRequested(): String? = null
        override fun onLinkLongPressed(url: String) = Unit
        override fun onDownloadRequested(request: BrowserDownloadRequest) = Unit
        override fun onPageArchiveReady(sourcePath: String, fileName: String) = Unit
        override fun onExternalAppRequested(url: String) = Unit
        override fun onPageInteraction() = Unit
        override fun onNotice(message: String) = Unit
    }
}
