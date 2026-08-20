package com.example.httpsbrowser.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.example.httpsbrowser.CrashDiagnostics
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Brave adblock-rust を Android JNI 経由で利用するフィルタ実行器。
 * 規則の解釈はネイティブエンジンに任せ、Kotlin 側で ABP 規則を再実装しない。
 */
class BraveAdBlockEngine(context: Context) {
    private val appContext = context.applicationContext
    private val statistics: SharedPreferences = appContext
        .getSharedPreferences(STATISTICS_FILE, Context.MODE_PRIVATE)
    private val cacheDirectory = File(appContext.noBackupFilesDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }
    private val serializedEngineFile = File(cacheDirectory, SERIALIZED_ENGINE_FILE_NAME)
    private val serializedEngineMetadata = AtomicFile(File(cacheDirectory, SERIALIZED_ENGINE_METADATA_FILE_NAME))
    private val scriptletResourceFile = File(cacheDirectory, SCRIPTLET_RESOURCE_FILE_NAME)
    private val blockedToday = AtomicInteger(readStoredBlockedCount())
    private val statisticsLock = Any()
    @Volatile private var statisticsDay = localDayKey()
    private val activeHandle = AtomicLong(0L)

    @Volatile private var networkRuleCount = 0
    @Volatile private var cosmeticRuleCount = 0
    @Volatile private var compiledFileSignature: String? = null

