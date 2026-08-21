package com.example.httpsbrowser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.HttpsBrowserTheme

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private lateinit var appRoot: FrameLayout
    @Volatile private var fullscreenVideoView: View? = null
    private var fullscreenContainer: FrameLayout? = null
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
        val composeView = ComposeView(this).apply {
            setContent {
                HttpsBrowserTheme {
                    BrowserScreen(viewModel(), externalUrl = incomingUrl)
                }
            }
        }
        appRoot.addView(composeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(appRoot)
    }

    /**
     * Chromium WebChromeClientが渡すfullscreen custom viewを、Activity root上の単一native containerへ
     * 接続する。通常WebViewはCompose側に残るためAwContentsの描画先を切り替えない。
     */
    fun showFullscreenCustomView(view: View) {
        if (::appRoot.isInitialized.not()) return
        if (fullscreenVideoView === view && fullscreenContainer != null) return
        hideFullscreenCustomView(fullscreenVideoView)

        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        container.addView(createPipButton(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 18, 18, 0) })
        appRoot.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenContainer = container
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
        runCatching { view?.keepScreenOn = false }
        setFullscreenVideoForPictureInPicture(null)
        setFullscreenSystemBars(false)
        CrashDiagnostics.record("fullscreen_native_container_hidden", "view=${view?.javaClass?.name.orEmpty()}")
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
        }
        return entered
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12以上でもauto-enterだけに依存しない。Fulgurisと同様に明示開始し、端末の
        // ジェスチャー実装差でPiPへ入らない経路をなくす。
        if (fullscreenVideoView != null && !isInPictureInPictureMode) {
            enterFullscreenPictureInPictureMode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) pictureInPictureTransitionRequested = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pictureInPictureActive = isInPictureInPictureMode
        pictureInPictureTransitionRequested = false
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

    private fun createPipButton(): TextView = TextView(this).apply {
        text = "PiP"
        contentDescription = "ピクチャーインピクチャーで再生"
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(16, 8, 16, 8)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(0xC20D1118.toInt())
            setStroke(1, 0x88FFFFFF.toInt())
        }
        setOnClickListener { enterFullscreenPictureInPictureMode() }
    }

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
                val ratio = bounds.width().toFloat() / bounds.height().toFloat()
                if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                    builder.setAspectRatio(Rational(bounds.width(), bounds.height()))
                }
            } else {
                builder.setAspectRatio(Rational(16, 9))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明示PiPの補助としてauto-enterも有効にする。通常ページではvideoViewがnullのため無効。
            builder.setAutoEnterEnabled(videoView != null)
            if (videoView != null) builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
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

    private companion object {
        const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f
        const val MAX_PIP_ASPECT_RATIO = 2.39f
    }
}
