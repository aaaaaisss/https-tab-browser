package com.example.httpsbrowser.web

import java.net.URI
import java.util.Locale

object MinimalAdBlockClient {
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adservice.google.com", "ads.youtube.com"
    )
    private val blockedPathTokens = listOf(
        "/pagead", "/ads/", "/adserver", "/adservice", "/advertising",
        "/prebid/", "/gampad/", "/api/stats/ads"
    )
    private val mediaExtensionRegex = Regex(
        ".*\\.(?:m3u8|mpd|mp4|webm|m4s|ts|aac|opus|mp3)(?:$|[?#]).*",
        RegexOption.IGNORE_CASE
    )

    fun shouldBlockMinimalAd(url: String, documentUrl: String, resourceType: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return false
        if (resourceType.equals("media", true) || mediaExtensionRegex.matches(url)) return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        val youtube = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
        if (youtube) return path.startsWith("/api/stats/ads") || path.startsWith("/_get_ads") ||
            path.startsWith("/pagead") || path.contains("/youtubei/v1/player/ad_break")
        return blockedPathTokens.any { path.contains(it) }
    }
}
