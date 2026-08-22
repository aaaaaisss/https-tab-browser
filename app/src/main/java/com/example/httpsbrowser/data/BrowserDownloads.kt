package com.example.httpsbrowser.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.pm.ServiceInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi

/** WebViewのDownloadListenerからUIへ渡す、保存に必要な最小限の直接ダウンロード情報。 */
data class BrowserDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val userAgent: String,
    val cookie: String?,
    val referer: String?
)

enum class BrowserDownloadMode { NORMAL, HIGH }

/**
 * 通常はOS DownloadManagerへ委譲する。高速はHTTP Range対応を検査した後だけ4分割並列にする。
 * 高速化できないURLはWorker内で必ず通常経路へ戻すため、互換性を失わない。
 */
object BrowserDownloadDispatcher {
    private const val FAST_WORK_PREFIX = "neko_fast_download_"

    fun start(context: Context, request: BrowserDownloadRequest, mode: BrowserDownloadMode): String {
        if (mode == BrowserDownloadMode.HIGH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val work = OneTimeWorkRequestBuilder<FastDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(FastDownloadWorker.inputData(request))
                .build()
            val uniqueName = FAST_WORK_PREFIX + request.url.sha256Prefix()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                work
            )
            return "高速ダウンロードを準備しています: ${request.fileName}"
        }
        enqueueNormal(context, request)
        return "ダウンロードを開始しました: ${request.fileName}"
    }

    fun enqueueNormal(context: Context, request: BrowserDownloadRequest) {
        val downloadRequest = DownloadManager.Request(Uri.parse(request.url)).apply {
            request.mimeType.takeIf { it.isNotBlank() }?.let(::setMimeType)
            setTitle(request.fileName)
            setDescription("ねこぶらうざからのダウンロード")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)
            addRequestHeader("User-Agent", request.userAgent)
            request.cookie?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
            request.referer?.takeIf { it.startsWith("https://") }?.let { addRequestHeader("Referer", it) }
        }
        (context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(downloadRequest)
    }

    private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
}

/**
 * HTTPSのRange対応ファイルだけを4本に分けてダウンロードする。認証・Range・容量条件が満たせない
 * 場合は成功扱いでDownloadManagerへ引き継ぐ。失敗しても部分ファイルや未完成の公開Downloadsを残さない。
 */
private object FastDownloadNotifications {
    private const val CHANNEL_ID = "fast_downloads"
    private const val NOTIFICATION_ID = 4217

