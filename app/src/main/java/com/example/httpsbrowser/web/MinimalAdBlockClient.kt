package com.example.httpsbrowser.web

import java.net.URI
import java.util.Locale

/**
 * APK-era minimal URL blocker.
 *
 * The APK exposes this helper as shouldBlockMinimalAd(String). Keep the
 * decision intentionally narrow: only destinations whose host/path is
 * evidenced by the APK are handled here.
 */
object MinimalAdBlockClient {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "ads.youtube.com"
    )

    private val blockedPathTokens = listOf(
        "/pagead",
        "/api/stats/ads",
        "/_get_ads",
        "/youtubei/v1/player/ad_break"
    )

    fun shouldBlockMinimalAd(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return false

        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true

        val youtube = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")

        return if (youtube) {
            blockedPathTokens.any { path.startsWith(it) || path.contains(it) }
        } else {
            path.startsWith("/pagead")
        }
    }
}
