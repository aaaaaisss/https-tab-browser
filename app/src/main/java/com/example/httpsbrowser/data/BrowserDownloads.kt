package com.example.httpsbrowser.data

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.work.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BrowserDownloadMode { NORMAL, FAST }

data class BrowserDownloadRequest(
    val url: String,
    val userAgent: String,
    val fileName: String,
    val mimeType: String,
    val referer: String = "",
    val title: String = fileName
)

data class BrowserDownloadStatus(
    val id: String,
    val url: String,
    val mode: BrowserDownloadMode,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val isSuccessful: Boolean,
    val isTerminal: Boolean,
    val phase: String,
    val progressFraction: Float = totalBytes?.takeIf { it > 0L }?.let { downloadedBytes.toFloat() / it.toFloat() } ?: 0f,
    val startedAt: Long
)

class BrowserDownloadDispatcher(private val context: Context) {
    private val trackedDownloads = ConcurrentHashMap<String, TrackedDownload>()

    fun enqueueNormal(request: BrowserDownloadRequest): String {
        val id = UUID.randomUUID().toString()
        val dmRequest = DownloadManager.Request(android.net.Uri.parse(request.url)).apply {
            setTitle(request.title)
            setDescription("ねこぶらうざからのダウンロード")
            setMimeType(request.mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)
            addRequestHeader("User-Agent", request.userAgent)
            request.referer.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
            CookieManager.getInstance().getCookie(request.url)?.let { addRequestHeader("Cookie", it) }
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dmId = manager.enqueue(dmRequest)
        trackedDownloads[id] = TrackedDownload(id, request, BrowserDownloadMode.NORMAL, System.currentTimeMillis(), dmId, null, false, "ENQUEUED")
        return id
    }

    fun enqueue(request: BrowserDownloadRequest, mode: BrowserDownloadMode = BrowserDownloadMode.NORMAL): String =
        if (mode == BrowserDownloadMode.FAST) enqueueFast(request) else enqueueNormal(request)

    fun enqueueFast(request: BrowserDownloadRequest): String {
        val id = UUID.randomUUID().toString()
        val work = OneTimeWorkRequestBuilder<FastDownloadWorker>()
            .setInputData(
                workDataOf(
                    FastDownloadWorker.KEY_URL to request.url,
                    FastDownloadWorker.KEY_USER_AGENT to request.userAgent,
                    FastDownloadWorker.KEY_FILE_NAME to request.fileName,
                    FastDownloadWorker.KEY_MIME_TYPE to request.mimeType,
                    FastDownloadWorker.KEY_REFERER to request.referer,
                    FastDownloadWorker.KEY_TRACKING_ID to id
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, work)
        trackedDownloads[id] = TrackedDownload(id, request, BrowserDownloadMode.FAST, System.currentTimeMillis(), null, work.id, false, "ENQUEUED")
        return id
    }

    fun switchToNormal(id: String) {
        val tracked = trackedDownloads[id] ?: return
        if (tracked.mode == BrowserDownloadMode.NORMAL) return
        WorkManager.getInstance(context).cancelWorkById(tracked.workId ?: return)
        trackedDownloads[id] = tracked.copy(mode = BrowserDownloadMode.NORMAL, fallbackToNormal = true, phase = "NORMAL")
    }

    suspend fun currentStatuses(): List<BrowserDownloadStatus> = trackedDownloads.values
        .sortedByDescending { it.createdAt }
        .map { it.snapshot() }

    fun cancel(id: String) {
        trackedDownloads[id]?.let {
            it.workId?.let { workId -> WorkManager.getInstance(context).cancelWorkById(workId) }
            trackedDownloads[id] = it.copy(cancelled = true, phase = "CANCELLED")
        }
    }

    fun delete(id: String) {
        trackedDownloads[id]?.let { tracked ->
            tracked.workId?.let { WorkManager.getInstance(context).cancelWorkById(it) }
            tracked.downloadManagerId?.let { idValue ->
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(idValue)
            }
            trackedDownloads[id] = tracked.copy(deleted = true, phase = "DELETED")
        }
    }

    fun trackedDownloads(): List<BrowserDownloadStatus> =
        trackedDownloads.values.sortedByDescending { it.createdAt }.map { it.snapshot() }

    fun close() = Unit

    private data class TrackedDownload(
        val id: String,
        val request: BrowserDownloadRequest,
        val mode: BrowserDownloadMode,
        val createdAt: Long,
        val downloadManagerId: Long?,
        val workId: UUID?,
        val fallbackToNormal: Boolean,
        val phase: String,
        val cancelled: Boolean = false,
        val deleted: Boolean = false
    ) {
        fun snapshot() = BrowserDownloadStatus(
            id = id,
            url = request.url,
            mode = mode,
            fileName = request.fileName,
            downloadedBytes = 0L,
            totalBytes = null,
            isSuccessful = false,
            isTerminal = cancelled || deleted,
            phase = phase,
            progressFraction = 0f,
            startedAt = createdAt
        )
    }
}

class FastDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private data class RangeProbe(val totalBytes: Long, val rangeSupported: Boolean)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        if (!url.startsWith("https://", true)) return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME)?.ifBlank {
            URLUtil.guessFileName(url, null, inputData.getString(KEY_MIME_TYPE))
        } ?: URLUtil.guessFileName(url, null, inputData.getString(KEY_MIME_TYPE))

