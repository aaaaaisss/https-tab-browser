package com.example.httpsbrowser.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class BlockListSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceUrl: String,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L
)

/** EasyList/ABP 構文のネットワーク規則サブセット（||domain^、|https://、部分文字列）を扱う。 */
class UrlRuleBlocker {
    private val rules = AtomicReference(RuleSet())

    fun shouldBlock(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        val set = rules.get()
        if (set.allow.any { it.matches(target, url) }) return false
        return set.block.any { it.matches(target, url) }
    }

    fun replaceRules(lines: Sequence<String>) {
        val allow = mutableListOf<UrlRule>()
        val block = mutableListOf<UrlRule>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[")) return@forEach
            val isAllow = line.startsWith("@@")
            val rule = UrlRule.parse(if (isAllow) line.removePrefix("@@") else line) ?: return@forEach
            if (isAllow) allow += rule else block += rule
        }
        rules.set(RuleSet(allow, block))
    }

    private data class RuleSet(val allow: List<UrlRule> = emptyList(), val block: List<UrlRule> = emptyList())
}

private sealed interface UrlRule {
    fun matches(uri: URI, rawUrl: String): Boolean

    data class Domain(val domain: String) : UrlRule {
        override fun matches(uri: URI, rawUrl: String): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return host == domain || host.endsWith(".$domain")
        }
    }

    data class Prefix(val prefix: String) : UrlRule {
        override fun matches(uri: URI, rawUrl: String) = rawUrl.startsWith(prefix, ignoreCase = true)
    }

    data class Contains(val needle: String) : UrlRule {
        override fun matches(uri: URI, rawUrl: String) = rawUrl.contains(needle, ignoreCase = true)
    }

    companion object {
        fun parse(source: String): UrlRule? {
            val withoutOptions = source.substringBefore("$").trim()
            return when {
                withoutOptions.startsWith("||") -> withoutOptions.removePrefix("||")
                    .substringBefore('^').substringBefore('/').lowercase()
                    .takeIf { it.contains('.') }?.let(::Domain)
                withoutOptions.startsWith("|https://") -> Prefix(withoutOptions.removePrefix("|"))
                withoutOptions.startsWith("http://") || withoutOptions.startsWith("https://") -> Prefix(withoutOptions)
                withoutOptions.length >= 4 -> Contains(withoutOptions.replace("*", ""))
                else -> null
            }
        }
    }
}

class AdBlockListRepository(
    private val context: Context,
    private val blocker: UrlRuleBlocker
) {
    private val directory = File(context.filesDir, "adblock").apply { mkdirs() }
    private val metadata = File(directory, "lists.json")

    suspend fun loadAndCompile(): List<BlockListSource> = withContext(Dispatchers.IO) {
        val sources = listSourcesInternal()
        val allLines = sources.asSequence().filter { it.enabled }.flatMap { source ->
            File(directory, "${source.id}.txt").takeIf(File::exists)?.readLines()?.asSequence() ?: emptySequence()
        }
        blocker.replaceRules(allLines)
        sources
    }

    suspend fun addOrUpdate(name: String, sourceUrl: String): Result<BlockListSource> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI(sourceUrl)
            require(uri.scheme.equals("https", ignoreCase = true)) { "ブロックリストは HTTPS URL のみ登録できます。" }
            require(!uri.host.isNullOrBlank()) { "有効な URL を入力してください。" }
            val source = listSourcesInternal().firstOrNull { it.sourceUrl == sourceUrl }
                ?: BlockListSource(name = name.ifBlank { uri.host }, sourceUrl = sourceUrl)
            val request = (uri.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "HttpsTabBrowser/1.0")
            }
            require(request.responseCode in 200..299) { "リストを取得できませんでした: HTTP ${request.responseCode}" }
            val content = BufferedInputStream(request.inputStream).use { input ->
                input.readBytesLimited(5 * 1024 * 1024)
            }.toString(Charsets.UTF_8)
            require(content.isNotBlank()) { "空のリストは登録できません。" }
            File(directory, "${source.id}.txt").writeText(content)
            val updated = source.copy(name = name.ifBlank { source.name }, updatedAt = System.currentTimeMillis())
            val updatedSources = listSourcesInternal().filterNot { it.id == source.id } + updated
            saveSources(updatedSources)
            loadAndCompile()
            updated
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        saveSources(listSourcesInternal().map { if (it.id == id) it.copy(enabled = enabled) else it })
        loadAndCompile()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.txt").delete()
        saveSources(listSourcesInternal().filterNot { it.id == id })
        loadAndCompile()
    }

    private fun listSourcesInternal(): List<BlockListSource> = runCatching {
        val array = JSONArray(metadata.takeIf(File::exists)?.readText() ?: "[]")
        List(array.length()) { index -> array.getJSONObject(index).let { item ->
            BlockListSource(
                id = item.getString("id"), name = item.getString("name"),
                sourceUrl = item.getString("sourceUrl"), enabled = item.optBoolean("enabled", true),
                updatedAt = item.optLong("updatedAt")
            )
        } }
    }.getOrDefault(emptyList())

    private fun saveSources(sources: List<BlockListSource>) {
        metadata.writeText(JSONArray().apply {
            sources.forEach { source -> put(JSONObject().apply {
                put("id", source.id); put("name", source.name); put("sourceUrl", source.sourceUrl)
                put("enabled", source.enabled); put("updatedAt", source.updatedAt)
            }) }
        }.toString())
    }
}

private fun BufferedInputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "ブロックリストが上限 5 MB を超えています。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
