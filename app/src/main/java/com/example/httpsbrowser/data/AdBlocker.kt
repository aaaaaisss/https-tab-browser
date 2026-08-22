package com.example.httpsbrowser.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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

data class BlockListSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceUrl: String,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
    val builtIn: Boolean = false
)

data class AdBlockStatus(
    val blockedToday: Int,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int,
    val engineReady: Boolean = false
)

/**
 * フィルタリストの取得・永続化・更新だけを担当する。規則解釈は BraveAdBlockEngine へ委譲する。
 */
class AdBlockListRepository(
    private val context: Context,
    private val blocker: BraveAdBlockEngine
) {
    private val directory = File(context.filesDir, "adblock").apply { mkdirs() }
    private val metadata = File(directory, "lists.json")

    /**
     * ルール本文をKotlin/JNIの巨大Stringとして複製せず、アプリ内ファイルをRust側で読み込む。
     * これにより指定2標準リストと任意追加リストを維持したまま、初期コンパイル時のKotlinヒープ消費を抑える。
     */
    suspend fun loadAndCompile(): List<BlockListSource> = withContext(Dispatchers.Default) {
        val sources = listSourcesInternal()
        val sourceFiles = sources.asSequence().filter { it.enabled }
            .map { source -> source to File(directory, "${source.id}.txt") }
            .filter { (_, file) -> file.isFile && file.length() > 0L }
            .toList()
        val trustedStandardFiles = sourceFiles.asSequence()
            .filter { (source, _) -> source.builtIn && source.sourceUrl in STANDARD_LIST_URLS }
            .map { (_, file) -> file }
            .toSet()
        blocker.replaceRuleFiles(sourceFiles.map { (_, file) -> file }, trustedStandardFiles)
        sources
    }

    fun blockStatus(): AdBlockStatus = blocker.status()

    /** 初回導入時に公式・HTTPS の標準リストだけを登録する。ユーザー追加リストは変更しない。 */
    suspend fun ensureStandardLists(): List<BlockListSource> = withContext(Dispatchers.IO) {
        var sources = listSourcesInternal()
        // 廃止した組込みリストだけを削除し、ユーザーが追加した任意リストはそのまま維持する。
        val retiredBuiltIns = sources.filter { it.builtIn && it.sourceUrl !in STANDARD_LIST_URLS }
        retiredBuiltIns.forEach { source -> File(directory, "${source.id}.txt").delete() }
        sources = sources.filterNot { it in retiredBuiltIns }
        STANDARD_LISTS.forEach { standard ->
            if (sources.none { it.sourceUrl == standard.sourceUrl }) {
                // 同梱snapshotにより、オフラインの初回起動でも直ちに指定2リストを利用できる。
                if (!copyBundledSnapshot(standard.id)) fetchToFile(standard, standard.id)
                sources = sources + standard.copy(updatedAt = System.currentTimeMillis())
            }
        }
        saveSources(sources)
        sources
        }

    /** 7 日以上更新されていない標準リストだけを更新する。失敗時は直前の正常ファイルを維持する。 */
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
            updatedCount
        }
    }

    /** ユーザー操作時は更新期限を待たず、有効な標準・追加リストをすべて取得して即時再コンパイルする。 */
    suspend fun forceUpdateEnabledLists(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var sources = listSourcesInternal()
            var updatedCount = 0
            sources.filter { it.enabled }.forEach { source ->
                fetchToFile(source, source.id)
                val updated = source.copy(updatedAt = System.currentTimeMillis())
                sources = sources.map { if (it.id == source.id) updated else it }
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

    private fun copyBundledSnapshot(fileId: String): Boolean = runCatching {
        context.assets.open("adblock/$fileId.txt").use { input ->
            val temporary = File(directory, "$fileId.tmp")
            temporary.outputStream().use { output -> input.copyTo(output) }
            val destination = File(directory, "$fileId.txt")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }
        true
    }.getOrDefault(false)

    private fun fetchToFile(source: BlockListSource, fileId: String) {
        val uri = URI(validateHttpsUrl(source.sourceUrl))
        val request = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "HttpsTabBrowser/1.0")
        }
        require(request.responseCode in 200..299) { "リストを取得できませんでした: HTTP ${request.responseCode}" }
        val content = BufferedInputStream(request.inputStream).use { input -> input.readBytesLimited(MAX_LIST_BYTES) }.toString(Charsets.UTF_8)
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
                "adguard_android_2_optimized",
                "AdGuard Base フィルタ（Android 最適化版）",
                "https://filters.adtidy.org/android/filters/2_optimized.txt",
                builtIn = true
            ),
            BlockListSource(
                "brave_specific",
                "Brave Specific（YouTube・動画補助）",
                "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt",
                builtIn = true
            ),
            BlockListSource(
                "adguard_android_7_optimized",
                "AdGuard 日本語フィルタ（Android 最適化版）",
                "https://filters.adtidy.org/android/filters/7_optimized.txt",
                builtIn = true
            )
        )
        val STANDARD_LIST_URLS = STANDARD_LISTS.map(BlockListSource::sourceUrl).toSet()
    }
}

/** ネットワーク接続時に 7 日ごとを目安として標準リストを更新する。 */
class AdBlockUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val engine = BraveAdBlockEngine(applicationContext)
        val repository = AdBlockListRepository(applicationContext, engine)
        return try {
            repository.ensureStandardLists()
            repository.updateDueStandardLists().fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() }
            )
        } finally {
            engine.close()
        }
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
