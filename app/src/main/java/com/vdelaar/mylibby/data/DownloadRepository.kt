package com.vdelaar.mylibby.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.DownloadEntity
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.notifications.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadRepository(
    private val context: Context,
    private val api: ApiProvider,
    private val db: AppDatabase,
    private val settings: com.vdelaar.mylibby.core.datastore.SettingsRepository,
) {
    private val offlineDir get() = File(context.filesDir, "books").apply { mkdirs() }
    private val cacheDir get() = File(context.cacheDir, "books").apply { mkdirs() }

    fun observe(bookId: Long): Flow<DownloadEntity?> = db.downloads().observe(bookId)
    fun observeAll(): Flow<List<DownloadEntity>> = db.downloads().observeAll()

    suspend fun enqueue(book: Book, unmeteredOnly: Boolean = false) = withContext(Dispatchers.IO) {
        if (book.id in settings.app.value.autoDownloadExcluded) {
            settings.updateApp { it.copy(autoDownloadExcluded = it.autoDownloadExcluded - book.id) }
        }
        val type = (book.primaryFileType ?: "EPUB").uppercase()
        db.downloads().upsert(DownloadEntity(book.id, book.title, null, type, state = DownloadState.QUEUED))
        val network = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_BOOK_ID to book.id, DownloadWorker.KEY_TYPE to type, DownloadWorker.KEY_TITLE to book.title))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-${book.id}", ExistingWorkPolicy.KEEP, request)
    }

    /** Removes a downloaded book; with "Download all books" on, it won't be fetched again. */
    suspend fun remove(bookId: Long) = withContext(Dispatchers.IO) {
        if (settings.app.value.autoDownloadAll) {
            settings.updateApp { it.copy(autoDownloadExcluded = it.autoDownloadExcluded + bookId) }
        }
        WorkManager.getInstance(context).cancelUniqueWork("download-$bookId")
        db.downloads().get(bookId)?.filePath?.let { File(it).delete() }
        db.downloads().delete(bookId)
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        offlineDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun clearStreamCache() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Returns a local file for reading: the offline copy if downloaded, otherwise the book is
     * fetched into the cache directory (kept for quick re-opening).
     */
    suspend fun readableFile(book: Book, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        book.localPath?.let { return@withContext File(it) }
        db.downloads().get(book.id)?.takeIf { it.state == DownloadState.DONE }?.filePath?.let { path ->
            File(path).takeIf { it.exists() && it.length() > 0 }?.let { return@withContext it }
        }
        val type = (book.primaryFileType ?: "EPUB").uppercase()
        val target = File(cacheDir, "${book.id}.${extensionFor(type)}")
        if (target.exists() && target.length() > 0) return@withContext target
        fetch(api, book.id, type, target, onProgress)
        target
    }

    companion object {
        const val TAG = "downloads"

        fun extensionFor(type: String): String = when (type.uppercase()) {
            "MOBI" -> "mobi"
            "AZW3", "AZW" -> "azw3"
            "FB2" -> "fb2"
            "CBZ", "CBX" -> "cbz"
            "PDF" -> "pdf"
            else -> "epub"
        }

        suspend fun fetch(api: ApiProvider, bookId: Long, type: String, target: File, onProgress: (Float) -> Unit) {
            val response = api.grimmory().content(bookId, type)
            if (!response.isSuccessful) throw IOException(com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.err_download_http, response.code()))
            val body = response.body() ?: throw IOException(com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.err_empty_response))
            val total = body.contentLength()
            val tmp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastReport = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            onProgress(1f)
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MyLibbyApp
        val container = app.container
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        val type = inputData.getString(KEY_TYPE) ?: "EPUB"
        val title = inputData.getString(KEY_TITLE) ?: "Book"
        if (bookId < 0) return Result.failure()
        val db = container.db
        val dir = File(applicationContext.filesDir, "books").apply { mkdirs() }
        val target = File(dir, "$bookId.${DownloadRepository.extensionFor(type)}")

        runCatching { setForeground(foregroundInfo(title, 0f)) }
        db.downloads().upsert(DownloadEntity(bookId, title, null, type, state = DownloadState.DOWNLOADING))
        return try {
            // Reuse a copy already streamed to the cache for reading.
            val cached = File(File(applicationContext.cacheDir, "books"), target.name)
            if (cached.exists() && cached.length() > 0) {
                cached.copyTo(target, overwrite = true)
            } else {
                DownloadRepository.fetch(container.api, bookId, type, target) { p ->
                    kotlinx.coroutines.runBlocking { db.downloads().updateProgress(bookId, p, DownloadState.DOWNLOADING) }
                    runCatching { setForegroundAsync(foregroundInfo(title, p)) }
                }
            }
            db.downloads().upsert(
                DownloadEntity(bookId, title, target.absolutePath, type, target.length(), 1f, DownloadState.DONE, downloadedAt = System.currentTimeMillis())
            )
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                db.downloads().upsert(DownloadEntity(bookId, title, null, type, state = DownloadState.QUEUED, error = e.message))
                Result.retry()
            } else {
                db.downloads().upsert(DownloadEntity(bookId, title, null, type, state = DownloadState.FAILED, error = e.message))
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_TITLE) ?: "Book", 0f)

    private fun foregroundInfo(title: String, progress: Float): ForegroundInfo {
        val id = 5000 + (inputData.getLong(KEY_BOOK_ID, 0) % 1000).toInt()
        return ForegroundInfo(
            id,
            Notifications.downloadProgress(applicationContext, title, progress),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TYPE = "type"
        const val KEY_TITLE = "title"
    }
}
