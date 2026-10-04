package com.example.httpsbrowser.web

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

object MinimalAdBlockClient {
    private val blockedHosts = setOf("ads.youtube.com","doubleclick.net","googlesyndication.com","googleadservices.com","googletagservices.com")
    private val blockedPathTokens = listOf("/api/stats/ads","/_get_ads","/pagead","/youtubei/v1/player/ad_break")
    fun createEmptyAdResponse(): WebResourceResponse = WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
    fun shouldBlockMinimalAd(url: String): Boolean {
        if (url.isBlank()) return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val host=uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path=uri.path?.lowercase(Locale.ROOT).orEmpty()
        if(host.isBlank())return false
        if(blockedHosts.any{host==it||host.endsWith("."+it)})return true
        val youtube=host=="youtube.com"||host.endsWith(".youtube.com")||host=="youtube-nocookie.com"||host.endsWith(".youtube-nocookie.com")
        return if(youtube) blockedPathTokens.any{path.startsWith(it)||path.contains(it)} else path.startsWith("/pagead")
    }
}
