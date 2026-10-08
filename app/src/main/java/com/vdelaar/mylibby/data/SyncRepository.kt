package com.vdelaar.mylibby.data

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.toBook
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

enum class SyncStep { SENDING, BOOKS, COVERS }

/** What a running synchronisation is doing right now (shown in the sync popup). */
data class SyncProgress(
    val running: Boolean = false,
    val step: SyncStep? = null,
    val done: Int = 0,
    val total: Int = 0,
    val forced: Boolean = false,
)

/**
 * Covers are big downloads: on a metered connection (mobile data) they wait for Wi-Fi, unless the user
 * pressed "synchronise now" themselves.
 */
internal fun shouldFetchCovers(forced: Boolean, metered: Boolean): Boolean = forced || !metered

/**
 * The full synchronisation with Grimmory: send what is waiting, bring the favourites in, read the whole book list
 * into the local cache (so the library can be browsed offline) and keep the covers on the device.
 * One run at a time; the result is remembered for the sync popup.
 */
class SyncRepository(
    private val context: Context,
    private val api: ApiProvider,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val library: LibraryRepository,
    private val reading: ReadingRepository,
    private val scope: CoroutineScope,
    /** Sends the outbox, progress, highlights, bookmarks and sessions; true when something could not be sent. */
    private val push: suspend () -> Boolean,
    private val isMetered: () -> Boolean = { defaultMetered(context) },
    private val prefetchCover: suspend (url: String) -> Boolean = { url -> prefetchWithCoil(context, url) },
) {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()
    private val mutex = Mutex()

    /** A Grimmory connection exists and is switched on. */
    val isActive: Boolean get() = !settings.app.value.noServer && tokens.accessToken != null

    /** Starts a full synchronisation in the background (survives leaving the screen). Does nothing while one runs. */
    fun launchFull(forced: Boolean) {
        if (!isActive || _progress.value.running) return
        scope.launch { run(full = true, forced = forced) }
    }

    /**
     * Runs one synchronisation. [full] also reads the book list and keeps covers; otherwise only changes are sent.
     * Returns true when something failed (the background worker then tries again later).
     */
    suspend fun run(full: Boolean, forced: Boolean = false): Boolean {
        if (!isActive) return false
        if (!mutex.tryLock()) return false // somebody else is synchronising right now
        try {
            return doRun(full, forced)
        } finally {
            _progress.value = SyncProgress()
            mutex.unlock()
        }
    }

    private suspend fun doRun(full: Boolean, forced: Boolean): Boolean {
        var failed = false
        var error: String? = null
        var books = settings.syncStatus.value.books
        var covers = settings.syncStatus.value.covers
        try {
            _progress.value = SyncProgress(running = true, step = SyncStep.SENDING, forced = forced)
            failed = push()
            runCatching { library.refreshFavourites() }
            if (full) {
                _progress.value = SyncProgress(running = true, step = SyncStep.BOOKS, forced = forced)
                val all = pullBooks(forced)
                books = all.size
                // Furthest wins: a book read further here than on a Kobo is sent again so the Kobo gets it.
                runCatching { reading.reassertAhead(all) }
                if (shouldFetchCovers(forced, isMetered())) covers = saveCovers(all, forced)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            error = e.message ?: e.javaClass.simpleName
        }
        settings.updateSyncStatus { it.copy(lastAt = System.currentTimeMillis(), lastOk = !failed, lastError = error, books = books, covers = covers, forced = forced) }
        return failed
    }

    private suspend fun pullBooks(forced: Boolean): List<Book> {
        val all = ArrayList<Book>()
        var page = 0
        do {
            val res = api.grimmory().books(page = page, size = PAGE, sort = "addedOn", dir = "desc")
            val chunk = res.content.map { it.toBook() }
            library.cache(chunk)
            all += chunk
            _progress.value = SyncProgress(running = true, step = SyncStep.BOOKS, done = all.size, total = res.totalElements.toInt(), forced = forced)
            page++
        } while (res.hasNext && page < MAX_PAGES)
        return all
    }

    private suspend fun saveCovers(all: List<Book>, forced: Boolean): Int {
        val saved = AtomicInteger()
        val seen = AtomicInteger()
        val gate = Semaphore(PARALLEL_COVERS)
        coroutineScope {
            all.map { b ->
                async {
                    gate.withPermit {
                        if (prefetchCover(api.coverUrl(b.id, b.coverVersion, thumbnail = true))) saved.incrementAndGet()
                        val n = seen.incrementAndGet()
                        if (n % 5 == 0 || n == all.size) _progress.value = SyncProgress(running = true, step = SyncStep.COVERS, done = n, total = all.size, forced = forced)
                    }
                }
            }.awaitAll()
        }
        return saved.get()
    }

    private companion object {
        const val PAGE = 100
        const val MAX_PAGES = 300
        const val PARALLEL_COVERS = 4
    }
}

private fun defaultMetered(context: Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    return cm.isActiveNetworkMetered
}

/** Loads a cover into Coil's disk cache without keeping a decoded copy in memory. */
private suspend fun prefetchWithCoil(context: Context, url: String): Boolean {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size(32, 32))
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    return SingletonImageLoader.get(context).execute(request) is SuccessResult
}
