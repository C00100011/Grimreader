package com.vdelaar.mylibby

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.notifications.Notifications
import com.vdelaar.mylibby.notifications.ReminderScheduler

class MyLibbyApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.vdelaar.mylibby.core.AppStrings.context = this
        container = AppContainer(this)
        kotlinx.coroutines.runBlocking { container.settings.migrate() }
        // Android 12: apply the chosen app language to non-UI strings too.
        com.vdelaar.mylibby.core.AppStrings.context = com.vdelaar.mylibby.core.AppLanguage.wrap(this, container.settings.app.value.language)
        Notifications.createChannels(this)
        ReminderScheduler.schedule(this, container.settings.goal.value)
        if (container.tokens.accessToken != null) {
            container.sync.schedulePeriodic()
            container.sync.requestSync()
            com.vdelaar.mylibby.data.AutoDownload.apply(this, container.settings.app.value)
        }
    }

    // Covers are loaded with the right credentials (Grimmory token or Shelfmark session if configurwd).
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.api.imageCallFactory })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                // Covers live in app storage (not the cache folder), so clearing the cache does not lose them offline.
                cacheDir.resolve("covers").deleteRecursively()
                DiskCache.Builder()
                    .directory(filesDir.resolve("covers"))
                    .maxSizeBytes(500L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
