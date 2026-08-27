package com.example.httpsbrowser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.ConcurrentHashMap
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.HttpsBrowserTheme
import com.example.httpsbrowser.web.BrowserWebViewRegistry

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private lateinit var appRoot: FrameLayout
    /** 通常ページをComposeのAndroidViewから分離して保持する、選択タブ専用のnative host。 */
    private lateinit var normalWebContentHost: FrameLayout
    private lateinit var composeOverlayView: ComposeView
    private var normalWebContentBoundsReady = false
    private var forwardingNormalWebTouch = false
    /** Composeの右端スクロールレールが見える通常ページだけ、レール用のタッチ領域を予約する。 */
    private var normalWebContentReservesRightTouchRail = false
    /** Googleのページ内モーダルなど、通常WebViewをComposeより前面に置くページかを保持する。 */
    private var normalWebContentPlacedAboveCompose = false
    @Volatile private var fullscreenVideoView: View? = null
    private var fullscreenVideoTabId: String? = null
    private val videoDimensionsByTab = ConcurrentHashMap<String, VideoDimensions>()
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenPipButton: View? = null
    private var pictureInPictureActive by mutableStateOf(false)
    @Volatile private var pictureInPictureTransitionRequested = false

    // custom viewは全画面・PiP遷移で座標が変わる。sourceRectHintを追従させる。
    private val pipHintLayoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === fullscreenVideoView && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            updatePictureInPictureParams(view)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        incomingUrl = httpsViewUrl(intent)

        // custom viewはComposeのAndroidViewに重ねず、Fulgurisと同じくActivityのnative rootへ追加する。
        // これにより動画surfaceの親・測定サイズがCompose再構成で変わらない。
        appRoot = FrameLayout(this)
        normalWebContentHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
        }
        composeOverlayView = ComposeView(this).apply {
            setContent {
                HttpsBrowserTheme {
                    BrowserScreen(viewModel(), externalUrl = incomingUrl)
                }
            }
        }
        // 通常ページhostを最下層、Composeを常にその上に置く。ページ領域以外のComposeは透明なので
        // WebViewの描画・タップを妨げず、下部バー、ホーム、各シートは常に最前面で操作できる。
        appRoot.addView(normalWebContentHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        appRoot.addView(composeOverlayView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Composeは操作UIを前面表示するが、ページ領域の連続タッチだけはnative WebViewへ転送する。
        // これにより下部バー・ホーム・検索候補は覆われず、スクロール・ピンチ操作も維持する。
        composeOverlayView.setOnTouchListener { _, event -> forwardPageTouchToNativeWebView(event) }
        setContentView(appRoot)
    }

    /**
     * Compose rootが最前面でも、ページ矩形内で始まった連続タッチをnative WebViewへ転送する。
     * 右端レール用の細い領域はComposeへ残し、通常ページ上のスクロール・ピンチを阻害しない。
     */
    private fun forwardPageTouchToNativeWebView(event: MotionEvent): Boolean {
        if (::normalWebContentHost.isInitialized.not() || normalWebContentHost.visibility != View.VISIBLE) return false
        val railWidth = if (normalWebContentReservesRightTouchRail) {
            (40 * resources.displayMetrics.density).toInt()
        } else 0
        val withinHost = event.x >= normalWebContentHost.left && event.x < normalWebContentHost.right &&
            event.y >= normalWebContentHost.top && event.y < normalWebContentHost.bottom
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forwardingNormalWebTouch = withinHost && event.x < normalWebContentHost.right - railWidth
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        // GoogleページではhostをComposeより前面に置くため、ここへ届く場合は中継しない。
        if (!forwardingNormalWebTouch) return false
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation(-normalWebContentHost.left.toFloat(), -normalWebContentHost.top.toFloat())
        normalWebContentHost.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwardingNormalWebTouch = false
        }
        return true
    }

    /** 選択タブの通常WebViewをnative hostへ接続し、Composeの再構成から親Viewを分離する。 */
    fun showNormalWebContent(registry: BrowserWebViewRegistry, tabId: String) {
        if (::normalWebContentHost.isInitialized.not()) return
        registry.attachToNativeHost(tabId, normalWebContentHost)
        // Composeからページ矩形を受けるまで全画面の仮LayoutParamsを見せない。
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
        CrashDiagnostics.record("normal_webview_native_host_shown", "tab=$tabId")
    }

    /**
     * Composeのシートやダイアログを前面にする。WebView自体は不可視化・切離しせず、
     * Composeの下で表示を維持することで動画・音声の描画サーフェスと再生sessionを保つ。
     */
    fun setNormalWebContentVisible(visible: Boolean) {
        if (::normalWebContentHost.isInitialized.not()) return
        if (normalWebContentHost.childCount == 0) {
            normalWebContentHost.visibility = View.GONE
            return
        }
        if (visible && normalWebContentPlacedAboveCompose) normalWebContentHost.bringToFront()
        else composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
    }

    /**
     * ホームタブでも既存WebViewをhostから外さない。通常のタブ切替で親Viewを外すと、
     * Chromiumが動画の描画サーフェスを破棄し再生を停止するため、Composeホームを前面に
     * 重ねて背後のWebViewセッションを保持する。明示的なホーム復帰・タブ削除・Activity
     * 破棄だけがWebViewを破棄する経路となる。
     */
    fun hideNormalWebContent() {
        if (::normalWebContentHost.isInitialized.not()) return
        composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentHost.childCount > 0 && normalWebContentBoundsReady) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** Composeが計測したページ矩形だけを通常WebViewへ割り当て、下部操作UIと重ねない。 */
    fun setNormalWebContentBounds(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        reserveRightTouchRail: Boolean,
        placeAboveCompose: Boolean
    ) {
        if (::normalWebContentHost.isInitialized.not() || width <= 0 || height <= 0) return
        // Page boxはComposeがstatus bar下から下部バー上まで測定した値をそのまま使う。
        // Google系では右端予約を外し、重ねる型Webポップアップの全領域をWebViewへ渡す。
        normalWebContentReservesRightTouchRail = reserveRightTouchRail
        normalWebContentPlacedAboveCompose = placeAboveCompose
        // Googleのページ内モーダルはComposeのタッチ中継を経由させず、ページ矩形だけnative WebViewを前面にする。
        // Host自体は下部バーより上の高さへclip済みのため、下部バー・シートは覆わない。
        if (placeAboveCompose) normalWebContentHost.bringToFront() else composeOverlayView.bringToFront()
        val current = normalWebContentHost.layoutParams as? FrameLayout.LayoutParams ?: return
        normalWebContentBoundsReady = true
        if (current.leftMargin == left && current.topMargin == top && current.width == width && current.height == height) return
        current.leftMargin = left
        current.topMargin = top
        current.width = width
        current.height = height
        normalWebContentHost.layoutParams = current
        if (normalWebContentHost.childCount > 0) {
            // レイアウト更新時もhostを不可視化しない。IME・シート・タブ切替で
            // WebViewのサーフェスが破棄されると動画再生が止まる端末がある。
            normalWebContentHost.visibility = View.VISIBLE
        }
    }

    /**
     * Chromium WebChromeClientが渡すfullscreen custom viewを、Activity root上の単一native containerへ
     * 接続する。通常WebViewはCompose側に残るためAwContentsの描画先を切り替えない。
     */
    fun showFullscreenCustomView(view: View, tabId: String?) {
        if (::appRoot.isInitialized.not()) return
        if (fullscreenVideoView === view && fullscreenContainer != null) return
        hideFullscreenCustomView(fullscreenVideoView)

        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val pipButton = createPipButton()
        container.addView(pipButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(18, 18, 0, 0) })
        fullscreenPipButton = pipButton
        appRoot.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenContainer = container
        fullscreenVideoTabId = tabId
        setFullscreenVideoForPictureInPicture(view)
        runCatching { view.keepScreenOn = true }
        setFullscreenSystemBars(true)
        CrashDiagnostics.record("fullscreen_native_container_shown", "view=${view.javaClass.name}")
    }

    /** native fullscreen containerを一度だけ除去し、通常画面とPiP設定を復帰する。 */
    fun hideFullscreenCustomView(expectedView: View? = null) {
        val view = fullscreenVideoView
        if (expectedView != null && view !== expectedView) return
        fullscreenContainer?.let { container ->
            appRoot.removeView(container)
            container.removeAllViews()
        }
        fullscreenContainer = null
        fullscreenPipButton = null
        fullscreenVideoTabId = null
        // PiPまたは全画面終了後にだけ通常のCompose操作UIを戻す。
        if (::composeOverlayView.isInitialized) composeOverlayView.visibility = View.VISIBLE
        runCatching { view?.keepScreenOn = false }
        setFullscreenVideoForPictureInPicture(null)
        setFullscreenSystemBars(false)
        CrashDiagnostics.record("fullscreen_native_container_hidden", "view=${view?.javaClass?.name.orEmpty()}")
    }

    /**
     * WebViewが取得した実映像サイズをタブごとに記録し、全画面中のタブならPiP設定を即時更新する。
     * 画面回転ではなくvideoWidth/videoHeightを使うため、縦持ち中の横動画も横長PiPになる。
     */
    fun updatePictureInPictureVideoDimensions(tabId: String, width: Int, height: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updatePictureInPictureVideoDimensions(tabId, width, height) }
            return
        }
        if (tabId.isBlank() || width <= 0 || height <= 0) return
        val dimensions = VideoDimensions(width, height)
        if (videoDimensionsByTab.put(tabId, dimensions) == dimensions) return
        if (fullscreenVideoTabId == tabId) updatePictureInPictureParams(fullscreenVideoView)
    }

    /** 全画面custom viewが存在する間だけPiPへ移行できる。 */
    fun setFullscreenVideoForPictureInPicture(view: View?) {
        val previousView = fullscreenVideoView
        if (previousView !== view) previousView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = view
        if (previousView !== view) view?.addOnLayoutChangeListener(pipHintLayoutListener)
        if (view == null) pictureInPictureTransitionRequested = false
        updatePictureInPictureParams(view)
    }

    /** WebViewがPiP開始に伴いonHideCustomViewを先に送っても、動画Viewを外さないための判定。 */
    fun shouldRetainFullscreenCustomView(): Boolean =
        pictureInPictureActive || pictureInPictureTransitionRequested

    /** 全画面中だけ使う明示PiP操作。API 26以上ではauto-enterへ依存せず直接開始する。 */
    fun enterFullscreenPictureInPictureMode(): Boolean {
        val videoView = fullscreenVideoView
        if (videoView == null || !supportsPictureInPicture() || isInPictureInPictureMode || pictureInPictureTransitionRequested) return false
        pictureInPictureTransitionRequested = true
        val entered = runCatching {
            enterPictureInPictureMode(buildPictureInPictureParams(videoView))
        }.getOrDefault(false)
        if (!entered) {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_enter_failed", "enterPictureInPictureMode=false")
        } else {
            CrashDiagnostics.record("pip_enter_requested", "source=explicit\napi=${Build.VERSION.SDK_INT}")
            // 端末側が状態callbackを返さない場合でも、PiPボタンを再試行不能なまま残さない。
            appRoot.postDelayed({
                if (!isInPictureInPictureMode && pictureInPictureTransitionRequested) {
                    pictureInPictureTransitionRequested = false
                    CrashDiagnostics.record("pip_enter_timeout", "callback_missing=true")
                }
            }, PIP_TRANSITION_TIMEOUT_MS)
        }
        return entered
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12以降は、事前に設定したauto-enterがジェスチャーPiPをより滑らかに開始する。
        // ここで明示enterを重ねると、WebView custom viewの停止・再親子化と競合し黒画面化し得る。
        // API 26〜30だけ従来の明示経路を使う。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && fullscreenVideoView != null && !isInPictureInPictureMode) {
            enterFullscreenPictureInPictureMode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) pictureInPictureTransitionRequested = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureActive = isInPictureInPictureMode
        pictureInPictureTransitionRequested = false
        // PiP windowには動画だけを残す。操作ボタンやCompose下部バーはPiP中に合成しない。
        fullscreenPipButton?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            if (::composeOverlayView.isInitialized) composeOverlayView.visibility = View.GONE
        } else if (fullscreenContainer == null && ::composeOverlayView.isInitialized) {
            composeOverlayView.visibility = View.VISIBLE
        }
        CrashDiagnostics.record(
            if (isInPictureInPictureMode) "pip_entered" else "pip_exited",
            "fullscreenView=${fullscreenVideoView != null}"
        )
    }

    override fun onDestroy() {
        fullscreenVideoView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = null
        fullscreenContainer = null
        if (::normalWebContentHost.isInitialized) normalWebContentHost.removeAllViews()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = httpsViewUrl(intent)
    }

    private fun createPipButton(): TextView = TextView(this).apply {
        text = "PiP"
        contentDescription = "ピクチャーインピクチャーで再生"
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(0xC20D1118.toInt())
            setStroke(1, 0x88FFFFFF.toInt())
        }
        setOnClickListener {
            if (!enterFullscreenPictureInPictureMode()) {
                Toast.makeText(this@MainActivity, "この動画ではPiPを開始できませんでした。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun supportsPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updatePictureInPictureParams(videoView: View?) {
        if (!supportsPictureInPicture()) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(videoView)) }
    }

    private fun buildPictureInPictureParams(videoView: View?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        videoView?.let { view ->
            val bounds = Rect()
            if (view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) {
                builder.setSourceRectHint(bounds)
                val dimensions = fullscreenVideoTabId?.let(videoDimensionsByTab::get)
                val aspectWidth = dimensions?.width ?: bounds.width()
                val aspectHeight = dimensions?.height ?: bounds.height()
                val ratio = aspectWidth.toFloat() / aspectHeight.toFloat()
                if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                    builder.setAspectRatio(Rational(aspectWidth, aspectHeight))
                }
            } else {
                builder.setAspectRatio(Rational(16, 9))
            }
        }
        if (videoView != null) {
            // 起動時・通常ブラウズ時のActivity構成には触れない。PiP操作を押した時だけ、
            // 再生surfaceを所有する本Activityを動かさず、同じ安定済みMainActivityを別taskで開く。
            builder.setActions(listOf(createOpenBrowserRemoteAction()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明示PiPの補助としてauto-enterも有効にする。通常ページではvideoViewがnullのため無効。
            builder.setAutoEnterEnabled(videoView != null)
            if (videoView != null) builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** PiP動画のWebView/custom viewを再親子化せず、通常ブラウズ用の既存Activityだけを別taskで開く。 */
    private fun createOpenBrowserRemoteAction(): RemoteAction {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_BROWSER_FROM_PIP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_browser),
            "ブラウズを開く",
            "PiP再生を続けたままブラウザを操作",
            pendingIntent
        )
    }

    private fun setFullscreenSystemBars(fullscreen: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            // ホームジェスチャーを最優先するためnavigation barは隠さず、status barだけを制御する。
            if (fullscreen) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun httpsViewUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
    }

    private data class VideoDimensions(val width: Int, val height: Int)

    private companion object {
        const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f
        const val MAX_PIP_ASPECT_RATIO = 2.39f
        const val REQUEST_OPEN_BROWSER_FROM_PIP = 4_021
        const val PIP_TRANSITION_TIMEOUT_MS = 2_000L
    }
}
