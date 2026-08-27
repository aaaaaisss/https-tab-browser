package com.example.httpsbrowser.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * ユーザーが明示的に選んだ引き継ぎデータだけを、端末外へ書き出し・端末内へ読み込む。
 *
 * この形式にはCookie、ログイン状態、Web Storage、閲覧履歴、開いているタブ、
 * ダウンロード済みファイル、広告フィルタ本文を含めない。フィルタは追加URLと有効状態だけを扱う。
 */
data class BrowserTransferPayload(
    val bookmarks: List<Bookmark>,
    val settings: BrowserSettings,
    val customFilterSources: List<TransferFilterSource>
)

data class TransferFilterSource(
    val name: String,
    val sourceUrl: String,
    val enabled: Boolean
)

object BrowserDataTransfer {
    private const val FORMAT = "https-tab-browser-transfer"
    private const val SCHEMA_VERSION = 1
    private const val MAX_INPUT_BYTES = 1_000_000
    private const val MAX_BOOKMARKS = 1_000
    private const val MAX_CUSTOM_FILTERS = 100
    private const val MAX_DARK_EXCLUSIONS = 200
    private const val MAX_TITLE_LENGTH = 160
    private const val MAX_URL_LENGTH = 2_048
    private const val MIN_VIDEO_PLAYBACK_RATE = 0.25f
    private const val MAX_VIDEO_PLAYBACK_RATE = 3f

