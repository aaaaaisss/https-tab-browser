package com.example.httpsbrowser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.HttpsBrowserTheme

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    @Volatile private var fullscreenVideoView: View? = null
    private var pictureInPictureActive by mutableStateOf(false)
    @Volatile private var pictureInPictureTransitionRequested = false
    // custom viewはComposeのレイアウト、回転、PiP遷移で座標が変わる。
    // そのたびにauto-enter等の全パラメータを保ったsourceRectHintを更新する。
    private val pipHintLayoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === fullscreenVideoView && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            updatePictureInPictureParams(view)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        incomingUrl = httpsViewUrl(intent)
        setContent {
            HttpsBrowserTheme {
                BrowserScreen(viewModel(), externalUrl = incomingUrl)
            }
        }
    }

    /** WebChromeClientの全画面custom viewが存在する間だけPiPへの手動遷移を許可する。 */
    fun setFullscreenVideoForPictureInPicture(view: View?) {
        val previousView = fullscreenVideoView
        if (previousView !== view) previousView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = view
        if (previousView !== view) view?.addOnLayoutChangeListener(pipHintLayoutListener)
        if (view == null) pictureInPictureTransitionRequested = false
        // Viewがまだレイアウトされていない場合もあるため、現在値と将来のレイアウト変化の両方を扱う。
        updatePictureInPictureParams(view)
    }

    /** WebViewがPiPへの遷移でonHideCustomViewを送っても、動画Viewを外さないための判定。 */
    fun shouldRetainFullscreenCustomView(): Boolean =
        pictureInPictureActive || pictureInPictureTransitionRequested

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val videoView = fullscreenVideoView
        if (videoView == null || !supportsPictureInPicture() || isInPictureInPictureMode) return
        // Android 12以降はauto-enterがジェスチャー遷移を担当する。ここではWebViewが
        // onHideCustomViewを先に送った場合にcustom viewを保持する印だけを残す。
        pictureInPictureTransitionRequested = true
        CrashDiagnostics.record("pip_leave_hint", "fullscreenView=true\napi=${Build.VERSION.SDK_INT}\nauto=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val entered = runCatching { enterPictureInPictureMode(buildPictureInPictureParams(videoView)) }.getOrDefault(false)
        if (!entered) {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_enter_failed", "enterPictureInPictureMode=false")
        }
    }

    override fun onResume() {
        super.onResume()
        // PiPへ入らずにアプリへ戻った場合だけ、遷移中マーカーを解除する。
        if (!isInPictureInPictureMode) pictureInPictureTransitionRequested = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pictureInPictureActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_entered", "fullscreenView=${fullscreenVideoView != null}")
        } else {
            pictureInPictureTransitionRequested = false
            CrashDiagnostics.record("pip_exited", "fullscreenView=${fullscreenVideoView != null}")
        }
    }

    override fun onDestroy() {
        fullscreenVideoView?.removeOnLayoutChangeListener(pipHintLayoutListener)
        fullscreenVideoView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = httpsViewUrl(intent)
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
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12以降はauto-enterがスワイプ/ホームのPiP遷移を開始する。
            // fullscreen custom viewが存在する時だけ有効化し、通常ページではPiPにしない。
            builder.setAutoEnterEnabled(videoView != null)
            if (videoView != null) builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
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
