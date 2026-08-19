package com.example.httpsbrowser

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
    private var pictureInPictureEligible = false

    /** 全画面 WebView 動画が見えている間だけ、ホーム操作時の PiP 移行を試行する。 */
    fun setPictureInPictureEligible(eligible: Boolean) {
        pictureInPictureEligible = eligible
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pictureInPictureEligible && !isInPictureInPictureMode) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = httpsViewUrl(intent)
    }

    private fun httpsViewUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
    }
}
