package com.example.httpsbrowser.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class BlockListSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceUrl: String,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
    val builtIn: Boolean = false
)

/** EasyList/ABP 構文のネットワーク規則サブセット（||domain^、|https://、部分文字列）を扱う。 */
class UrlRuleBlocker {
    private val rules = AtomicReference(RuleSet())

    fun shouldBlock(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        val host = target.host?.lowercase().orEmpty()
        val set = rules.get()
        if (matchesDomainSet(host, set.allowDomains) || set.allowOther.any { it.matches(target, url) }) return false
        return matchesDomainSet(host, set.blockDomains) || set.blockOther.any { it.matches(target, url) }
    }

    /** 現在のページだけに適用する、リスト由来の安全な CSS 非表示規則を返す。 */
    fun cosmeticCssFor(pageUrl: String): String {
        val host = runCatching { URI(pageUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val matchingSelectors = rules.get().cosmeticRules.asSequence()
            .filter { rule -> rule.domains.isEmpty() || rule.domains.any { domain -> host == domain || host.endsWith(".$domain") } }
            .map { it.selector }
            .distinct()
            .take(MAX_COSMETIC_SELECTORS_PER_PAGE)
            .toList()
        val declarations = "{display:none!important;visibility:hidden!important;}"
        return FALLBACK_COSMETIC_SELECTORS.joinToString(",") + declarations +
            matchingSelectors.joinToString(separator = "") { selector -> "$selector$declarations" }
    }

    fun replaceRules(lines: Sequence<String>) {
        val allowDomains = HashSet<String>()
        val blockDomains = HashSet<String>()
        val allowOther = mutableListOf<UrlRule>()
        val blockOther = mutableListOf<UrlRule>()
        val cosmeticRules = mutableListOf<CosmeticRule>()
        lines.forEach { raw ->
            val line = raw.trim()
            CosmeticRule.parse(line)?.let { rule ->
                if (cosmeticRules.size < MAX_COSMETIC_RULES) cosmeticRules += rule
                return@forEach
            }
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#")) return@forEach
            val isAllow = line.startsWith("@@")
            val rule = UrlRule.parse(if (isAllow) line.removePrefix("@@") else line) ?: return@forEach
            when (rule) {
                is UrlRule.Domain -> if (isAllow) allowDomains += rule.domain else blockDomains += rule.domain
                else -> if (isAllow) allowOther += rule else blockOther += rule
            }
        }
        rules.set(RuleSet(allowDomains, blockDomains, allowOther, blockOther, cosmeticRules))
    }

    private fun matchesDomainSet(host: String, rules: Set<String>): Boolean {
        var candidate = host
        while (candidate.isNotBlank()) {
            if (candidate in rules) return true
            candidate = candidate.substringAfter('.', "")
        }
        return false
    }

    private data class RuleSet(
        val allowDomains: Set<String> = emptySet(),
        val blockDomains: Set<String> = emptySet(),
        val allowOther: List<UrlRule> = emptyList(),
        val blockOther: List<UrlRule> = emptyList(),
        val cosmeticRules: List<CosmeticRule> = emptyList()
    )

    private data class CosmeticRule(val domains: List<String>, val selector: String) {
        companion object {
            fun parse(line: String): CosmeticRule? {
                if (line.startsWith("!") || line.contains("#%#") || line.contains("#$#") || line.contains("#@#")) return null
                val divider = line.indexOf("##")
                if (divider < 0) return null
                val selector = line.substring(divider + 2).trim()
                if (selector.isBlank() || selector.length > 500 || selector.contains("scriptlet", true)) return null
                val domains = line.substring(0, divider).split(',').map(String::trim)
                    .filter { it.isNotBlank() && it.none { character -> character == '~' || character == '*' } }
                    .map(String::lowercase)
                return CosmeticRule(domains, selector)
            }
        }
    }

    private companion object {
        const val MAX_COSMETIC_RULES = 4_000
        const val MAX_COSMETIC_SELECTORS_PER_PAGE = 450
        val FALLBACK_COSMETIC_SELECTORS = listOf(
            "ins.adsbygoogle", "[data-ad-client]", "[data-ad-slot]", "[id^='google_ads']",
            "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']", "iframe[src*='adservice']",
            "[class~='advertisement']", "[class~='advert']", "[aria-label='Advertisement']"
        )
    }
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
                    .takeIf { it.contains('.') && !it.contains('*') }?.let(::Domain)
                withoutOptions.startsWith("|https://") -> Prefix(withoutOptions.removePrefix("|"))
                withoutOptions.startsWith("http://") || withoutOptions.startsWith("https://") -> Prefix(withoutOptions)
                withoutOptions.length >= 4 && !withoutOptions.startsWith("/") -> Contains(withoutOptions.replace("*", ""))
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
            File(directory, "${source.id}.txt").takeIf(File::exists)?.useLines { it.toList().asSequence() } ?: emptySequence()
        }
        blocker.replaceRules(allLines)
        sources
    }

    /** 初回導入時に公式・HTTPS の標準リストだけを登録する。ユーザー追加リストは触らない。 */
    suspend fun ensureStandardLists(): List<BlockListSource> = withContext(Dispatchers.IO) {
        var sources = listSourcesInternal()
        STANDARD_LISTS.forEach { standard ->
            val existing = sources.firstOrNull { it.sourceUrl == standard.sourceUrl }
            if (existing == null) {
                fetchToFile(standard, standard.id)
                sources = sources + standard.copy(updatedAt = System.currentTimeMillis())
            }
        }
        saveSources(sources)
        loadAndCompile()
    }

