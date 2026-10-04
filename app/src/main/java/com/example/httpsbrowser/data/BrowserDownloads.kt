package com.example.httpsbrowser.data

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.work.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BrowserDownloadMode { NORMAL, FAST }

enum class BrowserDownloadStatus {
    ENQUEUED, CONNECTED, RUNNING, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == FAILED || this == CANCELLED
}

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
    val startedAt: Long
)

class BrowserDownloadDispatcher(private val context: Context) {
    private val trackedDownloads = ConcurrentHashMap<String, TrackedDownload>()

    fun enqueueNormal(request: BrowserDownloadRequest): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        trackedDownloads[id] = TrackedDownload(id, request, BrowserDownloadMode.NORMAL, now, null, null, false, "ENQUEUED")
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
        trackedDownloads.remove(id)?.workId?.let { WorkManager.getInstance(context).cancelWorkById(it) }
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
        val cancelled: Boolean = false
    ) {
        fun snapshot() = BrowserDownloadStatus(
            id = id,
            url = request.url,
            mode = mode,
            fileName = request.fileName,
            downloadedBytes = 0L,
            totalBytes = null,
            isSuccessful = false,
            isTerminal = cancelled,
            phase = phase,
            startedAt = createdAt
        )
    }
}

class FastDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        if (!url.startsWith("https://", true)) return Result.failure()
        return try {
            val connection = java.net.URL(url).openConnection().apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", inputData.getString(KEY_USER_AGENT).orEmpty())
                inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            connection.connect()
            val fileName = inputData.getString(KEY_FILE_NAME)?.ifBlank { URLUtil.guessFileName(url, null, inputData.getString(KEY_MIME_TYPE)) }
                ?: URLUtil.guessFileName(url, null, inputData.getString(KEY_MIME_TYPE))
            val target = java.io.File(applicationContext.cacheDir, fileName)
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Result.success(
                workDataOf(
                    OUTPUT_CONTENT_URI to Uri.fromFile(target).toString(),
                    PROGRESS_PHASE to "RUNNING",
                    PROGRESS_TOTAL to target.length(),
                    PROGRESS_DOWNLOADED to target.length()
                )
            )
        } catch (_: Throwable) {
            Result.retry()
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
    }
}