    /**
     * ルール本文をKotlin/JNIの巨大なStringとして二重・三重に複製しない。
     * 同一本文・同一scriptletリソース・同一エンジン版では直列化済みEngineを復元し、
     * 初回・更新・破損時だけ再コンパイルする。
     *
     * trustedRuleFiles は公式標準リストだけを渡す。任意URLから追加されたリストは、
     * ネットワーク/CSS規則には使うが、ページ内JavaScriptのscriptlet実行権限を持たない。
     */
    fun replaceRuleFiles(files: List<File>, trustedRuleFiles: Set<File> = emptySet()) {
        val existingFiles = files.filter { it.isFile && it.length() > 0L }
        if (existingFiles.isEmpty()) {
            CrashDiagnostics.record("adblock_compile_skipped", "reason=no_filter_files")
            return
        }
        val resourceFile = ensureScriptletResourceFileOrNull()
        val trustedPaths = trustedRuleFiles.asSequence()
            .filter { it.isFile && it.length() > 0L }
            .map(File::getAbsolutePath)
            .toSet()
        val signature = contentSignature(existingFiles, resourceFile, trustedPaths)
        if (activeHandle.get() != 0L && compiledFileSignature == signature) return
        if (statistics.getBoolean(COMPILATION_IN_PROGRESS_KEY, false)) {
            CrashDiagnostics.record("adblock_compile_skipped", "reason=previous_compile_did_not_finish\nfiles=${existingFiles.size}")
            return
        }
        synchronized(COMPILATION_LOCK) {
            if (statistics.getBoolean(COMPILATION_IN_PROGRESS_KEY, false)) return
            statistics.edit().putBoolean(COMPILATION_IN_PROGRESS_KEY, true).commit()
            val totalBytes = existingFiles.sumOf(File::length)
            CrashDiagnostics.record(
                "adblock_engine_prepare_started",
                "files=${existingFiles.size}\ntrustedFiles=${trustedPaths.size}\nbytes=$totalBytes\nscriptletResources=${resourceFile?.name ?: "unavailable"}"
            )
            try {
                val previous = activeHandle.getAndSet(0L)
                if (previous != 0L) NativeAdBlockEngine.destroy(previous)
                compiledFileSignature = null

                val resourcePath = resourceFile?.absolutePath.orEmpty()
                val cachedHandle = restoreCachedEngine(signature, resourcePath)
                val newHandle = if (cachedHandle != 0L) {
                    CrashDiagnostics.record("adblock_cache_restored", "files=${existingFiles.size}\nbytes=$totalBytes")
                    cachedHandle
                } else {
                    CrashDiagnostics.record("adblock_compile_started", "files=${existingFiles.size}\nbytes=$totalBytes")
                    NativeAdBlockEngine.createFromFiles(
                        paths = existingFiles.map(File::getAbsolutePath),
                        trustedPaths = trustedPaths.toList(),
                        scriptletResourcePath = resourcePath
                    )
                }
                if (newHandle == 0L) {
                    CrashDiagnostics.record("adblock_compile_failed", "native_handle=0\nfiles=${existingFiles.size}")
                    return
                }

                activeHandle.set(newHandle)
                compiledFileSignature = signature
                if (cachedHandle == 0L) persistCachedEngine(newHandle, signature)
                val counts = countRules(existingFiles)
                networkRuleCount = counts.first
                cosmeticRuleCount = counts.second
                CrashDiagnostics.record(
                    "adblock_engine_ready",
                    "source=${if (cachedHandle != 0L) "cache" else "compile"}\nnetworkRules=$networkRuleCount\ncosmeticRules=$cosmeticRuleCount\nfiles=${existingFiles.size}\ntrustedFiles=${trustedPaths.size}\nscriptletResources=${resourceFile != null}"
                )
            } catch (throwable: Throwable) {
                CrashDiagnostics.record("adblock_engine_exception", "${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            } finally {
                statistics.edit().remove(COMPILATION_IN_PROGRESS_KEY).commit()
            }
        }
    }

    /** 同一リスト本文・同一リソース・同一キャッシュ形式・同一ネイティブ版だけを復元候補にする。 */
    private fun restoreCachedEngine(signature: String, resourcePath: String): Long {
        if (!serializedEngineFile.isFile || !cacheSignatureMatches(signature)) {
            CrashDiagnostics.record("adblock_cache_miss", "reason=missing_or_signature_changed")
            return 0L
        }
        val handle = NativeAdBlockEngine.createFromSerializedFile(serializedEngineFile.absolutePath, resourcePath)
        if (handle != 0L) return handle
        invalidateCachedEngine("deserialize_failed")
        return 0L
    }

    private fun persistCachedEngine(handle: Long, signature: String) {
        val temporary = File(cacheDirectory, "$SERIALIZED_ENGINE_FILE_NAME.${android.os.Process.myPid()}.tmp")
        runCatching {
            temporary.delete()
            check(NativeAdBlockEngine.serializeToFile(handle, temporary.absolutePath)) { "native serialization failed" }
            check(temporary.isFile && temporary.length() > 0L) { "serialized engine is empty" }
            replaceCachePayload(temporary)
            writeCacheMetadata(signature)
            CrashDiagnostics.record("adblock_cache_written", "bytes=${serializedEngineFile.length()}")
        }.onFailure { throwable ->
            temporary.delete()
            invalidateCachedEngine("write_failed:${throwable.javaClass.simpleName}")
        }
    }

    private fun replaceCachePayload(temporary: File) {
        if (temporary.renameTo(serializedEngineFile)) return
        serializedEngineFile.delete()
        check(temporary.renameTo(serializedEngineFile)) { "cannot replace serialized engine cache" }
    }

    private fun writeCacheMetadata(signature: String) {
        var output: java.io.FileOutputStream? = null
        try {
            val stream = serializedEngineMetadata.startWrite()
            output = stream
            stream.write("$CACHE_FORMAT_VERSION\n$NATIVE_ENGINE_CACHE_VERSION\n$signature\n".toByteArray(Charsets.UTF_8))
            stream.flush()
            serializedEngineMetadata.finishWrite(stream)
            output = null
        } catch (throwable: Throwable) {
            output?.let(serializedEngineMetadata::failWrite)
            throw throwable
        }
    }

    private fun cacheSignatureMatches(signature: String): Boolean = runCatching {
        serializedEngineMetadata.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            val format = reader.readLine()
            val nativeVersion = reader.readLine()
            val storedSignature = reader.readLine()
            format == CACHE_FORMAT_VERSION && nativeVersion == NATIVE_ENGINE_CACHE_VERSION && storedSignature == signature
        }
    }.getOrDefault(false)

    private fun invalidateCachedEngine(reason: String) {
        serializedEngineFile.delete()
        serializedEngineMetadata.delete()
        CrashDiagnostics.record("adblock_cache_invalidated", "reason=$reason")
    }