    /** このアプリの全引き継ぎ対象を、portableなUTF-8 JSONとして作成する。 */
    fun exportJson(
        bookmarks: List<Bookmark>,
        settings: BrowserSettings,
        sources: List<BlockListSource>
    ): String = JSONObject().apply {
        put("format", FORMAT)
        put("schemaVersion", SCHEMA_VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("bookmarks", JSONArray().apply {
            bookmarks.take(MAX_BOOKMARKS).forEach { bookmark -> put(JSONObject().apply {
                put("title", bookmark.title.take(MAX_TITLE_LENGTH))
                put("url", bookmark.url)
            }) }
        })
        put("settings", JSONObject().apply {
            put("forceDarkPages", settings.forceDarkPages)
            put("forceDarkVideoPages", settings.forceDarkVideoPages)
            put("skipDarkeningAlreadyDarkPages", settings.skipDarkeningAlreadyDarkPages)
            put("darkModeExcludedHosts", JSONArray().apply {
                settings.darkModeExcludedHosts.take(MAX_DARK_EXCLUSIONS).forEach(::put)
            })
            put("adBlockingEnabled", settings.adBlockingEnabled)
            put("aggressiveAdBlockingEnabled", settings.aggressiveAdBlockingEnabled)
            put("preferredVideoPlaybackRate", settings.preferredVideoPlaybackRate)
            put("javascriptEnabled", settings.javascriptEnabled)
        })
        put("customFilterSources", JSONArray().apply {
            // 組込みフィルタとルール本文・更新時刻は復元対象にしない。
            sources.asSequence().filterNot(BlockListSource::builtIn).take(MAX_CUSTOM_FILTERS).forEach { source -> put(JSONObject().apply {
                put("name", source.name.take(MAX_TITLE_LENGTH))
                put("sourceUrl", source.sourceUrl)
                put("enabled", source.enabled)
            }) }
        })
    }.toString(2)

    /**
     * 可搬性の高い自己完結HTMLとして書き出す。実データはscript要素中の厳格なJSONであり、
     * 外部URL・画像・スクリプトは一切読み込まない。旧JSON形式も読み込み互換として維持する。
     */
    fun exportHtml(
        bookmarks: List<Bookmark>,
        settings: BrowserSettings,
        sources: List<BlockListSource>
    ): String {
        val jsonForHtml = exportJson(bookmarks, settings, sources)
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
        return """<!doctype html>
<html lang="ja">
<head><meta charset="utf-8"><title>HTTPS Tab Browser 引き継ぎデータ</title></head>
<body>
<h1>HTTPS Tab Browser 引き継ぎデータ</h1>
<p>このファイルにはブックマーク、設定、追加フィルタのURL・有効状態だけが含まれます。</p>
<p>Cookie、ログイン状態、Web Storage、履歴、開いているタブ、ダウンロード済みファイル、フィルタ本文は含まれません。</p>
<script id="https-tab-browser-transfer" type="application/json">$jsonForHtml</script>
</body>
</html>
"""
    }

    /**
     * スキーマ全体を先に検証する。成功時にだけViewModelが状態と追加フィルタを置換するため、
     * 壊れた・別形式のファイルによる部分的な取り込みを起こさない。
     */
    fun import(raw: String): Result<BrowserTransferPayload> = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "引き継ぎファイルが大きすぎます。" }
        val root = JSONObject(extractJsonPayload(raw))
        require(root.optString("format") == FORMAT) { "このアプリの引き継ぎファイルではありません。" }
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "この引き継ぎファイルの形式には対応していません。" }

        val bookmarks = parseBookmarks(root.getJSONArray("bookmarks"))
        val settings = parseSettings(root.getJSONObject("settings"))
        val customFilters = parseCustomFilters(root.getJSONArray("customFilterSources"))
        BrowserTransferPayload(bookmarks, settings, customFilters)
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val match = TRANSFER_HTML_SCRIPT.find(trimmed)
            ?: error("引き継ぎデータを含むHTMLファイルではありません。")
        return match.groupValues[1].trim()
    }

    private fun parseBookmarks(array: JSONArray): List<Bookmark> {
        require(array.length() <= MAX_BOOKMARKS) { "ブックマーク数が上限を超えています。" }
        val seenUrls = HashSet<String>()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val url = validateHttpsUrl(item.getString("url"), "ブックマークURL")
            require(seenUrls.add(url)) { "ブックマークURLが重複しています。" }
            val title = item.optString("title").trim().take(MAX_TITLE_LENGTH).ifBlank { url }
            Bookmark(id = UUID.randomUUID().toString(), title = title, url = url)
        }
    }

    private fun parseSettings(item: JSONObject): BrowserSettings {
        val excludedHosts = item.getJSONArray("darkModeExcludedHosts").let { array ->
            require(array.length() <= MAX_DARK_EXCLUSIONS) { "暗色化例外の件数が上限を超えています。" }
            List(array.length()) { index -> normalizeHost(array.getString(index)) }
                .distinct()
        }
        return BrowserSettings(
            forceDarkPages = item.getBoolean("forceDarkPages"),
            forceDarkVideoPages = item.getBoolean("forceDarkVideoPages"),
            skipDarkeningAlreadyDarkPages = item.getBoolean("skipDarkeningAlreadyDarkPages"),
            darkModeExcludedHosts = excludedHosts,
            adBlockingEnabled = item.getBoolean("adBlockingEnabled"),
            aggressiveAdBlockingEnabled = item.getBoolean("aggressiveAdBlockingEnabled"),
            preferredVideoPlaybackRate = item.getDouble("preferredVideoPlaybackRate").toFloat()
                .takeIf { it in MIN_VIDEO_PLAYBACK_RATE..MAX_VIDEO_PLAYBACK_RATE }
                ?: error("動画速度の値が不正です。"),
            javascriptEnabled = item.getBoolean("javascriptEnabled")
        )
    }

    private fun parseCustomFilters(array: JSONArray): List<TransferFilterSource> {
        require(array.length() <= MAX_CUSTOM_FILTERS) { "追加フィルタ数が上限を超えています。" }
        val seenUrls = HashSet<String>()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val sourceUrl = validateHttpsUrl(item.getString("sourceUrl"), "追加フィルタURL")
            require(seenUrls.add(sourceUrl)) { "追加フィルタURLが重複しています。" }
            val name = item.optString("name").trim().take(MAX_TITLE_LENGTH)
                .ifBlank { URI(sourceUrl).host.orEmpty() }
            TransferFilterSource(name, sourceUrl, item.getBoolean("enabled"))
        }
    }

    private val TRANSFER_HTML_SCRIPT = Regex(
        """<script\s+id=[\"']https-tab-browser-transfer[\"']\s+type=[\"']application/json[\"']\s*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )

    private fun validateHttpsUrl(raw: String, label: String): String {
        require(raw.length <= MAX_URL_LENGTH) { "$label が長すぎます。" }
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo.isNullOrBlank()) {
            "$label は有効なHTTPS URLにしてください。"
        }
        return uri.toString()
    }

    private fun normalizeHost(raw: String): String {
        val value = raw.trim().lowercase()
        require(value.length in 1..253 && value.matches(Regex("[a-z0-9.-]+"))) { "暗色化例外のホスト名が不正です。" }
        return value.removePrefix("www.")
    }
}
