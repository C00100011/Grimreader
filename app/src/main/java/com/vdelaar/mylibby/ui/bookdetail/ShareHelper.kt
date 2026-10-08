package com.vdelaar.mylibby.ui.bookdetail

import android.content.Context
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import android.content.Intent
import androidx.core.content.FileProvider
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

object ShareHelper {

    private fun shareText(c: AppContainer, book: Book): String = buildString {
        append("📖 ").append(book.title)
        if (book.authors.isNotEmpty()) append(str(R.string.share_by)).append(book.authorLine)
        book.seriesLine?.let { append("\n").append(it) }
        append("\n\n").append(str(R.string.share_pitch))
        val base = c.api.grimmoryBaseUrl
        if (base.isNotBlank()) append("\n").append(base).append("book/").append(book.id)
    }

    /** Shares title, author and cover image through the Android share sheet. */
    suspend fun shareBook(context: Context, c: AppContainer, book: Book) {
        val cover = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "cover_${book.id}.jpg")
                val req = Request.Builder().url(c.api.coverUrl(book.id, book.coverVersion)).build()
                c.api.grimmoryClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                }
                file
            }.getOrNull()
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            putExtra(Intent.EXTRA_TEXT, shareText(c, book))
            if (cover != null) {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.files", cover))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        context.startActivity(Intent.createChooser(intent, str(R.string.share_title, book.title)))
    }

    /** Shares the downloaded book file itself (e.g. to send to a friend's e-reader). */
    fun shareFile(context: Context, book: Book, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = when (file.extension.lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "mobi", "azw3" -> "application/x-mobipocket-ebook"
            else -> "application/octet-stream"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, str(R.string.send_book_file)))
    }
}