    /** APK内の固定リソースを非バックアップ領域へ原子的に展開する。読込失敗時もURL/CSS遮断は使える。 */
    private fun ensureScriptletResourceFileOrNull(): File? = runCatching {
        if (scriptletResourceFile.isFile && sha256(scriptletResourceFile) == BRAVE_RESOURCES_SHA256) return@runCatching scriptletResourceFile
        val temporary = File(cacheDirectory, "$SCRIPTLET_RESOURCE_FILE_NAME.${android.os.Process.myPid()}.tmp")
        temporary.delete()
        appContext.assets.open(SCRIPTLET_RESOURCE_ASSET).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(temporary) == BRAVE_RESOURCES_SHA256) { "scriptlet resource checksum mismatch" }
        if (!temporary.renameTo(scriptletResourceFile)) {
            scriptletResourceFile.delete()
            check(temporary.renameTo(scriptletResourceFile)) { "cannot replace scriptlet resources" }
        }
        CrashDiagnostics.record("adblock_scriptlet_resources_ready", "commit=$BRAVE_RESOURCES_COMMIT\nbytes=${scriptletResourceFile.length()}")
        scriptletResourceFile
    }.onFailure { throwable ->
        CrashDiagnostics.record("adblock_scriptlet_resources_unavailable", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
    }.getOrNull()

    /** フィルタ本文・trust境界・scriptletリソースをハッシュ化し、古いキャッシュを使わない。 */
    private fun contentSignature(files: List<File>, resourceFile: File?, trustedPaths: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_FORMAT_VERSION.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(NATIVE_ENGINE_CACHE_VERSION.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(BRAVE_RESOURCES_COMMIT.toByteArray(Charsets.UTF_8))
        digest.update(0)
        resourceFile?.let { updateDigestFromFile(digest, it) }
        files.forEachIndexed { index, file ->
            digest.update(index.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(if (file.absolutePath in trustedPaths) 1 else 0)
            updateDigestFromFile(digest, file)
        }
        return digest.digest().joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }
    }

    private fun updateDigestFromFile(digest: MessageDigest, file: File) {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(SIGNATURE_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigestFromFile(digest, file)
        return digest.digest().joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }
    }

    private fun countRules(files: List<File>): Pair<Int, Int> {
        var networkCount = 0
        var cosmeticCount = 0
        files.forEach { file ->
            file.useLines { lines -> lines.forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("!") || line.startsWith("[")) return@forEach
                if (line.contains("##") || line.contains("#@#") || line.contains("#%#") || line.contains("#$#")) cosmeticCount++
                else networkCount++
            } }
        }
        return networkCount to cosmeticCount
    }

    fun isReady(): Boolean = activeHandle.get() != 0L

    fun shouldBlock(url: String, documentUrl: String, resourceType: String): Boolean {
        val handle = activeHandle.get()
        if (handle == 0L || !NativeAdBlockEngine.shouldBlock(handle, url, documentUrl, resourceType)) return false
        recordBlockedRequest()
        return true
    }

    /** ページ固有selector・例外・非特権scriptlet情報をJSONで返す。 */
    fun cosmeticResources(url: String): String {
        val handle = activeHandle.get()
        return if (handle == 0L) "{}" else NativeAdBlockEngine.cosmeticJson(handle, url)
    }

    fun documentStartScript(url: String): String = runCatching {
        JSONObject(cosmeticResources(url)).optString("injected_script").trim()
    }.getOrDefault("")

    /** ページ内のclass/idに一致するgeneric cosmetic selectorを、例外規則込みでCSS化する。 */
    fun genericCosmeticCss(classesJson: String, idsJson: String, exceptionsJson: String): String {
        val handle = activeHandle.get()
        return if (handle == 0L) "" else NativeAdBlockEngine.genericCss(handle, classesJson, idsJson, exceptionsJson)
    }

    fun status(): AdBlockStatus {
        resetCounterIfNewDay()
        return AdBlockStatus(
            blockedToday = blockedToday.get(),
            networkRuleCount = networkRuleCount,
            cosmeticRuleCount = cosmeticRuleCount,
            engineReady = activeHandle.get() != 0L && NativeAdBlockEngine.available
        )
    }

    fun close() {
        persistBlockedCount(blockedToday.get())
        val handle = activeHandle.getAndSet(0L)
        compiledFileSignature = null
        if (handle != 0L) NativeAdBlockEngine.destroy(handle)
    }

    private fun readStoredBlockedCount(): Int =
        if (statistics.getString(STATISTICS_DAY_KEY, "") == localDayKey()) statistics.getInt(STATISTICS_COUNT_KEY, 0)
        else {
            statistics.edit().putString(STATISTICS_DAY_KEY, localDayKey()).putInt(STATISTICS_COUNT_KEY, 0).apply()
            0
        }

    private fun recordBlockedRequest() {
        synchronized(statisticsLock) {
            resetCounterIfNewDayLocked()
            val count = blockedToday.incrementAndGet()
            if (count % BLOCKED_COUNT_PERSIST_INTERVAL == 0) persistBlockedCount(count)
        }
    }

    private fun resetCounterIfNewDay() {
        val today = localDayKey()
        if (statisticsDay != today) synchronized(statisticsLock) { resetCounterIfNewDayLocked() }
    }

    private fun resetCounterIfNewDayLocked() {
        val today = localDayKey()
        if (statisticsDay != today) {
            statisticsDay = today
            blockedToday.set(0)
            persistBlockedCount(0)
        }
    }

    private fun persistBlockedCount(count: Int) {
        statistics.edit().putString(STATISTICS_DAY_KEY, statisticsDay).putInt(STATISTICS_COUNT_KEY, count).apply()
    }

    private fun localDayKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private companion object {
        val COMPILATION_LOCK = Any()
        const val STATISTICS_FILE = "adblock_statistics"
        const val COMPILATION_IN_PROGRESS_KEY = "compilation_in_progress"
        const val STATISTICS_DAY_KEY = "day"
        const val STATISTICS_COUNT_KEY = "blocked_count"
        const val BLOCKED_COUNT_PERSIST_INTERVAL = 25
        const val CACHE_DIRECTORY_NAME = "adblock"
        const val SERIALIZED_ENGINE_FILE_NAME = "brave-engine-v1.bin"
        const val SERIALIZED_ENGINE_METADATA_FILE_NAME = "brave-engine-v1.meta"
        const val SCRIPTLET_RESOURCE_ASSET = "adblock_resources/brave_resources.json"
        const val SCRIPTLET_RESOURCE_FILE_NAME = "brave-resources-v1.json"
        const val BRAVE_RESOURCES_COMMIT = "9a0cc4312e155cb5b16b701afc0ab9285dc30f24"
        const val BRAVE_RESOURCES_SHA256 = "dca2802415565b15ceb7288811685d47ddf4bc6b0c4324357ac66e33c1de4948"
        const val CACHE_FORMAT_VERSION = "2"
        const val NATIVE_ENGINE_CACHE_VERSION = "adblock-rust-0.13.2"
        const val SIGNATURE_BUFFER_BYTES = 64 * 1024
    }
}

