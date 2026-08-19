package com.example.httpsbrowser.data

import android.content.Context
import android.content.SharedPreferences
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
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
class UrlRuleBlocker(context: Context? = null) {
    private val rules = AtomicReference(RuleSet())
    private val statistics: SharedPreferences? = context?.applicationContext?.getSharedPreferences(STATISTICS_FILE, Context.MODE_PRIVATE)
    private val blockedToday = AtomicInteger(readStoredBlockedCount())
    private val statisticsLock = Any()

    fun shouldBlock(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        val host = target.host?.lowercase().orEmpty()
        val set = rules.get()
        if (matchesDomainSet(host, set.allowDomains) || matchesDomainPathRules(target, url, host, set.allowDomainPaths) ||
            set.allowOther.any { it.matches(target, url) }) return false
        val blocked = matchesDomainSet(host, set.blockDomains) || matchesDomainPathRules(target, url, host, set.blockDomainPaths) ||
            set.blockOther.any { it.matches(target, url) }
        if (blocked) recordBlockedRequest()
        return blocked
    }

    /** 設定画面で、今日の実際の遮断数とコンパイル済み規則数を確認する。 */
    fun status(): AdBlockStatus {
        resetCounterIfNewDay()
        val set = rules.get()
        return AdBlockStatus(
            blockedToday = blockedToday.get(),
            networkRuleCount = set.blockDomains.size + set.blockDomainPaths.values.sumOf { it.size } + set.blockOther.size,
            cosmeticRuleCount = FALLBACK_COSMETIC_SELECTORS.size
        )
    }

    private fun readStoredBlockedCount(): Int {
        val preferences = statistics ?: return 0
        return if (preferences.getString(STATISTICS_DAY_KEY, "") == localDayKey()) {
            preferences.getInt(STATISTICS_COUNT_KEY, 0)
        } else {
            preferences.edit().putString(STATISTICS_DAY_KEY, localDayKey()).putInt(STATISTICS_COUNT_KEY, 0).apply()
            0
        }
    }

    private fun recordBlockedRequest() {
        synchronized(statisticsLock) {
            resetCounterIfNewDay()
            val count = blockedToday.incrementAndGet()
            statistics?.edit()?.putString(STATISTICS_DAY_KEY, localDayKey())?.putInt(STATISTICS_COUNT_KEY, count)?.apply()
        }
    }

    private fun resetCounterIfNewDay() {
        val preferences = statistics ?: return
        val today = localDayKey()
        if (preferences.getString(STATISTICS_DAY_KEY, "") != today) {
            synchronized(statisticsLock) {
                if (preferences.getString(STATISTICS_DAY_KEY, "") != today) {
                    blockedToday.set(0)
                    preferences.edit().putString(STATISTICS_DAY_KEY, today).putInt(STATISTICS_COUNT_KEY, 0).apply()
                }
            }
        }
    }

    private fun localDayKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    /** 全サイトへ安全に適用できる、汎用広告タグだけの最小 CSS 非表示規則を返す。 */
    fun cosmeticCssFor(): String {
        val declarations = "{display:none!important;visibility:hidden!important;}"
        return FALLBACK_COSMETIC_SELECTORS.joinToString(",") + declarations
    }

