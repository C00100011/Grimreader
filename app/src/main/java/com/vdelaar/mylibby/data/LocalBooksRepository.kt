package com.vdelaar.mylibby.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookEntity
import com.vdelaar.mylibby.core.database.LocalBookEntity
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.toBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Books the user brings themselves (imported files or "Open with MyLibby"). */
class LocalBooksRepository(private val context: Context, private val db: AppDatabase) {

    private val dir get() = File(context.filesDir, "local").apply { mkdirs() }

    fun observe(): Flow<List<Book>> = db.localBooks().observeAll().map { list -> list.map { it.toBook() } }

    suspend fun all(): List<Book> = withContext(Dispatchers.IO) { db.localBooks().all().map { it.toBook() } }

    suspend fun get(id: Long): Book? = withContext(Dispatchers.IO) { db.localBooks().get(id)?.toBook() }

    /** Copies the file into app storage, reads title/author/cover and returns the new book. */
    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment?.takeIf { it.contains('.') } ?: "book.epub"
        val type = typeFor(name, context.contentResolver.getType(uri))
        val id = (db.localBooks().minId()?.coerceAtMost(0) ?: 0) - 1
        val file = File(dir, "$id.${DownloadRepository.extensionFor(type)}")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("Can't read this file")
        val meta = if (type == "EPUB") readEpubMetadata(file, File(dir, "$id-cover")) else EpubMeta()
        val title = meta.title ?: name.substringBeforeLast('.').replace('_', ' ')
        // Opening the same file twice shouldn't create a duplicate.
        db.localBooks().all().firstOrNull { it.title == title && it.sizeBytes == file.length() }?.let { existing ->
            file.delete()
            meta.coverPath?.let { File(it).delete() }
            return@withContext existing.toBook()
        }
        val entity = LocalBookEntity(
            id = id,
            title = title,
            authors = meta.authors.joinToString(BookEntity.SEP),
            language = meta.language,
            fileType = type,
            filePath = file.absolutePath,
            coverPath = meta.coverPath,
            sizeBytes = file.length(),
        )
        db.localBooks().upsert(entity)
        entity.toBook()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.localBooks().get(id)?.let { b ->
            File(b.filePath).delete()
            b.coverPath?.let { File(it).delete() }
        }
        db.localBooks().delete(id)
    }

    suspend fun saveProgress(id: Long, percent: Float) = withContext(Dispatchers.IO) {
        db.localBooks().updateProgress(id, percent, java.time.Instant.now().toString())
        if (percent >= 99.5f) db.localBooks().updateStatus(id, "READ")
        else if (percent > 0f) db.localBooks().get(id)?.takeIf { it.readStatus == null }?.let { db.localBooks().updateStatus(id, "READING") }
    }

    suspend fun setStatus(id: Long, status: String) = withContext(Dispatchers.IO) { db.localBooks().updateStatus(id, status) }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun typeFor(name: String, mime: String?): String = when {
        name.endsWith(".mobi", true) || mime == "application/x-mobipocket-ebook" -> "MOBI"
        name.endsWith(".azw3", true) || name.endsWith(".azw", true) -> "AZW3"
        name.endsWith(".fb2", true) || mime == "application/x-fictionbook+xml" -> "FB2"
        name.endsWith(".cbz", true) -> "CBZ"
        else -> "EPUB"
    }

    data class EpubMeta(val title: String? = null, val authors: List<String> = emptyList(), val language: String? = null, val coverPath: String? = null)

    /** Minimal EPUB metadata reader: container.xml → OPF → dc:title/creator/language + cover image. */
    private fun readEpubMetadata(file: File, coverBase: File): EpubMeta = runCatching {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")?.let { e -> zip.getInputStream(e).bufferedReader().readText() } ?: return EpubMeta()
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return EpubMeta()
            val opf = zip.getEntry(opfPath)?.let { e -> zip.getInputStream(e).bufferedReader().readText() } ?: return EpubMeta()
            fun dc(tag: String) = Regex("<dc:$tag[^>]*>([\\s\\S]*?)</dc:$tag>").findAll(opf).map { decode(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
            val base = opfPath.substringBeforeLast('/', "")
            val items = Regex("<item\\b[^>]*>").findAll(opf).map { it.value }.toList()
            fun attr(tag: String, name: String) = Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            val coverId = Regex("<meta[^>]*name=\"cover\"[^>]*content=\"([^\"]+)\"").find(opf)?.groupValues?.get(1)
                ?: Regex("<meta[^>]*content=\"([^\"]+)\"[^>]*name=\"cover\"").find(opf)?.groupValues?.get(1)
            val coverItem = items.firstOrNull { attr(it, "properties")?.contains("cover-image") == true }
                ?: items.firstOrNull { coverId != null && attr(it, "id") == coverId }
            val coverPath = coverItem?.let { item ->
                val href = attr(item, "href") ?: return@let null
                val entryName = java.net.URLDecoder.decode(if (base.isEmpty()) href else "$base/$href", "UTF-8")
                zip.getEntry(entryName)?.let { e ->
                    val out = File(coverBase.parentFile, coverBase.name + "." + href.substringAfterLast('.', "jpg"))
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.absolutePath
                }
            }
            EpubMeta(dc("title").firstOrNull(), dc("creator"), dc("language").firstOrNull(), coverPath)
        }
    }.getOrDefault(EpubMeta())

    private fun decode(s: String) = s.trim()
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}
