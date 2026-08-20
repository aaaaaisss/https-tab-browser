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
 * リスト構文の解釈はネイティブエンジンに任せ、Kotlin 側で ABP 規則を再実装しない。
 */
class BraveAdBlockEngine(context: Context) {
    private val appContext = context.applicationContext
    private val statistics: SharedPreferences = appContext
        .getSharedPreferences(STATISTICS_FILE, Context.MODE_PRIVATE)
    private val cacheDirectory = File(appContext.noBackupFilesDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }
    private val serializedEngineFile = File(cacheDirectory, SERIALIZED_ENGINE_FILE_NAME)
    private val serializedEngineMetadata = AtomicFile(File(cacheDirectory, SERIALIZED_ENGINE_METADATA_FILE_NAME))
    private val blockedToday = AtomicInteger(readStoredBlockedCount())
    private val statisticsLock = Any()
    @Volatile private var statisticsDay = localDayKey()
    private val activeHandle = AtomicLong(0L)

    @Volatile private var networkRuleCount = 0
    @Volatile private var cosmeticRuleCount = 0
    @Volatile private var compiledFileSignature: String? = null

    /**
     * ルール本文をKotlin/JNIの巨大なStringとして二重・三重に複製しない。
     * 同一本文・同一エンジン版では直列化済みEngineを復元し、初回・更新・破損時だけ再コンパイルする。
     */
    fun replaceRuleFiles(files: List<File>) {
        val existingFiles = files.filter { it.isFile && it.length() > 0L }
        if (existingFiles.isEmpty()) {
            CrashDiagnostics.record("adblock_compile_skipped", "reason=no_filter_files")
            return
        }
        val signature = contentSignature(existingFiles)
        if (activeHandle.get() != 0L && compiledFileSignature == signature) {
            // 設定画面の再表示などで同じ4リストを再コンパイルしない。
            return
        }
        if (statistics.getBoolean(COMPILATION_IN_PROGRESS_KEY, false)) {
            CrashDiagnostics.record("adblock_compile_skipped", "reason=previous_compile_did_not_finish\nfiles=${existingFiles.size}")
            return
        }
        synchronized(COMPILATION_LOCK) {
            if (statistics.getBoolean(COMPILATION_IN_PROGRESS_KEY, false)) return
            // commit() で先に確定する。ネイティブコンパイル中にOSがプロセスを終了しても、
            // 次回起動で同じ重い処理を繰り返さず、ブラウザ本体を確実に起動できる。
            statistics.edit().putBoolean(COMPILATION_IN_PROGRESS_KEY, true).commit()
            val totalBytes = existingFiles.sumOf(File::length)
            CrashDiagnostics.record("adblock_engine_prepare_started", "files=${existingFiles.size}\nbytes=$totalBytes")
            try {
                // 更新時に旧Engineと新Engineを同時保持すると、最大メモリ使用量がほぼ二倍になる。
                // 先に旧Engineを解放し、短時間の遮断停止よりプロセス生存を優先する。
                val previous = activeHandle.getAndSet(0L)
                if (previous != 0L) NativeAdBlockEngine.destroy(previous)
                compiledFileSignature = null

                val cachedHandle = restoreCachedEngine(signature)
                val newHandle = if (cachedHandle != 0L) {
                    CrashDiagnostics.record("adblock_cache_restored", "files=${existingFiles.size}\nbytes=$totalBytes")
                    cachedHandle
                } else {
                    CrashDiagnostics.record("adblock_compile_started", "files=${existingFiles.size}\nbytes=$totalBytes")
                    NativeAdBlockEngine.createFromFiles(existingFiles.map(File::getAbsolutePath))
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
                    "source=${if (cachedHandle != 0L) "cache" else "compile"}\nnetworkRules=$networkRuleCount\ncosmeticRules=$cosmeticRuleCount\nfiles=${existingFiles.size}"
                )
            } catch (throwable: Throwable) {
                // Java側の例外はページ表示へ波及させず、次回の診断画面から原因を取得できるようにする。
                CrashDiagnostics.record("adblock_engine_exception", "${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            } finally {
                // 通常の失敗では安全モードを残さず、ネイティブ異常終了だけを次回起動で検知する。
                statistics.edit().remove(COMPILATION_IN_PROGRESS_KEY).commit()
            }
        }
    }

    /** 同一リスト本文・同一キャッシュ形式・同一ネイティブ版だけを復元候補にする。 */
    private fun restoreCachedEngine(signature: String): Long {
        if (!serializedEngineFile.isFile || !cacheSignatureMatches(signature)) {
            CrashDiagnostics.record("adblock_cache_miss", "reason=missing_or_signature_changed")
            return 0L
        }
        val handle = NativeAdBlockEngine.createFromSerializedFile(serializedEngineFile.absolutePath)
        if (handle != 0L) return handle
        invalidateCachedEngine("deserialize_failed")
        return 0L
    }

    /**
     * Rust側でシリアライズ結果を同一ディレクトリの一時ファイルへ書く。
     * 本体を先に原子的に差し替え、最後にAtomicFileのメタデータを書いてキャッシュを確定する。
     * 中断時は署名不一致またはメタデータ欠損として再コンパイルされる。
     */
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
            // 不完全なペイロードを次回に読ませない。次回は確実に再コンパイルする。
            invalidateCachedEngine("write_failed:${throwable.javaClass.simpleName}")
        }
    }

    private fun replaceCachePayload(temporary: File) {
        // 同じ内部ストレージ上のrenameは原子的に置換される。失敗時は既存ペイロードを削除してから
        // 置換を試み、最後まで失敗した場合は例外でキャッシュを無効化する。
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

    /** フィルタ本文そのものをハッシュ化するため、更新日時やファイル名だけでは古いキャッシュを使わない。 */
    private fun contentSignature(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_FORMAT_VERSION.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(NATIVE_ENGINE_CACHE_VERSION.toByteArray(Charsets.UTF_8))
        files.forEachIndexed { index, file ->
            digest.update(index.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(SIGNATURE_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
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

    /** ページ固有のselector・例外・scriptlet情報をJSONで返す。scriptletはWebView側で注入しない。 */
    fun cosmeticResources(url: String): String {
        val handle = activeHandle.get()
        return if (handle == 0L) "{}" else NativeAdBlockEngine.cosmeticJson(handle, url)
    }

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
        // ページの各リソース遮断ごとではなく、終了時にも統計を確実に保存する。
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
            // shouldInterceptRequest はページ内の多数リソースから呼ばれる。遮断のたびに
            // SharedPreferencesへ書き込むと、YouTube等でI/OとGCを不必要に増やす。
            // 表示用のカウンタは即時に更新し、永続化は一定件数ごとと終了時だけにする。
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
        /** WorkManagerと画面起動が重なっても、巨大なエンジンを複数同時に生成しない。 */
        val COMPILATION_LOCK = Any()
        const val STATISTICS_FILE = "adblock_statistics"
        const val COMPILATION_IN_PROGRESS_KEY = "compilation_in_progress"
        const val STATISTICS_DAY_KEY = "day"
        const val STATISTICS_COUNT_KEY = "blocked_count"
        const val BLOCKED_COUNT_PERSIST_INTERVAL = 25
        const val CACHE_DIRECTORY_NAME = "adblock"
        const val SERIALIZED_ENGINE_FILE_NAME = "brave-engine-v1.bin"
        const val SERIALIZED_ENGINE_METADATA_FILE_NAME = "brave-engine-v1.meta"
        const val CACHE_FORMAT_VERSION = "1"
        // adblock-rustのminor versionを更新したら必ず変更する。バイナリ互換の誤用を防ぐ。
        const val NATIVE_ENGINE_CACHE_VERSION = "adblock-rust-0.13.2"
        const val SIGNATURE_BUFFER_BYTES = 64 * 1024
    }
}

/** Rust JNI の読み込み失敗時は広告遮断を安全に無効化し、ページ読み込みを壊さない。 */
object NativeAdBlockEngine {
    val available: Boolean = runCatching { System.loadLibrary("https_browser_adblock") }.isSuccess

    fun createFromFiles(paths: List<String>): Long =
        if (available && paths.isNotEmpty()) runCatching { nativeCreateFromFiles(paths.toTypedArray()) }.getOrDefault(0L) else 0L
    fun createFromSerializedFile(path: String): Long =
        if (available && path.isNotBlank()) runCatching { nativeCreateFromSerializedFile(path) }.getOrDefault(0L) else 0L
    fun serializeToFile(handle: Long, path: String): Boolean =
        available && handle != 0L && path.isNotBlank() && runCatching { nativeSerializeToFile(handle, path) }.getOrDefault(false)
    fun destroy(handle: Long) { if (available) runCatching { nativeDestroy(handle) } }
    fun shouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean =
        available && runCatching { nativeShouldBlock(handle, url, documentUrl, resourceType) }.getOrDefault(false)
    fun cosmeticJson(handle: Long, url: String): String =
        if (available) runCatching { nativeCosmeticJson(handle, url) }.getOrDefault("{}") else "{}"
    fun genericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String =
        if (available) runCatching { nativeGenericCss(handle, classesJson, idsJson, exceptionsJson) }.getOrDefault("") else ""

    @JvmStatic private external fun nativeCreateFromFiles(paths: Array<String>): Long
    @JvmStatic private external fun nativeCreateFromSerializedFile(path: String): Long
    @JvmStatic private external fun nativeSerializeToFile(handle: Long, path: String): Boolean
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeShouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean
    @JvmStatic private external fun nativeCosmeticJson(handle: Long, url: String): String
    @JvmStatic private external fun nativeGenericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String
}
