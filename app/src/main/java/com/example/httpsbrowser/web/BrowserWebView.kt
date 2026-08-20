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
import androidx.webkit.ScriptHandler
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
        val darkModeChanged = entry.appliedForceDark != settings.forceDarkPages
        entry.settings = settings
        entry.adBlockingEnabled = settings.adBlockingEnabled
        entry.appliedForceDark = settings.forceDarkPages
        prepareDarkDocumentStartStyle(entry)
        configure(entry.webView, settings)
        // 最後に動作した構成と同様、暗色化設定の切替時だけ現在文書を一度読み直す。
        // 戻る・進む・タブ選択ではここに入らず、WebView履歴を維持する。
        if (darkModeChanged && entry.loadedUrl != null) entry.webView.reload()
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.activeDocumentUrl = tab.lastRequestedUrl
            CrashDiagnostics.recordWebViewNavigation(tab.lastRequestedUrl)
            prepareYoutubeDocumentStartScript(entry, tab.lastRequestedUrl)
            entry.webView.loadUrl(tab.lastRequestedUrl)
        }
        return entry.webView
    }

    fun load(tabId: String, url: String) {
        entries[tabId]?.let { entry ->
            if (isHttps(url)) {
                entry.loadedUrl = url
                // shouldInterceptRequest はUIスレッド外から呼ばれ得るため、
                // コールバック内で WebView.url を読む代わりに遷移前に親URLを保持する。
                entry.activeDocumentUrl = url
                CrashDiagnostics.recordWebViewNavigation(url)
                prepareYoutubeDocumentStartScript(entry, url)
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
        entries.remove(tabId)?.let { entry ->
            entry.isActive = false
            runCatching { entry.documentStartScriptHandler?.remove() }
            runCatching { entry.darkDocumentStartHandler?.remove() }
            entry.documentStartScriptHandler = null
            entry.darkDocumentStartHandler = null
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

    private fun configure(view: WebView, settings: BrowserSettings) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        configureDarkMode(view, settings.forceDarkPages)
    }

    /**
     * 公式の単一路径にする。Algorithmic Darkening対応WebViewでは旧Force Darkを併用せず、
     * 未対応の古いWebViewだけでForce Darkをfallbackにする。画像・動画をCSS反転しない。
     */
    @Suppress("DEPRECATION")
    private fun configureDarkMode(view: WebView, enabled: Boolean) {
        val webSettings = view.settings
        val hasAlgorithmicDarkening = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        val hasForceDark = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
        val hasForceDarkStrategy = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
        if (hasAlgorithmicDarkening) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, enabled)
            if (hasForceDark) WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_OFF)
        } else if (hasForceDark) {
            WebSettingsCompat.setForceDark(
                webSettings,
                if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
            if (hasForceDarkStrategy) {
                WebSettingsCompat.setForceDarkStrategy(
                    webSettings,
                    WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY
                )
            }
        }
        // Algorithmic Darkening利用時はapp-level Force Darkを重ねない。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.setForceDarkAllowed(!hasAlgorithmicDarkening && enabled)
        CrashDiagnostics.record(
            "dark_mode_configured",
            "enabled=$enabled\napi=${Build.VERSION.SDK_INT}\nalgorithmic=$hasAlgorithmicDarkening\nforceDark=$hasForceDark\nforceDarkStrategy=$hasForceDarkStrategy\nmode=${if (hasAlgorithmicDarkening) "algorithmic" else "force_dark_fallback"}"
        )
    }

    /** 初期白フラッシュを防ぐため、HTTPS文書の開始時から背景・color-schemeだけを暗色にする。 */
    private fun prepareDarkDocumentStartStyle(entry: Entry) {
        val enabled = entry.settings.forceDarkPages
        if (entry.darkDocumentStartEnabled == enabled) return
        runCatching { entry.darkDocumentStartHandler?.remove() }
        entry.darkDocumentStartHandler = null
        entry.darkDocumentStartEnabled = enabled
        if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(entry.webView, DARK_BASE_STYLE_SCRIPT, setOf("https://*"))
        }.onSuccess { handler ->
            entry.darkDocumentStartHandler = handler
        }.onFailure { throwable ->
            CrashDiagnostics.record("dark_document_start_unavailable", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
        }
    }

    /** Document Start非対応時にも安全に背景を補正する。反転・画像/動画へのfilterは一切使わない。 */
    private fun applyDarkBaseStyle(view: WebView, enabled: Boolean) {
        view.evaluateJavascript(if (enabled) DARK_BASE_STYLE_SCRIPT else REMOVE_DARK_BASE_STYLE_SCRIPT, null)
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
        val host = youtubeHost(url) ?: return
        val originRules = setOf(
            "https://youtube.com", "https://*.youtube.com",
            "https://youtube-nocookie.com", "https://*.youtube-nocookie.com"
        )
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(entry.webView, script, originRules)
        }.onSuccess { handler ->
            entry.documentStartScriptHandler = handler
            entry.documentStartScriptUrl = url
            CrashDiagnostics.record("adblock_youtube_scriptlet_prepared", "scriptlet=true\nparentHost=$host\nchars=${script.length}")
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

    /** YouTubeのプレーヤー本体・サイズ計算へ触れず、明示的な広告枠だけを非表示にする。 */
    private fun applyYoutubeCosmeticFilters(view: WebView, entry: Entry, url: String, enabled: Boolean) {
        if (entry.youtubeCosmeticAppliedUrl == url && enabled) return
        entry.youtubeCosmeticAppliedUrl = if (enabled) url else null
        // 同一ドキュメントで通常サイトからYouTubeへSPA遷移した場合にも、汎用CSSを残さない。
        entry.cosmeticAppliedUrl = null
        entry.genericCosmeticAppliedUrl = null
        // YouTubeの動的selectorはWeb Componentsのレイアウトへ波及し得るため使わない。
        // 明示的な広告slotだけを対象にし、プレーヤー・幅計算・gridには一切触れない。
        val css = if (enabled) YOUTUBE_AD_CSS else ""
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
            // Brave エンジンが ABP/AdGuard の例外、第三者判定、resource type を評価する。
            // 独自の YouTube 除外や簡易 URL 判定は行わず、正規のフィルタ規則をそのまま尊重する。
            // shouldInterceptRequest はUIスレッド外から呼ばれ得る。ここで WebView.url など
            // View の状態には触れず、UIスレッドで保持した親ページURLだけを利用する。
            val documentUrl = entry.activeDocumentUrl.orEmpty().ifBlank { url }
            // YouTube/Googlevideo/ytimgのiframe bootstrap、player JS、映像chunk、内部APIは
            // 再生必須として保護する。明示的な広告・計測専用ホスト/パスだけは規則評価を継続する。
            val shouldCheck = !isYoutubePlaybackResource(url) || isYoutubeAdOrTrackingNetwork(url)
            if (entry.adBlockingEnabled && shouldCheck && blocker.shouldBlock(
                    url = url,
                    documentUrl = documentUrl,
                    resourceType = resourceTypeFor(request)
                )
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
            entry?.cosmeticAppliedUrl = null
            entry?.genericCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAppliedUrl = null
            entry?.activeDocumentUrl = url
            view.setBackgroundColor(android.graphics.Color.BLACK)
            entry?.let { configure(view, it.settings) }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            applyDarkBaseStyle(view, entry?.settings?.forceDarkPages == true)
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = false)
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId]
            applyDarkBaseStyle(view, entry?.settings?.forceDarkPages == true)
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = true)
            if (isYoutubeDocumentUrl(url)) recordYoutubeViewportMetrics(view, url)
            CookieManager.getInstance().flush()
            entry?.callbacks?.onPageFinished(tabId, url, view.title)
            entries[tabId]?.callbacks?.onHistoryState(tabId, view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel() // 証明書エラーを無視して接続することは絶対にしない。
            entries[tabId]?.callbacks?.onSslError(error.url)
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // 同じURLを即時に再生成すると、壊れたページ・メモリ不足でレンダラーが再度落ちる無限ループになる。
            // 既に描画プロセスを失ったWebViewには loadUrl/clearHistory/stopLoading を実行せず、destroyだけを行う。
            val entry = entries.remove(tabId)
            entry?.isActive = false
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
            configure(popupView, current.settings)
            // 新規ウィンドウの WebView を、そのまま新しいタブへ接続する。
            // 空文字を loadedUrl に入れると Compose 再構成時に読み込み状態が不整合になるため null を維持する。
            entries[newTabId] = Entry(
                webView = popupView,
                callbacks = current.callbacks,
                settings = current.settings
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
        var documentStartScriptHandler: ScriptHandler? = null,
        var documentStartScriptUrl: String? = null,
        var darkDocumentStartHandler: ScriptHandler? = null,
        var darkDocumentStartEnabled: Boolean = false,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        var appliedForceDark: Boolean? = null,
        @Volatile var activeDocumentUrl: String? = null,
        @Volatile var adBlockingEnabled: Boolean = true,
        @Volatile var isActive: Boolean = true
    )

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

    private fun recordYoutubeViewportMetrics(view: WebView, url: String) {
        view.evaluateJavascript(YOUTUBE_VIEWPORT_METRICS_SCRIPT) { raw ->
            val metrics = runCatching { JSONTokener(raw ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
            if (metrics.isNotBlank()) CrashDiagnostics.record("youtube_viewport_metrics", "url=$url\n$metrics")
        }
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
        const val MAX_STATIC_COSMETIC_SELECTORS = 500
        const val GENERIC_COSMETIC_DELAY_MS = 350L
        const val YOUTUBE_SCRIPTLET_DOCUMENT_URL = "https://www.youtube.com/"
        val DARK_BASE_STYLE_SCRIPT = """
            (function(){
              var id='__https_browser_dark_base';
              var style=document.getElementById(id);
              if(!style){style=document.createElement('style');style.id=id;(document.head||document.documentElement).appendChild(style);}
              style.textContent=':root{color-scheme:dark!important;background-color:#000!important}html,body{background-color:#000!important;}';
            })();
        """.trimIndent()
        val REMOVE_DARK_BASE_STYLE_SCRIPT = """
            (function(){document.getElementById('__https_browser_dark_base')?.remove();document.getElementById('__https_browser_deep_dark')?.remove();})();
        """.trimIndent()
        // #player、video、ytm-player、レイアウトコンテナは意図的に含めない。
        val YOUTUBE_AD_CSS = """
            ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-promoted-video-renderer,
            ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,
            ytd-action-companion-ad-renderer,ytm-ad-slot-renderer,
            ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,
            #player-ads,.ytp-ad-overlay-container,.ytp-ad-module {
              display:none!important;visibility:hidden!important;
            }
        """.trimIndent()
        // 左余白がCompose/WebView/YouTube文書のどこで発生しているかを実寸で診断する。
        val YOUTUBE_VIEWPORT_METRICS_SCRIPT = """
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
