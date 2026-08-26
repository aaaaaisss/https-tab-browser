package com.example.httpsbrowser

import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.httpsbrowser.ui.BrowserScreen
import com.example.httpsbrowser.ui.BrowserScreenHost
import com.example.httpsbrowser.ui.BrowserViewModelFactory
import com.example.httpsbrowser.ui.HttpsBrowserTheme
import com.example.httpsbrowser.web.BrowserWebViewRegistry

/**
 * system PiPに入ったMainActivityが所有するfullscreen video surfaceを移動させず、
 * PiP操作から開く通常ブラウズ専用のActivity。タブ・履歴の永続状態は保存しないため、
 * PiP再生hostの選択タブやWebView sessionと競合しない。
 */
class BrowserActivity : ComponentActivity(), BrowserScreenHost {
    private lateinit var appRoot: FrameLayout
    private lateinit var normalWebContentHost: FrameLayout
    private lateinit var composeOverlayView: ComposeView
    private var normalWebContentBoundsReady = false
    private var forwardingNormalWebTouch = false
    private var normalWebContentReservesRightTouchRail = false
    private var normalWebContentPlacedAboveCompose = false
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenView: View? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ねこぶらうざ"
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
                    BrowserScreen(
                        viewModel(factory = BrowserViewModelFactory(application, restorePersistentSession = false))
                    )
                }
            }
        }
        appRoot.addView(normalWebContentHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        appRoot.addView(composeOverlayView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        composeOverlayView.setOnTouchListener { _, event -> forwardPageTouchToNativeWebView(event) }
        setContentView(appRoot)
        CrashDiagnostics.record("pip_browser_activity_opened", "session=isolated\npersistentTabs=false")
    }

    private fun forwardPageTouchToNativeWebView(event: MotionEvent): Boolean {
        if (normalWebContentHost.visibility != View.VISIBLE) return false
        val railWidth = if (normalWebContentReservesRightTouchRail) {
            (40 * resources.displayMetrics.density).toInt()
        } else 0
        val withinHost = event.x >= normalWebContentHost.left && event.x < normalWebContentHost.right &&
            event.y >= normalWebContentHost.top && event.y < normalWebContentHost.bottom
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            forwardingNormalWebTouch = withinHost && event.x < normalWebContentHost.right - railWidth
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

    override fun showNormalWebContent(registry: BrowserWebViewRegistry, tabId: String) {
        registry.attachToNativeHost(tabId, normalWebContentHost)
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
    }

    override fun setNormalWebContentVisible(visible: Boolean) {
        if (normalWebContentHost.childCount == 0) {
            normalWebContentHost.visibility = View.GONE
            return
        }
        if (visible && normalWebContentPlacedAboveCompose) normalWebContentHost.bringToFront()
        else composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentBoundsReady) View.VISIBLE else View.INVISIBLE
    }

    override fun hideNormalWebContent() {
        composeOverlayView.bringToFront()
        normalWebContentHost.visibility = if (normalWebContentHost.childCount > 0 && normalWebContentBoundsReady) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun setNormalWebContentBounds(
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
        if (placeAboveCompose) normalWebContentHost.bringToFront() else composeOverlayView.bringToFront()
        val current = normalWebContentHost.layoutParams as? FrameLayout.LayoutParams ?: return
        normalWebContentBoundsReady = true
        if (current.leftMargin == left && current.topMargin == top && current.width == width && current.height == height) return
        current.leftMargin = left
        current.topMargin = top
        current.width = width
        current.height = height
        normalWebContentHost.layoutParams = current
        if (normalWebContentHost.childCount > 0) normalWebContentHost.visibility = View.VISIBLE
    }

    override fun showFullscreenCustomView(view: View, tabId: String?) {
        if (fullscreenView === view && fullscreenContainer != null) return
        hideFullscreenCustomView()
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        appRoot.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenContainer = container
        fullscreenView = view
        runCatching { view.keepScreenOn = true }
        setFullscreenSystemBars(true)
    }

    override fun hideFullscreenCustomView(expectedView: View?) {
        val active = fullscreenView
        if (expectedView != null && active !== expectedView) return
        fullscreenContainer?.let { container ->
            appRoot.removeView(container)
            container.removeAllViews()
        }
        fullscreenContainer = null
        fullscreenView = null
        runCatching { active?.keepScreenOn = false }
        setFullscreenSystemBars(false)
    }

    override fun shouldRetainFullscreenCustomView(): Boolean = false

    override fun updatePictureInPictureVideoDimensions(tabId: String, width: Int, height: Int) = Unit

    override fun onDestroy() {
        normalWebContentHost.removeAllViews()
        super.onDestroy()
    }

    private fun setFullscreenSystemBars(fullscreen: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (fullscreen) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