    /** 7 日以上更新されていない標準リストのみを更新する。失敗時は直前の正常ファイルを維持する。 */
    suspend fun updateDueStandardLists(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            var updatedCount = 0
            var sources = listSourcesInternal()
            STANDARD_LISTS.forEach { standard ->
                val source = sources.firstOrNull { it.sourceUrl == standard.sourceUrl } ?: return@forEach
                if (now - source.updatedAt < UPDATE_INTERVAL_MS) return@forEach
                fetchToFile(source, source.id)
                sources = sources.map { if (it.id == source.id) it.copy(updatedAt = now) else it }
                updatedCount++
            }
            saveSources(sources)
            loadAndCompile()
            updatedCount
        }
    }

    suspend fun addOrUpdate(name: String, sourceUrl: String): Result<BlockListSource> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = validateHttpsUrl(sourceUrl)
            val existing = listSourcesInternal().firstOrNull { it.sourceUrl == normalizedUrl }
            val source = existing ?: BlockListSource(name = name.ifBlank { URI(normalizedUrl).host }, sourceUrl = normalizedUrl)
            fetchToFile(source, source.id)
            val updated = source.copy(
                name = name.ifBlank { source.name },
                sourceUrl = normalizedUrl,
                updatedAt = System.currentTimeMillis(),
                builtIn = existing?.builtIn ?: false
            )
            saveSources(listSourcesInternal().filterNot { it.id == source.id } + updated)
            loadAndCompile()
            updated
        }
    }

    suspend fun update(id: String, name: String, sourceUrl: String): Result<BlockListSource> = withContext(Dispatchers.IO) {
        runCatching {
            val previous = listSourcesInternal().firstOrNull { it.id == id }
                ?: error("更新対象のリストが見つかりません。")
            val normalizedUrl = validateHttpsUrl(sourceUrl)
            val replacement = previous.copy(name = name.ifBlank { URI(normalizedUrl).host }, sourceUrl = normalizedUrl)
            fetchToFile(replacement, replacement.id)
            val updated = replacement.copy(updatedAt = System.currentTimeMillis())
            saveSources(listSourcesInternal().map { if (it.id == id) updated else it })
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

    private fun validateHttpsUrl(sourceUrl: String): String {
        val uri = URI(sourceUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) { "ブロックリストは HTTPS URL のみ登録できます。" }
        require(!uri.host.isNullOrBlank()) { "有効な URL を入力してください。" }
        return uri.toString()
    }

    private fun fetchToFile(source: BlockListSource, fileId: String) {
        val uri = URI(validateHttpsUrl(source.sourceUrl))
        val request = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "HttpsTabBrowser/1.0")
        }
        require(request.responseCode in 200..299) { "リストを取得できませんでした: HTTP ${request.responseCode}" }
        val content = BufferedInputStream(request.inputStream).use { input ->
            input.readBytesLimited(MAX_LIST_BYTES)
        }.toString(Charsets.UTF_8)
        require(content.isNotBlank()) { "空のリストは登録できません。" }
        val temporary = File(directory, "$fileId.tmp")
        temporary.writeText(content)
        val destination = File(directory, "$fileId.txt")
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun listSourcesInternal(): List<BlockListSource> = runCatching {
        val array = JSONArray(metadata.takeIf(File::exists)?.readText() ?: "[]")
        List(array.length()) { index -> array.getJSONObject(index).let { item ->
            BlockListSource(
                id = item.getString("id"),
                name = item.getString("name"),
                sourceUrl = item.getString("sourceUrl"),
                enabled = item.optBoolean("enabled", true),
                updatedAt = item.optLong("updatedAt"),
                builtIn = item.optBoolean("builtIn", false)
            )
        } }
    }.getOrDefault(emptyList())

    private fun saveSources(sources: List<BlockListSource>) {
        metadata.writeText(JSONArray().apply {
            sources.forEach { source -> put(JSONObject().apply {
                put("id", source.id); put("name", source.name); put("sourceUrl", source.sourceUrl)
                put("enabled", source.enabled); put("updatedAt", source.updatedAt); put("builtIn", source.builtIn)
            }) }
        }.toString())
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_LIST_BYTES = 12 * 1024 * 1024

        val STANDARD_LISTS = listOf(
            BlockListSource(
                id = "adguard_base",
                name = "AdGuard ベースフィルタ",
                sourceUrl = "https://filters.adtidy.org/extension/chromium/filters/2.txt",
                builtIn = true
            ),
            BlockListSource(
                id = "adguard_tracking",
                name = "AdGuard 追跡防止フィルタ",
                sourceUrl = "https://filters.adtidy.org/extension/chromium/filters/3.txt",
                builtIn = true
            ),
            BlockListSource(
                id = "adguard_mobile",
                name = "AdGuard モバイル広告フィルタ",
                sourceUrl = "https://filters.adtidy.org/extension/chromium/filters/11.txt",
                builtIn = true
            ),
            BlockListSource(
                id = "adguard_japanese",
                name = "AdGuard 日本語フィルタ",
                sourceUrl = "https://filters.adtidy.org/extension/chromium/filters/7.txt",
                builtIn = true
            )
        )
    }
}

/** ネットワーク接続時に 7 日ごとを目安として更新する。端末の省電力設定により実行時刻は前後する。 */
class AdBlockUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = AdBlockListRepository(applicationContext, UrlRuleBlocker())
        repository.ensureStandardLists()
        return repository.updateDueStandardLists().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        private const val WORK_NAME = "https_tab_browser_adblock_update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AdBlockUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

private fun BufferedInputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "ブロックリストが上限 ${maxBytes / (1024 * 1024)} MB を超えています。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
