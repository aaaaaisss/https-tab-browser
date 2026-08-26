package com.example.httpsbrowser.ui

import android.view.View
import android.webkit.WebChromeClient
import com.example.httpsbrowser.web.BrowserWebViewRegistry

/**
 * BrowserScreenが通常WebViewとfullscreen custom viewをActivityのnative rootへ接続するための最小契約。
 * PiP再生hostとPiPから開く独立ブラウズActivityの間でViewインスタンスは共有しない。
 */
interface BrowserScreenHost {
    fun showNormalWebContent(registry: BrowserWebViewRegistry, tabId: String)
    fun setNormalWebContentVisible(visible: Boolean)
    fun hideNormalWebContent()
    fun setNormalWebContentBounds(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        reserveRightTouchRail: Boolean,
        placeAboveCompose: Boolean
    )
    fun showFullscreenCustomView(view: View, tabId: String?)
    fun hideFullscreenCustomView(expectedView: View? = null)
    fun shouldRetainFullscreenCustomView(): Boolean
    fun updatePictureInPictureVideoDimensions(tabId: String, width: Int, height: Int)
}
