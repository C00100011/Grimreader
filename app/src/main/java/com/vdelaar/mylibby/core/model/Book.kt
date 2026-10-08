package com.vdelaar.mylibby.core.model

import com.vdelaar.mylibby.core.database.BookEntity
import com.vdelaar.mylibby.core.network.BookDetailDto
import com.vdelaar.mylibby.core.network.BookSummaryDto
import com.vdelaar.mylibby.core.network.RecommendedBookDto

data class Book(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val categories: List<String> = emptyList(),
    val language: String? = null,
    val readStatus: String? = null,
    /** 0..100 */
    val progress: Float? = null,
    val lastReadTime: String? = null,
    val addedOn: String? = null,
    val primaryFileId: Long? = null,
    val primaryFileType: String? = null,
    val coverVersion: String? = null,
    val pageCount: Int? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    /** Set for books imported from the device. */
    val localPath: String? = null,
    val localCover: String? = null,
    /** Your own rating, 1..10 (= half stars); null when not rated. */
    val rating: Int? = null,
) {
    /** Imported from this device; not part of Grimmory. */
    val isLocal: Boolean get() = id < 0

    val authorLine: String get() = authors.joinToString(", ").ifBlank { "Unknown author" }

    val seriesLine: String?
        get() = seriesName?.let { name ->
            seriesNumber?.let { n -> "$name #${formatSeriesNumber(n)}" } ?: name
        }

    val isReadable: Boolean
        get() = primaryFileType?.uppercase() in READABLE_TYPES

    val isDutch: Boolean
        get() = language?.lowercase()?.let { it.startsWith("nl") || it.startsWith("dut") || it.contains("dutch") || it.contains("nederlands") } == true

    companion object {
        val READABLE_TYPES = setOf("EPUB", "MOBI", "AZW3", "AZW", "FB2", "CBZ", "CBX")
    }
}

