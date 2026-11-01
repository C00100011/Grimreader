package com.vdelaar.mylibby.data.dict

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The headword index of a pack as a small SQLite file next to its entries. */
class SqlitePackIndexStore : PackIndexStore {
    override fun build(file: File, fill: (add: (IdxEntry) -> Unit) -> Unit) {
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE w (norm TEXT NOT NULL, plain TEXT NOT NULL, off INTEGER NOT NULL, size INTEGER NOT NULL)")
            db.beginTransaction()
            try {
                val insert = db.compileStatement("INSERT INTO w (norm, plain, off, size) VALUES (?, ?, ?, ?)")
                fill { e ->
                    insert.clearBindings()
                    insert.bindString(1, indexKey(e.word)); insert.bindString(2, plainKey(e.word))
                    insert.bindLong(3, e.offset); insert.bindLong(4, e.size.toLong())
                    insert.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL("CREATE INDEX w_norm ON w (norm)")
            db.execSQL("CREATE INDEX w_plain ON w (plain)")
        } finally { db.close() }
    }

    override fun open(file: File): PackIndex {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return object : PackIndex {
            override fun find(norm: String, plain: String): List<Located> {
                val out = ArrayList<Located>()
                // An exact headword first; the accent-insensitive matches only when there is none.
                for ((col, key) in listOf("norm" to norm, "plain" to plain)) {
                    db.rawQuery("SELECT off, size FROM w WHERE $col = ? LIMIT 20", arrayOf(key)).use { c ->
                        while (c.moveToNext()) out += Located(c.getLong(0), c.getInt(1))
                    }
                    if (out.isNotEmpty()) break
                }
                return out
            }
            override fun close() = db.close()
        }
    }
}

/** Small on disk: defaults (empty lists) are not written. */
private val wiktJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** The Wiktionary index: a headword per row with the entry as JSON, found by the lower-case or the accent-free headword. */
class SqliteWiktStore : WiktStore {
    override fun build(file: File, fill: (add: (String, WiktEntry) -> Unit) -> Unit): Int {
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        var count = 0
        try {
            db.execSQL("CREATE TABLE e (norm TEXT NOT NULL, plain TEXT NOT NULL, body TEXT NOT NULL)")
            db.beginTransaction()
            try {
                val insert = db.compileStatement("INSERT INTO e (norm, plain, body) VALUES (?, ?, ?)")
                fill { word, entry ->
                    insert.clearBindings()
                    insert.bindString(1, indexKey(word)); insert.bindString(2, plainKey(word))
                    insert.bindString(3, wiktJson.encodeToString(WiktEntry.serializer(), entry))
                    insert.executeInsert()
                    count++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL("CREATE INDEX e_norm ON e (norm)")
            db.execSQL("CREATE INDEX e_plain ON e (plain)")
        } finally { db.close() }
        return count
    }

    override fun open(file: File): WiktIndex {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return object : WiktIndex {
            override fun find(norm: String, plain: String): List<WiktEntry> {
                val out = ArrayList<WiktEntry>()
                // An exact headword first; the accent-insensitive matches only when there is none.
                for ((col, key) in listOf("norm" to norm, "plain" to plain)) {
                    db.rawQuery("SELECT body FROM e WHERE $col = ? LIMIT 30", arrayOf(key)).use { c ->
                        while (c.moveToNext()) runCatching { wiktJson.decodeFromString(WiktEntry.serializer(), c.getString(0)) }.getOrNull()?.let { out += it }
                    }
                    if (out.isNotEmpty()) break
                }
                return out
            }
            override fun close() = db.close()
        }
    }
}

/** Downloads a pack, reporting progress. A file that is only half there is never left behind. */
class OkPackDownloader(
    /** Ask for a compressed answer and keep it compressed: servers that squeeze a large file as they send it announce no size. */
    private val gzip: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(com.vdelaar.mylibby.core.debug.NetworkLogInterceptor("Dictionary"))
        .build(),
) : PackDownloader {
    override suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Grimreader (Android e-book reader)")
            .apply { if (gzip) header("Accept-Encoding", "gzip") }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The download server answered ${resp.code}")
            val body = resp.body ?: throw IOException("Empty answer")
            val total = body.contentLength()
            var done = 0L
            var last = 0L
            try {
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last >= 128 * 1024 || done == total) { last = done; onProgress(done, total) }
                        }
                    }
                }
            } catch (e: Exception) { target.delete(); throw e }
            onProgress(done, total)
        }
    }
}
