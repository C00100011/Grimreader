package com.vdelaar.mylibby.data.dict

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.notifications.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Downloads dictionaries as background work, so they carry on when the user leaves the screen or the app, and a notification
 * says when one is ready. A pack is either a Wiktionary one ([WIKT], by id) or a FreeDict one ([FREEDICT], by name).
 */
object DictionaryDownloads {
    const val WIKT = "wikt"
    const val FREEDICT = "freedict"

    fun startWiktionary(context: Context, info: WiktPackInfo) {
        (context.applicationContext as MyLibbyApp).container.wiktionary.markQueued(info.id)
        enqueue(context, WIKT, info.id)
    }

    fun startFreedict(context: Context, info: PackInfo) {
        (context.applicationContext as MyLibbyApp).container.dictionaryPacks.markQueued(info.name)
        enqueue(context, FREEDICT, info.name)
    }

    private fun enqueue(context: Context, kind: String, id: String) {
        val request = OneTimeWorkRequestBuilder<DictionaryDownloadWorker>()
            .setInputData(Data.Builder().putString(DictionaryDownloadWorker.KEY_KIND, kind).putString(DictionaryDownloadWorker.KEY_ID, id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dictionary-$kind-$id", ExistingWorkPolicy.KEEP, request)
    }
}

class DictionaryDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val kind = inputData.getString(KEY_KIND)
    private val id = inputData.getString(KEY_ID).orEmpty()
    // The progress notification goes when the work ends, so "ready" needs an id of its own.
    private val notificationId = 6000 + (id.hashCode() and 0xfff)
    private val finishedId = notificationId + 0x1000

    override suspend fun doWork(): Result {
        val container = (applicationContext as MyLibbyApp).container
        val label: String
        val progress: StateFlow<Map<String, PackProgress>>
        val install: suspend () -> Unit
        when (kind) {
            DictionaryDownloads.WIKT -> {
                val info = WIKTIONARY_PACKS.firstOrNull { it.id == id } ?: return Result.failure()
                label = wiktLabel(applicationContext, info)
                progress = container.wiktionary.progress
                install = { container.wiktionary.install(info) }
            }
            DictionaryDownloads.FREEDICT -> {
                // The catalog is kept on the device, so this needs no network once the list has been seen.
                val info = try { container.dictionaryPacks.catalog().firstOrNull { it.name == id } } catch (e: IOException) { null }
                    ?: return Result.failure().also { container.dictionaryPacks.dismissFailure(id) }
                label = packLabel(info.source, info.target)
                progress = container.dictionaryPacks.progress
                install = { container.dictionaryPacks.install(info) }
            }
            else -> return Result.failure()
        }
        runCatching { setForeground(foreground(label, 0f, false)) }
        return coroutineScope {
            val watcher = launch {
                var last = -1
                progress.collect { map ->
                    val state = map[id]
                    val (fraction, preparing) = when (state) {
                        is PackProgress.Downloading -> state.fraction to false
                        is PackProgress.Preparing -> state.fraction to true
                        else -> return@collect
                    }
                    val percent = ((fraction ?: 0f) * 100).toInt()
                    if (percent != last) { last = percent; runCatching { setForeground(foreground(label, fraction, preparing)) } }
                }
            }
            try {
                install()
                Notifications.dictionaryFinished(applicationContext, finishedId, label, null)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The reason is also in the progress map, where the settings show it with a retry button.
                Notifications.dictionaryFinished(applicationContext, finishedId, label, e.message ?: e.javaClass.simpleName)
                Result.failure()
            } finally {
                watcher.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(id, 0f, false)

    private fun foreground(label: String, fraction: Float?, preparing: Boolean) = ForegroundInfo(
        notificationId, Notifications.dictionaryProgress(applicationContext, label, fraction, preparing), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_ID = "id"
    }
}
