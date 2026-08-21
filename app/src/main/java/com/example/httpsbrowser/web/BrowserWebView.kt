package com.example.httpsbrowser.web

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.view.View
import android.view.ViewGroup
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
            entry.appliedForceDarkVideoPages != settings.forceDarkVideoPages
        configure(entry.webView, settings, isVideoPlaybackDocumentUrl(tab.lastRequestedUrl))
        entry.appliedForceDark = settings.forceDarkPages
        entry.appliedForceDarkVideoPages = settings.forceDarkVideoPages
        if (darkModeChanged && entry.loadedUrl != null) {
            // 暗色化切替だけでWebViewを再読込しない。
            val url = entry.loadedUrl.orEmpty()
            applyDeepDarkCss(
                entry.webView,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(settings, isVideoPlaybackDocumentUrl(url), url),
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
            prepareSiteDocumentStartScript(entry, tab.lastRequestedUrl)
            prepareYoutubeDocumentStartScript(entry, tab.lastRequestedUrl)
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            if (isHttps(url)) {
                entry.loadedUrl = url
                entry.rearmPageLifecycle(url)
                // shouldInterceptRequest はUIスレッド外から呼ばれ得るため、
                // コールバック内で WebView.url を読む代わりに遷移前に親URLを保持する。
                entry.activeDocumentUrl = url
                CrashDiagnostics.recordWebViewNavigation(url)
                prepareSiteDocumentStartScript(entry, url)
                prepareYoutubeDocumentStartScript(entry, url)
                entry.webView.loadUrl(url)
            } else entry.callbacks.onBlockedNavigation(url)
        }
    }

    fun reload(tabId: String) = entries[tabId]?.let { entry ->
        entry.rearmPageLifecycle(entry.webView.url.orEmpty())
        entry.webView.reload()
    }

    /** Fulgurisと同様、キャッシュからの履歴遷移でも完了処理が再実行できるよう先に再armする。 */
    fun goBack(tabId: String) = entries[tabId]?.let { entry ->
        entry.webView.takeIf { canGoBack(tabId) }?.let { view ->
            entry.rearmPageLifecycle(view.url.orEmpty())
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
                enabled = !suppressed && shouldApplyPageCssDarkening(entry.settings, videoPage, url),
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
        if (view.parent === host) return true
        (view.parent as? ViewGroup)?.removeView(view)
        host.removeAllViews()
        host.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        view.visibility = View.VISIBLE
        return true
    }

    /** 非選択タブは破棄せずnative hostからだけ外す。タブ履歴・ログイン状態・再生状態を保持する。 */
    fun detachFromNativeHost(tabId: String, host: ViewGroup? = null) {
        val view = entries[tabId]?.webView ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (host == null || parent === host) parent.removeView(view)
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
            entry.rearmPageLifecycle(view.url.orEmpty())
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
            entry.documentStartScriptHandler = null
            entry.siteDocumentStartScriptHandler = null
            entry.youtubePictureInPictureScriptHandler = null
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
    private fun configure(view: WebView, settings: BrowserSettings, videoPage: Boolean) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        val allowPlatformDarkening = shouldApplyPlatformDarkening(settings, videoPage)
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
            "engine=legacy_121e47b\nforceRequested=${settings.forceDarkPages}\nvideoPage=$videoPage\nplatformDarkening=$allowPlatformDarkening\npageCssDarkening=${shouldApplyPageCssDarkening(settings, videoPage)}"
        )
    }

    /** video surfaceの全画面合成を反転しないため、標準暗色化は動画文書では常に無効にする。 */
    private fun shouldApplyPlatformDarkening(settings: BrowserSettings, videoPage: Boolean): Boolean =
        settings.forceDarkPages && !videoPage

    /** 動画サイト上書きはページ本文のCSS反転だけを有効にする。Shortsは映像面を優先して常に除外する。 */
    private fun shouldApplyPageCssDarkening(settings: BrowserSettings, videoPage: Boolean, url: String = ""): Boolean =
        settings.forceDarkPages && (!videoPage || settings.forceDarkVideoPages) && !isYoutubeShortsDocumentUrl(url)

    /**
     * 一般ページは121e47b型の反転CSSを維持する。一方YouTubeはWeb Componentsが多いため、
     * 反転でなく専用の前景・背景色を指定し、映像surfaceにfilterを一切適用しない。
     */
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
     * Brave Androidの公開issueで判明したYouTubeの`disablePictureInPicture`属性を、YouTube origin
     * に限ってdocument start時から解除する。player response・ネットワーク応答・広告判定は改変しない。
     */
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
            WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_PIP_UNLOCK_SCRIPT, originRules)
        }.onSuccess { handler ->
            entry.youtubePictureInPictureScriptHandler = handler
            CrashDiagnostics.record("youtube_pip_unlock_ready", "documentStart=true")
        }.onFailure { throwable ->
            CrashDiagnostics.record("youtube_pip_unlock_unsupported", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
        }
    }

    /**
     * 指定2標準リストからBraveが解決したscriptletを、対応するHTTPS主文書のJSより先に注入する。
     * Rust側のtrust境界により、ユーザー追加URLの規則はscriptlet本文を返せない。
     * YouTubeはiframeにも同じscriptletを届ける専用経路があるため、ここでは二重注入を避ける。
     */
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
            // YouTube等のSPAはmain-frame loadを発生させずURLだけをhistory APIで更新する。
            // 共有・アドレスバー・renderer再作成用のタブURLをここで最新化する。
            entry.loadedUrl = url
            entry.activeDocumentUrl = url
            configure(view, entry.settings, isVideoPlaybackDocumentUrl(url))
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(entry.settings, isVideoPlaybackDocumentUrl(url), url),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            entry.callbacks.onVisitedHistory(tabId, url)
            entry.callbacks.onHistoryState(tabId, canGoBack(tabId), view.canGoForward())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CrashDiagnostics.recordWebViewNavigation(url)
            val entry = entries[tabId]
            entry?.cosmeticAppliedUrl = null
            entry?.genericCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAggressiveApplied = null
            entry?.activeDocumentUrl = url
            entry?.rearmPageLifecycle(url)
            view.setBackgroundColor(android.graphics.Color.BLACK)
            // 121e47bと同じく、遷移先が動画文書かどうかに応じて標準暗色化を再設定する。
            entry?.let {
                configure(view, it.settings, isVideoPlaybackDocumentUrl(url))
                // commit可視化まで待つとbodyの初期白背景が一瞬現れることがあるため、開始時にも適用する。
                applyDeepDarkCss(
                    view,
                    enabled = !it.fullscreenVideoDarkeningSuppressed &&
                        shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url),
                    youtubePage = isYoutubeDocumentUrl(url)
                )
            }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            // 121e47bの暗色化経路を、動画上書き設定を含めて初回可視化時から適用する。
            applyDeepDarkCss(
                view,
                enabled = entry?.let { !it.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(it.settings, isVideoPlaybackDocumentUrl(url), url) } == true,
                youtubePage = isYoutubeDocumentUrl(url)
            )
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = false)
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId] ?: return
            // FulgurisがYouTube/キャッシュ復帰で行うのと同じく、progress=100の最初の完了だけを
            // 採用する。重複したonPageFinishedでCSS注入・Cookie flush・履歴通知を繰り返さない。
            if (!entry.tryCompletePageLifecycle(view.progress)) {
                CrashDiagnostics.record("page_finished_skipped", "url=$url\\nprogress=${view.progress}")
                return
            }
            applyDeepDarkCss(
                view,
                enabled = !entry.fullscreenVideoDarkeningSuppressed &&
                    shouldApplyPageCssDarkening(entry.settings, isVideoPlaybackDocumentUrl(url), url),
                youtubePage = isYoutubeDocumentUrl(url)
            )
            applyBraveCosmeticFilters(view, url, entry.adBlockingEnabled, includeGeneric = true)
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
        var youtubeCosmeticAggressiveApplied: Boolean? = null,
        /** YouTube originを含むiframeへ登録するBrave scriptlet。 */
        var documentStartScriptHandler: ScriptHandler? = null,
        var documentStartScriptUrl: String? = null,
        /** 主文書のoriginにだけ登録する、指定2標準リスト由来のtrusted scriptlet。 */
        var siteDocumentStartScriptHandler: ScriptHandler? = null,
        var siteDocumentStartScriptUrl: String? = null,
        var youtubePictureInPictureScriptHandler: ScriptHandler? = null,
        var cookieFlushRunnable: Runnable? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        var appliedForceDark: Boolean? = null,
        var appliedForceDarkVideoPages: Boolean? = null,
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
        // Brave Android PR #28593と同じく、YouTubeのページ側PiP阻害フラグを最小限だけ無効化する。
        // config未生成・対応外構造ではno-opとし、複数回のSPA遷移でも追加の要素を作らない。
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