        return try {
            val probe = probe(url)
            if (probe.totalBytes >= MIN_PARALLEL_BYTES && probe.rangeSupported) {
                downloadParallel(url, fileName, probe.totalBytes)
            } else {
                downloadNormal(url, fileName)
            }
        } catch (_: Throwable) {
            if (runAttemptCount + 1 >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private fun headers(connection: java.net.HttpURLConnection) {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", inputData.getString(KEY_USER_AGENT).orEmpty())
        inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
        CookieManager.getInstance().getCookie(inputData.getString(KEY_URL).orEmpty())?.let { connection.setRequestProperty("Cookie", it) }
    }

    private fun probe(url: String): RangeProbe {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        headers(connection)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.connect()
        val length = connection.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
            ?: connection.getHeaderFieldLong("Content-Length", -1L)
        val supported = connection.responseCode == java.net.HttpURLConnection.HTTP_PARTIAL &&
            connection.getHeaderField("Content-Range")?.startsWith("bytes ") == true
        connection.disconnect()
        return RangeProbe(length.coerceAtLeast(0L), supported)
    }

    private suspend fun downloadNormal(url: String, fileName: String): Result {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        headers(connection)
        connection.connect()
        if (connection.responseCode !in 200..299) return Result.retry()
        val target = java.io.File(applicationContext.cacheDir, fileName).also { it.parentFile?.mkdirs() }
        var downloaded = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    setProgress(workDataOf(PROGRESS_PHASE to "RUNNING", PROGRESS_DOWNLOADED to downloaded,
                        PROGRESS_TOTAL to connection.contentLengthLong))
                }
            }
        }
        connection.disconnect()
        return publishToDownloads(target, fileName)
    }

    private suspend fun downloadParallel(url: String, fileName: String, total: Long): Result {
        val directory = java.io.File(applicationContext.cacheDir, "fast_downloads").apply { mkdirs() }
        val parts = splitRanges(total)
        val files = parts.mapIndexed { index, range -> java.io.File(directory, "${inputData.getString(KEY_TRACKING_ID)}_${index}.part") }
        try {
            kotlinx.coroutines.coroutineScope {
                parts.mapIndexed { index, range ->
                    kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) {
                        downloadRange(url, range, files[index])
                    }
                }.forEach { it.await() }
            }
            val target = java.io.File(directory, fileName)
            target.outputStream().use { output ->
                files.forEach { part -> part.inputStream().use { it.copyTo(output) } }
            }
            return publishToDownloads(target, fileName)
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun splitRanges(total: Long): List<LongRange> {
        val count = PARALLEL_CONNECTIONS.coerceAtMost((total / MIN_PARALLEL_BYTES).toInt().coerceAtLeast(1))
        val chunk = total / count
        return (0 until count).map { index ->
            val start = index * chunk
            val end = if (index == count - 1) total - 1 else (start + chunk - 1)
            start..end
        }
    }

    private fun downloadRange(url: String, range: LongRange, target: java.io.File) {
        var lastError: Throwable? = null
        repeat(MAX_RETRIES) {
            try {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                headers(connection)
                connection.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
                connection.connect()
                if (connection.responseCode != java.net.HttpURLConnection.HTTP_PARTIAL) {
                    connection.disconnect()
                    throw java.io.IOException("Range request rejected: ${connection.responseCode}")
                }
                connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                connection.disconnect()
                return
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("range download failed")
    }

    private fun publishToDownloads(file: java.io.File, fileName: String): Result {
        val resolver = applicationContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, inputData.getString(KEY_MIME_TYPE).orEmpty().ifBlank { "application/octet-stream" })
            if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return Result.failure()
        return try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: return Result.failure()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Result.success(workDataOf(
                OUTPUT_CONTENT_URI to uri.toString(),
                PROGRESS_PHASE to "COMPLETED",
                PROGRESS_DOWNLOADED to file.length(),
                PROGRESS_TOTAL to file.length()
            ))
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            Result.failure()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_REFERER = "referer"
        const val KEY_TRACKING_ID = "tracking_id"
        const val OUTPUT_CONTENT_URI = "output_content_uri"
        const val PROGRESS_PHASE = "progress_phase"
        const val PROGRESS_DOWNLOADED = "progress_downloaded"
        const val PROGRESS_TOTAL = "progress_total"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MIN_PARALLEL_BYTES = 2L * 1024L * 1024L
        const val PARALLEL_CONNECTIONS = 4
        const val MAX_RETRIES = 3
        const val MAX_TRACKED_DOWNLOADS = 50
    }
}