    fun replaceRules(lines: Sequence<String>) {
        val allowDomains = HashSet<String>()
        val blockDomains = HashSet<String>()
        val allowDomainPaths = HashMap<String, MutableList<UrlRule.DomainPath>>()
        val blockDomainPaths = HashMap<String, MutableList<UrlRule.DomainPath>>()
        val allowOther = mutableListOf<UrlRule>()
        val blockOther = mutableListOf<UrlRule>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#") ||
                line.contains("##") || line.contains("#@#") || line.contains("#%#") || line.contains("#$#")) return@forEach
            val isAllow = line.startsWith("@@")
            val rule = UrlRule.parse(if (isAllow) line.removePrefix("@@") else line) ?: return@forEach
            when (rule) {
                is UrlRule.Domain -> if (isAllow) allowDomains += rule.domain else blockDomains += rule.domain
                is UrlRule.DomainPath -> {
                    val target = if (isAllow) allowDomainPaths else blockDomainPaths
                    target.getOrPut(rule.domain) { mutableListOf() } += rule
                }
                else -> if (isAllow) allowOther += rule else blockOther += rule
            }
        }
        rules.set(RuleSet(allowDomains, blockDomains, allowDomainPaths, blockDomainPaths, allowOther, blockOther))
    }

    private fun matchesDomainSet(host: String, rules: Set<String>): Boolean {
        var candidate = host
        while (candidate.isNotBlank()) {
            if (candidate in rules) return true
            candidate = candidate.substringAfter('.', "")
        }
        return false
    }

    private fun matchesDomainPathRules(
        uri: URI,
        rawUrl: String,
        host: String,
        rulesByDomain: Map<String, List<UrlRule.DomainPath>>
    ): Boolean {
        var candidate = host
        while (candidate.isNotBlank()) {
            if (rulesByDomain[candidate]?.any { it.matches(uri, rawUrl) } == true) return true
            candidate = candidate.substringAfter('.', "")
        }
        return false
    }

    private data class RuleSet(
        val allowDomains: Set<String> = emptySet(),
        val blockDomains: Set<String> = emptySet(),
        val allowDomainPaths: Map<String, List<UrlRule.DomainPath>> = emptyMap(),
        val blockDomainPaths: Map<String, List<UrlRule.DomainPath>> = emptyMap(),
        val allowOther: List<UrlRule> = emptyList(),
        val blockOther: List<UrlRule> = emptyList()
    )

    private companion object {
        const val STATISTICS_FILE = "adblock_statistics"
        const val STATISTICS_DAY_KEY = "day"
        const val STATISTICS_COUNT_KEY = "blocked_count"
        val FALLBACK_COSMETIC_SELECTORS = listOf(
            "ins.adsbygoogle", "[data-ad-client]", "[data-ad-slot]", "[id^='google_ads']",
            "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']", "iframe[src*='adservice']",
            "[aria-label='Advertisement']"
        )
    }
}

data class AdBlockStatus(
    val blockedToday: Int,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int
)

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

    /** `||domain/path` をドメイン全体の規則に縮約せず、対象 host の指定パスだけへ適用する。 */
    data class DomainPath(val domain: String, val pathPrefix: String) : UrlRule {
        override fun matches(uri: URI, rawUrl: String): Boolean {
            val host = uri.host?.lowercase() ?: return false
            if (host != domain && !host.endsWith(".$domain")) return false
            val pathAndQuery = uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty()
            return pathAndQuery.startsWith(pathPrefix, ignoreCase = true)
        }
    }

    companion object {
        fun parse(source: String): UrlRule? {
            // 第三者判定・リダイレクト・scriptlet などを伴う高度な ABP オプションは、
            // WebView の URL だけでは正確に再現できない。誤遮断を避けるため読み飛ばす。
            if (source.contains("$")) return null
            val withoutOptions = source.trim()
            return when {
                withoutOptions.startsWith("||") -> {
                    val body = withoutOptions.removePrefix("||")
                    val domain = body.substringBefore('^').substringBefore('/').lowercase()
                    val suffix = body.removePrefix(domain).substringBefore('^').replace("*", "")
                    domain.takeIf { it.contains('.') && !it.contains('*') }?.let {
                        if (suffix.isBlank()) Domain(it) else DomainPath(it, suffix)
                    }
                }
                withoutOptions.startsWith("|https://") -> Prefix(withoutOptions.removePrefix("|"))
                withoutOptions.startsWith("http://") || withoutOptions.startsWith("https://") -> Prefix(withoutOptions)
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

    /** コンパイル済み規則と実際の遮断数を設定画面で表示するための状態を返す。 */
    fun blockStatus(): AdBlockStatus = blocker.status()

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
