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
        configure(entry.webView, settings)
        if (entry.loadedUrl == null) {
            entry.loadedUrl = tab.lastRequestedUrl
            entry.activeDocumentUrl = tab.lastRequestedUrl
            CrashDiagnostics.recordWebViewNavigation(tab.lastRequestedUrl)
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

    private fun configure(view: WebView, settings: BrowserSettings) {
        view.settings.javaScriptEnabled = settings.javascriptEnabled
        configureDarkMode(view, settings.forceDarkPages)
    }

    /**
     * Android 13以上はAlgorithmic Darkeningが主方式だが、一部の更新途中WebViewでは
     * Force Dark featureも同時に公開される。従来のようにAlgorithmic対応時に
     * FORCE_DARK_OFFを明示すると、そのWebViewでは暗転経路を自ら無効化してしまう。
     *
     * CSS反転やページ本文の書換えは使わないため、動画・画像・iframeの色を直接壊さない。
     */
    @Suppress("DEPRECATION")
    private fun configureDarkMode(view: WebView, enabled: Boolean) {
        val webSettings = view.settings
        val hasAlgorithmicDarkening = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        if (hasAlgorithmicDarkening) {
            // targetSdk 33+での標準経路。WebViewが対応可能なページだけを安全に暗転する。
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, enabled)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // API 32以下および更新途中のWebView向けfallback。Algorithmic Darkeningを
            // 利用できる場合でもOFFを強制しない。
            WebSettingsCompat.setForceDark(
                webSettings,
                if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    webSettings,
                    WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                )
            }
        }

        // Android 10〜12のapp-level Force Dark fallbackを許可する。Android 13+では
        // 上記Algorithmic Darkeningが処理を担うため、ここは互換性設定としてのみ機能する。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.setForceDarkAllowed(true)
    }

    /**
     * Brave エンジンが返す hostname-specific selector と、実際に DOM に存在する class/id に
     * 対応する generic selector だけを注入する。例外規則は native engine が評価する。
     */
    private fun applyBraveCosmeticFilters(view: WebView, url: String, enabled: Boolean, includeGeneric: Boolean) {
        val entry = entries.entries.firstOrNull { it.value.webView === view }?.value ?: return
        if (!enabled || !blocker.isReady()) {
            if (entry.cosmeticAppliedUrl != null || entry.genericCosmeticAppliedUrl != null) {
                entry.cosmeticAppliedUrl = null
                entry.genericCosmeticAppliedUrl = null
                view.evaluateJavascript(
                    "(function(){document.getElementById('__https_browser_adblock_static')?.remove();document.getElementById('__https_browser_adblock_generic')?.remove();})();",
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
            if (entry.adBlockingEnabled && blocker.shouldBlock(
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
            entry?.activeDocumentUrl = url
            view.setBackgroundColor(android.graphics.Color.BLACK)
            entry?.let { configure(view, it.settings) }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val entry = entries[tabId]
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = false)
            super.onPageCommitVisible(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val entry = entries[tabId]
            applyBraveCosmeticFilters(view, url, entry?.adBlockingEnabled == true, includeGeneric = true)
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
        var cosmeticAppliedUrl: String? = null,
        var genericCosmeticAppliedUrl: String? = null,
        var callbacks: BrowserWebCallbacks = BrowserWebCallbacks.Empty,
        var settings: BrowserSettings = BrowserSettings(),
        @Volatile var activeDocumentUrl: String? = null,
        @Volatile var adBlockingEnabled: Boolean = true,
        @Volatile var isActive: Boolean = true
    )

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    private fun intentFallbackUrl(url: String): String? = runCatching {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.let(::upgradeToHttps)
    }.getOrNull()

    private fun resourceTypeFor(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val headers = request.requestHeaders
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value?.lowercase()
        return when (destination) {
            "script" -> "script"
            "style" -> "stylesheet"
            "image" -> "image"
            "font" -> "font"
            "audio", "video", "track" -> "media"
            "iframe", "frame" -> "subdocument"
            "empty" -> "xmlhttprequest"
            else -> when {
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
