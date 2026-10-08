package com.vdelaar.mylibby.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.datastore.AppState
import com.vdelaar.mylibby.core.model.toBook
import java.util.concurrent.TimeUnit

/** "Download all books": keeps the whole library on the device, including new books. */
object AutoDownload {
    private const val PERIODIC = "auto-download-all"
    private const val NOW = "auto-download-now"

    fun apply(context: Context, app: AppState) {
        val wm = WorkManager.getInstance(context)
        if (!app.autoDownloadAll) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NOW)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (app.autoDownloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(3, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        wm.enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoDownloadWorker>().setConstraints(constraints).build(),
        )
    }
}

/** Finds library books that aren't on the device yet and queues them for download. */
class AutoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MyLibbyApp).container
        val app = c.settings.app.value
        if (!app.autoDownloadAll || c.tokens.accessToken == null) return Result.success()
        val present = c.db.downloads().all()
            .filter { it.state != DownloadState.FAILED }
            .map { it.bookId }
            .toSet()
        return try {
            var page = 0
            var queued = 0
            do {
                val res = c.api.grimmory().books(page = page, size = 100, sort = "addedOn", dir = "desc")
                val books = res.content.map { it.toBook() }
                c.library.cache(books)
                for (b in books) {
                    if (b.isReadable && b.id !in present && b.id !in app.autoDownloadExcluded) {
                        c.downloads.enqueue(b, unmeteredOnly = app.autoDownloadWifiOnly)
                        queued++
                    }
                }
                page++
            } while (res.hasNext && page < 200)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
