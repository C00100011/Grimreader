package com.vdelaar.mylibby.di

import android.content.Context
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.security.TokenStore
import com.vdelaar.mylibby.data.AuthRepository
import com.vdelaar.mylibby.data.DownloadRepository
import com.vdelaar.mylibby.data.LibraryRepository
import com.vdelaar.mylibby.data.ReadingRepository
import com.vdelaar.mylibby.data.ShelfmarkRepository
import com.vdelaar.mylibby.data.StatsRepository
import com.vdelaar.mylibby.data.SyncScheduler
import com.vdelaar.mylibby.tts.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency graph: one instance of each service for the whole app. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context, appScope)
    val tokens = TokenStore(context)
    val db = AppDatabase.build(context)
    val api = ApiProvider(context.applicationContext, settings, tokens)
    val sync = SyncScheduler(context)
    val auth = AuthRepository(api, tokens, settings, db)
    val library = LibraryRepository(api, db, settings, sync)
    val reading = ReadingRepository(api, db, sync, settings)
    /** The full Grimmory synchronisation: changes out, book list and covers kept on the device. */
    val librarySync = com.vdelaar.mylibby.data.SyncRepository(
        context.applicationContext, api, settings, tokens, library, reading, appScope,
        push = { com.vdelaar.mylibby.data.pushPendingChanges(this@AppContainer) },
    )
    val downloads = DownloadRepository(context, api, db, settings)
    val stats = StatsRepository(api, db, settings, sync)
    val shelfmark = ShelfmarkRepository(api, settings, tokens)
    val tts = TtsController(context, settings, appScope)
    val localBooks = com.vdelaar.mylibby.data.LocalBooksRepository(context, db)
    val demo = com.vdelaar.mylibby.data.DemoRepository(context, settings, localBooks)
    val opds = com.vdelaar.mylibby.data.OpdsRepository(settings, tokens, localBooks, java.io.File(context.cacheDir, "opds")).also { o ->
        api.opdsImageClient = o.client // covers of a protected catalog need its login
    }
    val hardcover = com.vdelaar.mylibby.data.HardcoverRepository(
        tokens,
        com.vdelaar.mylibby.core.network.HardcoverClient(tokens),
        library,
        com.vdelaar.mylibby.data.HardcoverCache(java.io.File(context.cacheDir, "hardcover")),
    )
    val discover = com.vdelaar.mylibby.data.DiscoverRepository(db, settings, library, com.vdelaar.mylibby.data.HardcoverCache(java.io.File(context.cacheDir, "openlibrary")))
    val swipe = com.vdelaar.mylibby.data.SwipeRepository(db, discover, library)
    val personalData = com.vdelaar.mylibby.data.PersonalDataRepository(db, settings, sync)
}