/** Rust JNI の読み込み失敗時は広告遮断を安全に無効化し、ページ読み込みを壊さない。 */
object NativeAdBlockEngine {
    val available: Boolean = runCatching { System.loadLibrary("https_browser_adblock") }.isSuccess

    fun createFromFiles(paths: List<String>, trustedPaths: List<String>, scriptletResourcePath: String): Long =
        if (available && paths.isNotEmpty()) runCatching {
            nativeCreateFromFiles(paths.toTypedArray(), trustedPaths.toTypedArray(), scriptletResourcePath)
        }.getOrDefault(0L) else 0L

    fun createFromSerializedFile(path: String, scriptletResourcePath: String): Long =
        if (available && path.isNotBlank()) runCatching {
            nativeCreateFromSerializedFile(path, scriptletResourcePath)
        }.getOrDefault(0L) else 0L

    fun serializeToFile(handle: Long, path: String): Boolean =
        available && handle != 0L && path.isNotBlank() && runCatching { nativeSerializeToFile(handle, path) }.getOrDefault(false)
    fun destroy(handle: Long) { if (available) runCatching { nativeDestroy(handle) } }
    fun shouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean =
        available && runCatching { nativeShouldBlock(handle, url, documentUrl, resourceType) }.getOrDefault(false)
    fun cosmeticJson(handle: Long, url: String): String =
        if (available) runCatching { nativeCosmeticJson(handle, url) }.getOrDefault("{}") else "{}"
    fun genericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String =
        if (available) runCatching { nativeGenericCss(handle, classesJson, idsJson, exceptionsJson) }.getOrDefault("") else ""

    @JvmStatic private external fun nativeCreateFromFiles(paths: Array<String>, trustedPaths: Array<String>, scriptletResourcePath: String): Long
    @JvmStatic private external fun nativeCreateFromSerializedFile(path: String, scriptletResourcePath: String): Long
    @JvmStatic private external fun nativeSerializeToFile(handle: Long, path: String): Boolean
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeShouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean
    @JvmStatic private external fun nativeCosmeticJson(handle: Long, url: String): String
    @JvmStatic private external fun nativeGenericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String
}
