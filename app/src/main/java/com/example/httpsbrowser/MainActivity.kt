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

    /** WebChromeClientの全画面custom viewが存在する間だけPiPの自動移行を許可する。 */
    fun setFullscreenVideoForPictureInPicture(view: View?) {
        fullscreenVideoView = view
        updatePictureInPictureParams(view)
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 26〜30ではauto-enterがないため、全画面動画中だけ明示的にPiPへ入る。
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            fullscreenVideoView != null &&
            supportsPictureInPicture() &&
            !isInPictureInPictureMode
        ) {
            runCatching { enterPictureInPictureMode(buildPictureInPictureParams(fullscreenVideoView)) }
        }
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
                // Shortsを含め、AndroidのPiP対応範囲内の動画縦横比だけを渡す。
                val ratio = bounds.width().toFloat() / bounds.height().toFloat()
                if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                    builder.setAspectRatio(Rational(bounds.width(), bounds.height()))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 全画面custom viewがある間だけホームジェスチャー／ホームボタンでPiPへ移行する。
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