fun formatSeriesNumber(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()

fun BookSummaryDto.toBook() = Book(
    id = id,
    title = title.orEmpty().ifBlank { primaryFileName ?: "Untitled" },
    authors = authors.orEmpty(),
    seriesName = seriesName,
    seriesNumber = seriesNumber,
    categories = categories.orEmpty(),
    language = language,
    readStatus = readStatus,
    progress = readProgress,
    lastReadTime = lastReadTime,
    addedOn = addedOn,
    primaryFileId = primaryFileId,
    primaryFileType = primaryFileType,
    coverVersion = coverUpdatedOn,
    pageCount = pageCount,
    publisher = publisher,
    publishedDate = publishedDate,
    rating = personalRating?.takeIf { it > 0 },
)

/** A book from the recommendations endpoint (no reading progress there, so progress stays null). */
fun RecommendedBookDto.toBook(): Book {
    val m = metadata
    return Book(
        id = id,
        title = (m?.title ?: title).orEmpty().ifBlank { "Untitled" },
        authors = m?.authors.orEmpty(),
        seriesName = m?.seriesName,
        seriesNumber = m?.seriesNumber,
        categories = m?.categories.orEmpty(),
        language = m?.language,
        readStatus = readStatus,
        lastReadTime = lastReadTime,
        addedOn = addedOn,
        primaryFileId = primaryFile?.id,
        primaryFileType = primaryFile?.bookType,
        coverVersion = m?.coverUpdatedOn,
        pageCount = m?.pageCount,
        description = m?.description,
        publisher = m?.publisher,
        publishedDate = m?.publishedDate,
        rating = personalRating?.takeIf { it > 0 },
    )
}

fun BookDetailDto.toBook(): Book {
    val primary = files?.firstOrNull { it.isPrimary } ?: files?.firstOrNull { it.isBook }
    return Book(
        id = id,
        title = title.orEmpty().ifBlank { "Untitled" },
        authors = authors.orEmpty(),
        seriesName = seriesName,
        seriesNumber = seriesNumber,
        categories = categories.orEmpty(),
        language = language,
        readStatus = readStatus,
        progress = readProgress,
        lastReadTime = lastReadTime,
        addedOn = addedOn,
        primaryFileId = primary?.id,
        primaryFileType = primaryFileType ?: primary?.bookType,
        coverVersion = coverUpdatedOn,
        pageCount = pageCount,
        description = description,
        publisher = publisher,
        publishedDate = publishedDate,
        rating = personalRating?.takeIf { it > 0 },
    )
}

fun Book.toEntity() = BookEntity(
    id = id,
    title = title,
    authors = authors.joinToString(BookEntity.SEP),
    seriesName = seriesName,
    seriesNumber = seriesNumber,
    categories = categories.joinToString(BookEntity.SEP),
    language = language,
    readStatus = readStatus,
    readProgress = progress,
    lastReadTime = lastReadTime,
    addedOn = addedOn,
    primaryFileId = primaryFileId,
    primaryFileType = primaryFileType,
    coverUpdatedOn = coverVersion,
    pageCount = pageCount,
    description = description,
    publisher = publisher,
    publishedDate = publishedDate,
    personalRating = rating,
)

fun BookEntity.toBook() = Book(
    id = id,
    title = title,
    authors = authors.split(BookEntity.SEP).filter { it.isNotBlank() },
    seriesName = seriesName,
    seriesNumber = seriesNumber,
    categories = categories.split(BookEntity.SEP).filter { it.isNotBlank() },
    language = language,
    readStatus = readStatus,
    progress = readProgress,
    lastReadTime = lastReadTime,
    addedOn = addedOn,
    primaryFileId = primaryFileId,
    primaryFileType = primaryFileType,
    coverVersion = coverUpdatedOn,
    pageCount = pageCount,
    description = description,
    publisher = publisher,
    publishedDate = publishedDate,
    rating = personalRating,
)

/** Merge a fresh summary into a cached entity without losing detail-only fields. */
fun BookEntity?.mergeWith(book: Book): BookEntity {
    val e = book.toEntity()
    if (this == null) return e
    return e.copy(
        description = e.description ?: description,
        publisher = e.publisher ?: publisher,
        publishedDate = e.publishedDate ?: publishedDate,
    )
}

data class LibraryFilter(
    val search: String = "",
    val authors: Set<String> = emptySet(),
    val series: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val favouritesOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    /** Only books imported from the device. */
    val localOnly: Boolean = false,
    val sort: String = "addedOn",
    val dir: String = "desc",
) {
    val activeCount: Int
        get() = authors.size + series.size + genres.size + languages.size + statuses.size +
            (if (favouritesOnly) 1 else 0) + (if (downloadedOnly) 1 else 0) + (if (localOnly) 1 else 0)
}

data class FilterOptions(
    val authors: List<Pair<String, Long>> = emptyList(),
    val series: List<Pair<String, Long>> = emptyList(),
    val genres: List<Pair<String, Long>> = emptyList(),
    val languages: List<Triple<String, String, Long>> = emptyList(),
    val statuses: List<Pair<String, Long>> = emptyList(),
)

data class SeriesInfo(
    val name: String,
    val bookCount: Int,
    val total: Int?,
    val authors: List<String>,
    val booksRead: Int,
    val coverBookIds: List<Long>,
)

object ReadStatus {
    val all = listOf("UNREAD", "READING", "RE_READING", "READ", "PARTIALLY_READ", "PAUSED", "WONT_READ", "ABANDONED")

    fun label(status: String?): String = com.vdelaar.mylibby.core.str(labelRes(status))

    fun labelRes(status: String?): Int = when (status) {
        "UNREAD" -> com.vdelaar.mylibby.R.string.status_unread
        "READING" -> com.vdelaar.mylibby.R.string.status_reading
        "RE_READING" -> com.vdelaar.mylibby.R.string.status_rereading
        "READ" -> com.vdelaar.mylibby.R.string.status_read
        "PARTIALLY_READ" -> com.vdelaar.mylibby.R.string.status_partial
        "PAUSED" -> com.vdelaar.mylibby.R.string.status_paused
        "WONT_READ" -> com.vdelaar.mylibby.R.string.status_wont_read
        "ABANDONED" -> com.vdelaar.mylibby.R.string.status_abandoned
        else -> com.vdelaar.mylibby.R.string.status_unset
    }
}

fun com.vdelaar.mylibby.core.database.LocalBookEntity.toBook() = Book(
    id = id,
    title = title,
    authors = authors.split(com.vdelaar.mylibby.core.database.BookEntity.SEP).filter { it.isNotBlank() },
    language = language,
    readStatus = readStatus,
    progress = readProgress,
    lastReadTime = lastReadTime,
    addedOn = java.time.Instant.ofEpochMilli(addedAt).toString(),
    primaryFileType = fileType,
    localPath = filePath,
    localCover = coverPath,
)
