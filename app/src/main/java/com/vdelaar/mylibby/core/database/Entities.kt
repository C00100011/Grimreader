package com.vdelaar.mylibby.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached copy of a Grimmory book so the library works offline. List fields are stored joined by [SEP]. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val authors: String,
    val seriesName: String?,
    val seriesNumber: Float?,
    val categories: String,
    val language: String?,
    val readStatus: String?,
    val readProgress: Float?,
    val lastReadTime: String?,
    val addedOn: String?,
    val primaryFileId: Long?,
    val primaryFileType: String?,
    val coverUpdatedOn: String?,
    val pageCount: Int?,
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val personalRating: Int? = null,
    val cachedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SEP = "\u001F"
    }
}

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val bookId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val bookId: Long,
    val title: String,
    val filePath: String?,
    val fileType: String,
    val sizeBytes: Long = 0,
    val progress: Float = 0f,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val downloadedAt: Long = 0,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val bookFileId: Long?,
    val cfi: String?,
    val href: String?,
    /** 0..100, like Grimmory. */
    val percent: Float,
    val ttsCfi: String? = null,
    val updatedAt: Long,
    val dirty: Boolean,
)

enum class PendingAction { NONE, CREATE, UPDATE, DELETE }

@Entity(tableName = "annotations", indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val remoteId: Long? = null,
    val bookId: Long,
    val cfi: String,
    val text: String,
    val color: String,
    val style: String,
    val note: String? = null,
    val chapterTitle: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val pending: PendingAction = PendingAction.CREATE,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val remoteId: Long? = null,
    val bookId: Long,
    val cfi: String,
    val title: String?,
    val percent: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val pending: PendingAction = PendingAction.CREATE,
)

@Entity(tableName = "sessions", indices = [Index("localDate"), Index("bookId")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val bookTitle: String,
    val bookType: String?,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,
    val startProgress: Float,
    val endProgress: Float,
    val startCfi: String?,
    val endCfi: String?,
    /** Book content bytes advanced while reading forward; used for reading speed. */
    val bytesRead: Long,
    val pagesTurned: Int,
    /** yyyy-MM-dd in the device time zone at session start. */
    val localDate: String,
    val listening: Boolean = false,
    val synced: Boolean = false,
    /** Where the session comes from: [SOURCE_APP] (read here), [SOURCE_KOBO] (imported) or [SOURCE_GRIMMORY] (fetched). */
    @androidx.room.ColumnInfo(defaultValue = SOURCE_APP) val source: String = SOURCE_APP,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_KOBO = "kobo"
        const val SOURCE_GRIMMORY = "grimmory"
    }
}

/** Time spent per book section (≈ chapter). */
@Entity(tableName = "section_stats", primaryKeys = ["bookId", "sectionIndex"])
data class SectionStatEntity(
    val bookId: Long,
    val sectionIndex: Int,
    val label: String,
    val seconds: Long,
    val pages: Int,
    val finished: Boolean = false,
)

@Entity(tableName = "book_text_stats")
data class BookTextStatsEntity(
    @PrimaryKey val bookId: Long,
    val totalBytes: Long,
    val totalWords: Long,
)

/** A stretch of a book's text for the on-device AI chat; [summary] is filled in as the reader gets that far. */
@Entity(tableName = "book_chunks", primaryKeys = ["bookId", "idx"])
data class BookChunkEntity(
    val bookId: Long,
    val idx: Int,
    val section: String,
    /** The section's place in the book's reading order (what the reader reports as its section index). A chunk never spans two sections. */
    val spineIndex: Int,
    /** Where the chunk starts and ends inside that section, 0..1 of its text. */
    val startShare: Float,
    val endShare: Float,
    val text: String,
    val summary: String?,
)

enum class OutboxType { FAVOURITE_ADD, FAVOURITE_REMOVE, STATUS, RATING }

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: OutboxType,
    val bookId: Long,
    val payload: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

/**
 * A book imported from the device (not in Grimmory). Ids are negative so they never collide
 * with Grimmory ids; progress, highlights and stats stay on the device only.
 */
@Entity(tableName = "local_books")
data class LocalBookEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val authors: String,
    val language: String?,
    val fileType: String,
    val filePath: String,
    val coverPath: String?,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val readStatus: String? = null,
    val readProgress: Float? = null,
    val lastReadTime: String? = null,
)

/** One card of a week's swipe deck: a book idea and what the user decided (null = not yet). */
@Entity(tableName = "swipe_cards", primaryKeys = ["week", "key"])
data class SwipeCardEntity(
    /** ISO week, e.g. "2026-W41". */
    val week: String,
    /** Open Library work key. */
    val key: String,
    val position: Int,
    val title: String,
    val authors: String,
    val year: Int?,
    val coverId: Long?,
    val isbn: String?,
    /** Comma separated ISO codes. */
    val languages: String,
    /** Pipe separated. */
    val subjects: String,
    /** YES, LOVE or NO; null = still in the deck. */
    val decision: String? = null,
    val decidedAt: Long? = null,
)

/** A book the user wants: to read (maybe requested) or to buy later. */
@Entity(tableName = "wanted")
data class WantedEntity(
    @PrimaryKey val key: String,
    val title: String,
    val authors: String,
    val year: Int?,
    val coverId: Long?,
    val isbn: String?,
    val languages: String,
    val subjects: String,
    /** READ or BUY. */
    val list: String,
    /** Swiped up: "love it". */
    val love: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)
