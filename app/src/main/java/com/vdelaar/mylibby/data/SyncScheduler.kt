package com.vdelaar.mylibby.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.core.database.OutboxType
import com.vdelaar.mylibby.core.network.PersonalRatingRequest
import com.vdelaar.mylibby.core.network.ShelvesAssignmentRequest
import com.vdelaar.mylibby.core.network.UpdateStatusRequest
import java.util.concurrent.TimeUnit

class SyncScheduler(private val context: Context) {

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Push pending local changes as soon as there is a network connection. */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic() {
        // The periodic run is the full one (book list and covers too); UPDATE replaces an older schedule without this flag.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(FULL to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        private const val ONE_OFF = "sync-now"
        private const val PERIODIC = "sync-periodic"
        const val FULL = "full"
    }
}

/** Pushes the outbox, progress, sessions, highlights and bookmarks to Grimmory. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as MyLibbyApp).container
        if (c.tokens.accessToken == null) return Result.success()
        val failed = if (inputData.getBoolean(SyncScheduler.FULL, false)) {
            c.librarySync.run(full = true)
        } else {
            pushPendingChanges(c).also { if (runAttemptCount == 0) c.library.refreshFavourites() }
        }
        return if (failed && runAttemptCount < 5) Result.retry() else Result.success()
    }
}

/** Pushes everything that is waiting to be synced. Returns true when something could not be pushed. */
suspend fun pushPendingChanges(c: com.vdelaar.mylibby.di.AppContainer): Boolean {
    var failed = false
    val api = runCatching { c.api.grimmory() }.getOrNull() ?: return true

    // Outbox: favourites and read status
    for (item in c.db.outbox().all()) {
        try {
            when (item.type) {
                OutboxType.FAVOURITE_ADD, OutboxType.FAVOURITE_REMOVE -> {
                    val shelfId = c.library.ensureFavouritesShelf()
                    val add = item.type == OutboxType.FAVOURITE_ADD
                    val r = api.assignShelves(
                        ShelvesAssignmentRequest(
                            bookIds = setOf(item.bookId),
                            shelvesToAssign = if (add) setOf(shelfId) else emptySet(),
                            shelvesToUnassign = if (add) emptySet() else setOf(shelfId),
                        )
                    )
                    if (!r.isSuccessful && r.code() != 404) error("HTTP ${r.code()}")
                }
                OutboxType.STATUS -> {
                    val r = api.updateStatus(item.bookId, UpdateStatusRequest(item.payload ?: "READING"))
                    if (!r.isSuccessful && r.code() != 404) error("HTTP ${r.code()}")
                }
                OutboxType.RATING -> {
                    val rating = item.payload?.toIntOrNull() ?: 0
                    val r = if (rating > 0) api.setPersonalRating(PersonalRatingRequest(listOf(item.bookId), rating)) else api.resetPersonalRating(listOf(item.bookId))
                    if (!r.isSuccessful && r.code() != 404) error("HTTP ${r.code()}")
                }
            }
            c.db.outbox().delete(item.id)
        } catch (_: Exception) {
            c.db.outbox().bump(item.id)
            failed = true
        }
    }

    c.reading.pushAllProgress()
    c.reading.pushAnnotations()
    c.reading.pushBookmarks()
    if (!c.stats.pushSessions()) failed = true
    if (c.db.progress().dirty().isNotEmpty()) failed = true
    if (c.db.outbox().all().isNotEmpty()) failed = true

    return failed
}
