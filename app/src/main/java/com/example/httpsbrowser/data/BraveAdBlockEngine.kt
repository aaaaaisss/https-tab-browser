package com.example.httpsbrowser.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Brave adblock-rust を Android JNI 経由で利用するフィルタ実行器。
 * リスト構文の解釈はネイティブエンジンに任せ、Kotlin 側で ABP 規則を再実装しない。
 */
class BraveAdBlockEngine(context: Context) {
    private val statistics: SharedPreferences = context.applicationContext
        .getSharedPreferences(STATISTICS_FILE, Context.MODE_PRIVATE)
    private val blockedToday = AtomicInteger(readStoredBlockedCount())
    private val statisticsLock = Any()
    private val activeHandle = AtomicLong(0L)

    @Volatile private var networkRuleCount = 0
    @Volatile private var cosmeticRuleCount = 0

    fun replaceRules(lines: Sequence<String>) {
        val joinedRules = StringBuilder()
        var networkCount = 0
        var cosmeticCount = 0
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[")) return@forEach
            joinedRules.append(line).append('\n')
            if (line.contains("##") || line.contains("#@#") || line.contains("#%#") || line.contains("#$#")) cosmeticCount++
            else networkCount++
        }
        val newHandle = NativeAdBlockEngine.create(joinedRules.toString())
        if (newHandle == 0L) return
        val previous = activeHandle.getAndSet(newHandle)
        if (previous != 0L) NativeAdBlockEngine.destroy(previous)
        networkRuleCount = networkCount
        cosmeticRuleCount = cosmeticCount
    }

    fun shouldBlock(url: String, documentUrl: String, resourceType: String): Boolean {
        val handle = activeHandle.get()
        if (handle == 0L || !NativeAdBlockEngine.shouldBlock(handle, url, documentUrl, resourceType)) return false
        recordBlockedRequest()
        return true
    }

    /** ページ固有の selector・例外・scriptlet 情報を JSON で返す。scriptlet は WebView 側で注入しない。 */
    fun cosmeticResources(url: String): String {
        val handle = activeHandle.get()
        return if (handle == 0L) "{}" else NativeAdBlockEngine.cosmeticJson(handle, url)
    }

    /** ページ内の class/id に一致する generic cosmetic selector を、例外規則込みで CSS 化する。 */
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
        val handle = activeHandle.getAndSet(0L)
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
            resetCounterIfNewDay()
            val count = blockedToday.incrementAndGet()
            statistics.edit().putString(STATISTICS_DAY_KEY, localDayKey()).putInt(STATISTICS_COUNT_KEY, count).apply()
        }
    }

    private fun resetCounterIfNewDay() {
        val today = localDayKey()
        if (statistics.getString(STATISTICS_DAY_KEY, "") != today) {
            synchronized(statisticsLock) {
                if (statistics.getString(STATISTICS_DAY_KEY, "") != today) {
                    blockedToday.set(0)
                    statistics.edit().putString(STATISTICS_DAY_KEY, today).putInt(STATISTICS_COUNT_KEY, 0).apply()
                }
            }
        }
    }

    private fun localDayKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private companion object {
        const val STATISTICS_FILE = "adblock_statistics"
        const val STATISTICS_DAY_KEY = "day"
        const val STATISTICS_COUNT_KEY = "blocked_count"
    }
}

/** Rust JNI の読み込み失敗時は広告遮断を安全に無効化し、ページ読み込みを壊さない。 */
object NativeAdBlockEngine {
    val available: Boolean = runCatching { System.loadLibrary("https_browser_adblock") }.isSuccess

    fun create(rules: String): Long = if (available) nativeCreate(rules) else 0L
    fun destroy(handle: Long) { if (available) nativeDestroy(handle) }
    fun shouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean =
        available && nativeShouldBlock(handle, url, documentUrl, resourceType)
    fun cosmeticJson(handle: Long, url: String): String = if (available) nativeCosmeticJson(handle, url) else "{}"
    fun genericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String =
        if (available) nativeGenericCss(handle, classesJson, idsJson, exceptionsJson) else ""

    @JvmStatic private external fun nativeCreate(rules: String): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeShouldBlock(handle: Long, url: String, documentUrl: String, resourceType: String): Boolean
    @JvmStatic private external fun nativeCosmeticJson(handle: Long, url: String): String
    @JvmStatic private external fun nativeGenericCss(handle: Long, classesJson: String, idsJson: String, exceptionsJson: String): String
}
