package com.vdelaar.mylibby.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    fun observeMany(ids: List<Long>): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastReadTime DESC LIMIT :limit")
    suspend fun recentlyRead(limit: Int): List<BookEntity>

    @Query(
        """SELECT * FROM books WHERE
           (:q = '' OR title LIKE '%' || :q || '%' OR authors LIKE '%' || :q || '%' OR seriesName LIKE '%' || :q || '%')
           ORDER BY addedOn DESC"""
    )
    suspend fun search(q: String): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM books WHERE readStatus = 'READ'")
    fun observeFinishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE readStatus IN ('READING', 'RE_READING')")
    fun observeReadingCount(): Flow<Int>

    @Query("DELETE FROM books")
    suspend fun clear()
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT bookId FROM favourites")
    suspend fun ids(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE bookId = :bookId)")
    fun observeIsFavourite(bookId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE bookId = :bookId")
    suspend fun remove(bookId: Long)

    @Query("DELETE FROM favourites")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(list: List<FavouriteEntity>)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    suspend fun get(bookId: Long): DownloadEntity?

    @Upsert
    suspend fun upsert(d: DownloadEntity)

    @Query("UPDATE downloads SET progress = :progress, state = :state WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: Long, progress: Float, state: DownloadState)

    @Query("DELETE FROM downloads WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<ProgressEntity?>

    @Upsert
    suspend fun upsert(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE dirty = 1")
    suspend fun dirty(): List<ProgressEntity>

    @Query("UPDATE progress SET dirty = 0 WHERE bookId = :bookId AND updatedAt = :updatedAt")
    suspend fun markClean(bookId: Long, updatedAt: Long)

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    /** Grimmory books only (positive ids); progress in imported local books lives only on this device. */
    @Query("DELETE FROM progress WHERE bookId > 0")
    suspend fun clearRemote()
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND pending != 'DELETE' ORDER BY createdAt")
    fun observeForBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId")
    suspend fun allForBook(bookId: Long): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE pending != 'NONE'")
    suspend fun pending(): List<AnnotationEntity>

    @Insert
    suspend fun insert(a: AnnotationEntity): Long

    @Upsert
    suspend fun upsert(a: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("SELECT * FROM annotations WHERE localId = :localId")
    suspend fun get(localId: Long): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND cfi = :cfi LIMIT 1")
    suspend fun byCfi(bookId: Long, cfi: String): AnnotationEntity?

    @Query("SELECT COUNT(*) FROM annotations WHERE pending != 'DELETE'")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM annotations WHERE pending != 'DELETE'")
    suspend fun all(): List<AnnotationEntity>

    @Query("DELETE FROM annotations WHERE bookId > 0")
    suspend fun clearRemote()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND pending != 'DELETE' ORDER BY percent")
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    suspend fun allForBook(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE pending != 'NONE'")
    suspend fun pending(): List<BookmarkEntity>

    @Insert
    suspend fun insert(b: BookmarkEntity): Long

    @Upsert
    suspend fun upsert(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("SELECT * FROM bookmarks WHERE pending != 'DELETE'")
    suspend fun all(): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE bookId > 0")
    suspend fun clearRemote()
}

data class DayTotal(val localDate: String, val seconds: Long, val pages: Long)

data class BookTotal(val bookId: Long, val bookTitle: String, val seconds: Long, val sessions: Int)

/** All reading (not listening) of one book: for its reading pace. */
data class BookSessionTotals(val seconds: Long, val bytes: Long, val firstAt: Long, val lastAt: Long, val days: Int)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: ReadingSessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startTime")
    suspend fun all(): List<ReadingSessionEntity>

    @Query("SELECT * FROM sessions WHERE synced = 0")
    suspend fun unsynced(): List<ReadingSessionEntity>

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("SELECT localDate, SUM(durationSeconds) AS seconds, SUM(pagesTurned) AS pages FROM sessions GROUP BY localDate ORDER BY localDate")
    fun observeDayTotals(): Flow<List<DayTotal>>

    @Query("SELECT localDate, SUM(durationSeconds) AS seconds, SUM(pagesTurned) AS pages FROM sessions GROUP BY localDate ORDER BY localDate")
    suspend fun dayTotals(): List<DayTotal>

    @Query("SELECT bookId, bookTitle, SUM(durationSeconds) AS seconds, COUNT(*) AS sessions FROM sessions GROUP BY bookId ORDER BY seconds DESC LIMIT :limit")
    fun observeTopBooks(limit: Int): Flow<List<BookTotal>>

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM sessions WHERE bytesRead > 0 AND listening = 0 ORDER BY startTime DESC LIMIT :limit")
    suspend fun recentForSpeed(limit: Int): List<ReadingSessionEntity>

    @Query("SELECT * FROM sessions WHERE bytesRead > 0 AND listening = 0 ORDER BY startTime DESC LIMIT :limit")
    fun observeRecentForSpeed(limit: Int): Flow<List<ReadingSessionEntity>>

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM sessions WHERE bookId = :bookId")
    fun observeBookSeconds(bookId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) AS seconds, COALESCE(SUM(bytesRead), 0) AS bytes, COALESCE(MIN(startTime), 0) AS firstAt, COALESCE(MAX(endTime), 0) AS lastAt, COUNT(DISTINCT localDate) AS days FROM sessions WHERE bookId = :bookId AND listening = 0")
    suspend fun bookTotals(bookId: Long): BookSessionTotals

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM sessions")
    fun observeTotalSeconds(): Flow<Long>

    @Query("SELECT COALESCE(SUM(pagesTurned), 0) FROM sessions")
    fun observeTotalPages(): Flow<Long>
}

@Dao
interface SectionStatDao {
    @Query("SELECT * FROM section_stats")
    suspend fun all(): List<SectionStatEntity>

    @Query("SELECT * FROM section_stats WHERE bookId = :bookId AND sectionIndex = :index")
    suspend fun get(bookId: Long, index: Int): SectionStatEntity?

    @Upsert
    suspend fun upsert(s: SectionStatEntity)

    @Query("SELECT * FROM section_stats WHERE bookId = :bookId ORDER BY sectionIndex")
    fun observeForBook(bookId: Long): Flow<List<SectionStatEntity>>

    /** Average time per finished chapter, across all books. */
    @Query("SELECT AVG(seconds) FROM section_stats WHERE finished = 1 AND seconds > 60")
    fun observeAvgChapterSeconds(): Flow<Double?>

    @Query("SELECT COALESCE(SUM(seconds), 0) * 1.0 / MAX(COALESCE(SUM(pages), 0), 1) FROM section_stats")
    fun observeAvgSecondsPerPage(): Flow<Double>
}

@Dao
interface TextStatsDao {
    @Query("SELECT * FROM book_text_stats WHERE bookId = :bookId")
    suspend fun get(bookId: Long): BookTextStatsEntity?

    @Upsert
    suspend fun upsert(s: BookTextStatsEntity)

    @Query("SELECT * FROM book_text_stats")
    suspend fun all(): List<BookTextStatsEntity>
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(o: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bump(id: Long)

    @Query("DELETE FROM outbox WHERE bookId = :bookId AND type IN (:types)")
    suspend fun deleteFor(bookId: Long, types: List<OutboxType>)
}

@Dao
interface LocalBookDao {
    @Query("SELECT * FROM local_books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LocalBookEntity>>

    @Query("SELECT * FROM local_books ORDER BY addedAt DESC")
    suspend fun all(): List<LocalBookEntity>

    @Query("SELECT * FROM local_books WHERE id = :id")
    suspend fun get(id: Long): LocalBookEntity?

    @Upsert
    suspend fun upsert(b: LocalBookEntity)

    @Query("DELETE FROM local_books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT MIN(id) FROM local_books")
    suspend fun minId(): Long?

    @Query("UPDATE local_books SET readProgress = :progress, lastReadTime = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float, time: String)

    @Query("UPDATE local_books SET readStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}

@Dao
interface SwipeDao {
    @Query("SELECT * FROM swipe_cards WHERE week = :week ORDER BY position")
    fun observeWeek(week: String): Flow<List<SwipeCardEntity>>

    @Query("SELECT * FROM swipe_cards WHERE week = :week ORDER BY position")
    suspend fun week(week: String): List<SwipeCardEntity>

    @Query("SELECT COUNT(*) FROM swipe_cards WHERE week = :week")
    suspend fun count(week: String): Int

    /** Every idea ever shown: the same book is not offered again in later weeks. */
    @Query("SELECT `key` FROM swipe_cards")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<SwipeCardEntity>)

    @Query("UPDATE swipe_cards SET decision = :decision, decidedAt = :at WHERE week = :week AND `key` = :key")
    suspend fun decide(week: String, key: String, decision: String?, at: Long?)

    @Query("SELECT * FROM swipe_cards WHERE week = :week AND decision IS NOT NULL ORDER BY decidedAt DESC LIMIT 1")
    suspend fun lastDecided(week: String): SwipeCardEntity?
}

@Dao
interface WantedDao {
    @Query("SELECT * FROM wanted ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WantedEntity>>

    @Query("SELECT * FROM wanted ORDER BY addedAt DESC")
    suspend fun all(): List<WantedEntity>

    @Query("SELECT * FROM wanted WHERE `key` = :key")
    suspend fun get(key: String): WantedEntity?

    @Upsert
    suspend fun upsert(w: WantedEntity)

    @Query("DELETE FROM wanted WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("UPDATE wanted SET list = :list WHERE `key` = :key")
    suspend fun setList(key: String, list: String)
}
