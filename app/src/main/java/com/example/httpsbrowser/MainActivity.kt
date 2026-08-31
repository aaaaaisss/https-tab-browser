package com.example.httpsbrowser

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.HttpsBrowserTheme
import com.example.httpsbrowser.web.BrowserWebViewRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private lateinit var appRoot: FrameLayout
    private lateinit var normalWebContentHost: FrameLayout
    private lateinit var composeOverlayView: ComposeView
    private var normalWebContentBoundsReady = false
    private var normalWebContentReservesRightTouchRail = false
    private var normalWebContentPlacedAboveCompose = false
    private var normalWebContentTabId: String? = null
    private var forwardingNormalWebTouch = false

    @Volatile private var fullscreenVideoView: View? = null
    private var fullscreenVideoTabId: String? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenPipButton: TextView? = null
    private var pictureInPictureActive by mutableStateOf(false)
    @Volatile private var pictureInPictureTransitionRequested = false
    private val videoDimensionsByTab = ConcurrentHashMap<String, VideoDimensions>()

    // custom viewはレイアウト・回転・PiP遷移で座標が変わるため、sourceRectHintを更新する。
    private val pipHintLayoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === fullscreenVideoView && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            updatePictureInPictureParams(view)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        incomingUrl = httpsViewUrl(intent)

        appRoot = FrameLayout(this)
        normalWebContentHost = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
        }
        normalWebContentHost.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        composeOverlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HttpsBrowserTheme {
                    BrowserScreen(viewModel(), externalUrl = incomingUrl)
                }
            }
            // WebViewはComposeの下に置く場合があるため、ページ領域のタッチだけを転送する。
            setOnTouchListener { _, event -> forwardPageTouchToNativeWebView(event) }
        }

        appRoot.addView(normalWebContentHost, FrameLayout.LayoutParams(-1, -1))
        appRoot.addView(composeOverlayView, FrameLayout.LayoutParams(-1, -1))
        setContentView(appRoot)
    }

    /** Composeの透明オーバーレイ越しに、native host内のWebViewへページ操作を転送する。 */
    private fun forwardPageTouchToNativeWebView(event: MotionEvent): Boolean {
        if (normalWebContentHost.visibility != View.VISIBLE) return false
        val railWidth = if (normalWebContentReservesRightTouchRail) {
            (40f * resources.displayMetrics.density).roundToInt()
        } else 0
        val x = event.x
        val y = event.y
        val withinHost = x >= normalWebContentHost.left && x < normalWebContentHost.right &&
            y >= normalWebContentHost.top && y < normalWebContentHost.bottom
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            forwardingNormalWebTouch = withinHost && x < normalWebContentHost.right - railWidth
        }
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

    /** 選択中タブのWebViewをComposeのレイアウトに追従するnative hostへ接続する。 */
    fun showNormalWebContent(registry: BrowserWebViewRegistry, tabId: String) {
        normalWebContentTabId = tabId
        registry.attachToNativeHost(tabId, normalWebContentHost)
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
        normalWebContentHost.bringToFrontIfNeeded()
        CrashDiagnostics.record("normal_webview_native_host_shown", "tab=$tabId")
    }

    fun setNormalWebContentVisible(visible: Boolean) {
        if (!hasAttachedNormalWebContent()) {
            normalWebContentHost.visibility = View.GONE
            return
        }
        if (visible) normalWebContentHost.bringToFrontIfNeeded() else composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
    }

    fun hideNormalWebContent() {
        composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (hasAttachedNormalWebContent() && normalWebContentBoundsReady) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** Compose上のページ領域をActivity直下のWebView hostへ反映する。 */
    fun setNormalWebContentBounds(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        reserveRightTouchRail: Boolean,
        placeAboveCompose: Boolean
    ) {
        if (width <= 0 || height <= 0) return
        normalWebContentReservesRightTouchRail = reserveRightTouchRail
        normalWebContentPlacedAboveCompose = placeAboveCompose
        normalWebContentBoundsReady = true
        if (placeAboveCompose) normalWebContentHost.bringToFront() else composeOverlayView.bringToFront()

        val current = normalWebContentHost.layoutParams as? FrameLayout.LayoutParams ?: return
        if (current.leftMargin == left && current.topMargin == top &&
            current.width == width && current.height == height
        ) return
        current.leftMargin = left
        current.topMargin = top
        current.width = width
        current.height = height
        normalWebContentHost.layoutParams = current
        if (hasAttachedNormalWebContent()) normalWebContentHost.visibility = View.VISIBLE
    }

    /** WebChromeClientの全画面custom viewをActivity直下へ移し、Compose再構成から分離する。 */
    fun showFullscreenCustomView(view: View, tabId: String?) {
        if (fullscreenVideoView === view && fullscreenContainer != null) return
        hideFullscreenCustomView(fullscreenVideoView)
        normalWebContentHost.visibility = View.INVISIBLE

        val container = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))

        val pipButton = createPipButton()
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(pipButton)
        }
        val controlsParams = FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START)
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        controlsParams.setMargins(
            (insets?.left ?: 0) + dp(18),
            (insets?.top ?: 0) + dp(18),
            0,
            0
        )
        container.addView(controls, controlsParams)
        appRoot.addView(container, FrameLayout.LayoutParams(-1, -1))

        fullscreenContainer = container
        fullscreenPipButton = pipButton
        fullscreenVideoTabId = tabId
        setFullscreenVideoForPictureInPicture(view)
        view.keepScreenOn = true
        setFullscreenSystemBars(true)
        CrashDiagnostics.record("fullscreen_native_container_shown", "view=${view.javaClass.name}")
    }

    fun hideFullscreenCustomView(expectedView: View? = null) {
        val currentView = fullscreenVideoView
        if (expectedView != null && currentView !== expectedView) return
        fullscreenContainer?.let { container ->
            appRoot.removeView(container)
            container.removeAllViews()
        }
        fullscreenContainer = null
        fullscreenPipButton = null
        fullscreenVideoTabId = null
        currentView?.keepScreenOn = false
        setFullscreenVideoForPictureInPicture(null)
        if (!isInPictureInPictureMode) composeOverlayView.visibility = View.VISIBLE
        if (fullscreenVideoView == null) normalWebContentHost.visibility = View.VISIBLE
        setFullscreenSystemBars(false)
        CrashDiagnostics.record("fullscreen_native_container_hidden", "view=${currentView?.javaClass?.name.orEmpty()}")
    }

    /** 動画の実寸を使ってPiPの比率を安定させる。WebView/JavascriptInterfaceから呼ばれる。 */
    fun updatePictureInPictureVideoDimensions(tabId: String, width: Int, height: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updatePictureInPictureVideoDimensions(tabId, width, height) }
            return
        }
        if (tabId.isBlank() || width <= 0 || height <= 0) return
        val dimensions = VideoDimensions(width, height)
        if (videoDimensionsByTab.put(tabId, dimensions) != dimensions && fullscreenVideoTabId == tabId) {
            updatePictureInPictureParams(fullscreenVideoView)
        }
    }

    /** WebViewがPiP遷移中にonHideCustomViewを先行通知しても、映像Viewを残す。 */
    fun setFullscreenVideoForPictureInPicture(view: View?) {
        val previousView = fullscreenVideoView
        if (previousView !== view) previousView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = view
        if (previousView !== view) view?.addOnLayoutChangeListener(pipHintLayoutListener)
        if (view == null) pictureInPictureTransitionRequested = false
        updatePictureInPictureParams(view)
    }

    fun shouldRetainFullscreenCustomView(): Boolean =
        pictureInPictureActive || pictureInPictureTransitionRequested

    /** 明示的なPiPボタンから呼ぶ。Android 12以降でもauto-enterに依存せず開始する。 */
    fun enterFullscreenPictureInPictureMode(): Boolean {
        val videoView = fullscreenVideoView
        if (videoView == null || !supportsPictureInPicture() || isInPictureInPictureMode || pictureInPictureTransitionRequested) {
            return false
        }
        pictureInPictureTransitionRequested = true
        val entered = runCatching {
            enterPictureInPictureMode(buildPictureInPictureParams(videoView))
        }.getOrDefault(false)
        if (!entered) {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_enter_failed", "enterPictureInPictureMode=false")
        } else {
            CrashDiagnostics.record("pip_enter_requested", "source=explicit\napi=${Build.VERSION.SDK_INT}")
        }
        return entered
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val videoView = fullscreenVideoView
        if (videoView == null || !supportsPictureInPicture() || isInPictureInPictureMode) return
        // Android 12以降は設定済みauto-enterを使うが、onHideCustomViewの順序逆転に備えて保持印を残す。
        pictureInPictureTransitionRequested = true
        CrashDiagnostics.record("pip_leave_hint", "fullscreenView=true\napi=${Build.VERSION.SDK_INT}\nauto=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val entered = runCatching { enterPictureInPictureMode(buildPictureInPictureParams(videoView)) }.getOrDefault(false)
            if (!entered) {
                pictureInPictureTransitionRequested = false
                CrashDiagnostics.record("pip_enter_failed", "enterPictureInPictureMode=false")
            }
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
        fullscreenPipButton?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            composeOverlayView.visibility = View.GONE
        } else if (fullscreenContainer == null) {
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
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = httpsViewUrl(intent)
    }

    private fun hasAttachedNormalWebContent(): Boolean = normalWebContentHost.childCount > 0

    private fun FrameLayout.bringToFrontIfNeeded() {
        if (normalWebContentPlacedAboveCompose) bringToFront() else composeOverlayView.bringToFront()
    }

    private fun createPipButton(): TextView = TextView(this).apply {
        text = "PiP"
        contentDescription = "ピクチャーインピクチャーで再生"
        setTextColor(android.graphics.Color.WHITE)
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(0xC2000000.toInt())
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }
        setOnClickListener { enterFullscreenPictureInPictureMode() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun supportsPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updatePictureInPictureParams(videoView: View?) {
        if (!supportsPictureInPicture()) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(videoView)) }
    }

    private fun buildPictureInPictureParams(videoView: View?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoView != null) {
            val bounds = Rect()
            if (videoView.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) {
                builder.setSourceRectHint(bounds)
                val dimensions = fullscreenVideoTabId?.let(videoDimensionsByTab::get)
                val width = dimensions?.width ?: bounds.width()
                val height = dimensions?.height ?: bounds.height()
                val ratio = width.toFloat() / height.toFloat()
                if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                    builder.setAspectRatio(Rational(width, height))
                }
            } else {
                builder.setAspectRatio(Rational(16, 9))
            }
            builder.setActions(listOf(createOpenBrowserRemoteAction()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(videoView != null)
            if (videoView != null) builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun createOpenBrowserRemoteAction(): RemoteAction {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_BROWSER_FROM_PIP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options
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
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
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
        const val REQUEST_OPEN_BROWSER_FROM_PIP = 4021
        const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f
        const val MAX_PIP_ASPECT_RATIO = 2.39f
    }
}