    fun foregroundInfo(context: Context, fileName: String): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "高速ダウンロード", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("高速ダウンロード中")
            .setContentText(fileName)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

class FastDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val request = requestFromInput() ?: return@withContext Result.failure()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            BrowserDownloadDispatcher.enqueueNormal(applicationContext, request)
            return@withContext Result.success()
        }

        val probe = runCatching { probeRange(request) }.getOrNull()
        if (probe == null || probe.totalBytes < MIN_PARALLEL_BYTES) {
            BrowserDownloadDispatcher.enqueueNormal(applicationContext, request)
            return@withContext Result.success()
        }

        val parts = mutableListOf<File>()
        try {
            setForeground(createForegroundInfo(request.fileName))
            val ranges = splitRanges(probe.totalBytes)
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        val part = File(applicationContext.cacheDir, "neko-fast-${id}-$index.part")
                        downloadRange(request, range, part)
                        synchronized(parts) { parts += part }
                    }
                }.awaitAll()
            }
            if (parts.size != PARALLEL_CONNECTIONS) error("高速ダウンロードの分割結果が不足しています。")
            publishToDownloads(request, parts.sortedBy { it.name })
            Result.success()
        } catch (_: Throwable) {
            // 一時partを残さず、次回の通常経路を妨げない。ネットワーク揺らぎはWorkManagerに再試行させる。
            parts.forEach { runCatching { it.delete() } }
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = withContext(Dispatchers.IO) {
        val name = inputData.getString(KEY_FILE_NAME) ?: "ダウンロード"
        createForegroundInfo(name)
    }

    private fun createForegroundInfo(fileName: String): ForegroundInfo =
        FastDownloadNotifications.foregroundInfo(applicationContext, fileName)

    private fun probeRange(request: BrowserDownloadRequest): RangeProbe {
        val connection = openConnection(request, "bytes=0-0")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return error("Range未対応")
            val contentRange = connection.getHeaderField("Content-Range") ?: return error("Content-Rangeなし")
            val total = contentRange.substringAfter('/').toLongOrNull() ?: return error("総サイズ不明")
            if (total <= 0L) return error("不正な総サイズ")
            return RangeProbe(total)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadRange(request: BrowserDownloadRequest, range: LongRange, target: File) {
        val connection = openConnection(request, "bytes=${range.first}-${range.last}")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) error("分割Rangeが拒否されました。")
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(target.outputStream()).use { output -> input.copyTo(output) }
            }
            if (target.length() != range.last - range.first + 1L) error("分割Rangeの長さが一致しません。")
        } finally {
            connection.disconnect()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(request: BrowserDownloadRequest, parts: List<File>) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, request.fileName)
            put(MediaStore.Downloads.MIME_TYPE, request.mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Downloads保存先を作成できませんでした。")
        try {
            resolver.openOutputStream(destination)?.use { output ->
                BufferedOutputStream(output).use { buffered ->
                    parts.forEach { part -> part.inputStream().use { it.copyTo(buffered) } }
                }
            } ?: error("Downloads保存先を開けませんでした。")
            resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (throwable: Throwable) {
            resolver.delete(destination, null, null)
            throw throwable
        } finally {
            parts.forEach { runCatching { it.delete() } }
        }
    }

    private fun openConnection(request: BrowserDownloadRequest, range: String): HttpURLConnection =
        (URL(request.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Range", range)
            setRequestProperty("User-Agent", request.userAgent)
            request.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            request.referer?.takeIf { it.startsWith("https://") }?.let { setRequestProperty("Referer", it) }
        }

    private fun splitRanges(totalBytes: Long): List<LongRange> = List(PARALLEL_CONNECTIONS) { index ->
        val start = totalBytes * index / PARALLEL_CONNECTIONS
        val end = totalBytes * (index + 1) / PARALLEL_CONNECTIONS - 1L
        start..end
    }

    private fun requestFromInput(): BrowserDownloadRequest? {
        val url = inputData.getString(KEY_URL) ?: return null
        val fileName = inputData.getString(KEY_FILE_NAME) ?: URLUtil.guessFileName(url, null, null)
        return BrowserDownloadRequest(
            url = url,
            fileName = fileName,
            mimeType = inputData.getString(KEY_MIME_TYPE).orEmpty(),
            userAgent = inputData.getString(KEY_USER_AGENT).orEmpty(),
            cookie = inputData.getString(KEY_COOKIE),
            referer = inputData.getString(KEY_REFERER)
        )
    }

    private data class RangeProbe(val totalBytes: Long)

    companion object {
        private const val PARALLEL_CONNECTIONS = 4
        private const val MIN_PARALLEL_BYTES = 2L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RETRIES = 2
        private const val KEY_URL = "url"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_MIME_TYPE = "mimeType"
        private const val KEY_USER_AGENT = "userAgent"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_REFERER = "referer"

        fun inputData(request: BrowserDownloadRequest): Data = Data.Builder()
            .putString(KEY_URL, request.url)
            .putString(KEY_FILE_NAME, request.fileName)
            .putString(KEY_MIME_TYPE, request.mimeType)
            .putString(KEY_USER_AGENT, request.userAgent)
            .putString(KEY_COOKIE, request.cookie)
            .putString(KEY_REFERER, request.referer)
            .build()
    }
}
