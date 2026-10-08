package com.vdelaar.mylibby.data

import android.content.Context
import android.net.Uri
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo mode lets people (and app store reviewers) try MyLibby without a Grimmory server.
 * It imports the free books bundled in the app (Standard Ebooks, CC0) as ordinary on-device books.
 */
class DemoRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val localBooks: LocalBooksRepository,
) {
    val isDemo: Boolean get() = settings.app.value.demo

    suspend fun start() = withContext(Dispatchers.IO) {
        val ids = mutableSetOf<Long>()
        val tmp = File(context.cacheDir, "demo").apply { mkdirs() }
        for (name in context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".epub") }.sorted()) {
            val file = File(tmp, name)
            context.assets.open("$ASSET_DIR/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            ids += localBooks.import(Uri.fromFile(file)).id
            file.delete()
        }
        settings.updateApp { it.copy(demo = true, demoBookIds = ids) }
    }

    /** Leaves demo mode and removes the demo books from this device. */
    suspend fun exit() = withContext(Dispatchers.IO) {
        for (id in settings.app.value.demoBookIds) localBooks.delete(id)
        settings.updateApp { it.copy(demo = false, demoBookIds = emptySet(), onboardingDone = false) }
    }

    private companion object {
        const val ASSET_DIR = "demo"
    }
}
