package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.SwipeCardEntity
import com.vdelaar.mylibby.core.database.WantedEntity
import com.vdelaar.mylibby.core.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.IsoFields

enum class Swipe(val code: String) {
    NO("NO"), YES("YES"), LOVE("LOVE");

    companion object { fun of(code: String?): Swipe? = entries.firstOrNull { it.code == code } }
}

enum class WantList { READ, BUY }

/** "2026-W41": the week a deck belongs to (ISO weeks start on Monday, so a new deck appears every Monday). */
fun weekKey(date: LocalDate): String =
    "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

/** Genres to start from when the library does not say what you like yet. */
internal val STARTER_GENRES = listOf("fantasy", "thriller", "science fiction")

/**
 * The ideas for one week: one list per genre, taken in turn so every genre is in the deck, minus what you already
 * have, what was offered before and what you already want. [size] cards at most.
 */
internal fun buildDeck(perGenre: List<List<BookIdea>>, seenKeys: Set<String>, library: List<Book>, size: Int = 12): List<BookIdea> =
    interleave(perGenre)
        .filter { it.key !in seenKeys && libraryMatch(it.title, it.authors, library) == null }
        .distinctWorks()
        .take(size)

internal fun SwipeCardEntity.toIdea() = BookIdea(
    key = key, title = title,
    authors = authors.split('|').filter { it.isNotBlank() },
    year = year, coverId = coverId, isbn = isbn,
    languages = languages.split(',').filter { it.isNotBlank() },
    subjects = subjects.split('|').filter { it.isNotBlank() },
)

internal fun WantedEntity.toIdea() = BookIdea(
    key = key, title = title,
    authors = authors.split('|').filter { it.isNotBlank() },
    year = year, coverId = coverId, isbn = isbn,
    languages = languages.split(',').filter { it.isNotBlank() },
    subjects = subjects.split('|').filter { it.isNotBlank() },
)

private fun BookIdea.toCard(week: String, position: Int) = SwipeCardEntity(
    week = week, key = key, position = position, title = title,
    authors = authors.joinToString("|"), year = year, coverId = coverId, isbn = isbn,
    languages = languages.joinToString(","), subjects = subjects.joinToString("|"),
)

private fun BookIdea.toWanted(list: WantList, love: Boolean, now: Long) = WantedEntity(
    key = key, title = title, authors = authors.joinToString("|"), year = year, coverId = coverId, isbn = isbn,
    languages = languages.joinToString(","), subjects = subjects.joinToString("|"), list = list.name, love = love, addedAt = now,
)

/**
 * The weekly deck of book ideas to swipe through (right = yes, up = love it, left = no) and the lists the choices
 * end up in. A deck is made once per week and kept, so it does not change when the app is closed.
 */
class SwipeRepository(
    private val db: AppDatabase,
    private val discover: DiscoverRepository,
    private val library: HardcoverLibrary,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun thisWeek(): String = weekKey(today())

    fun deck(): Flow<List<SwipeCardEntity>> = db.swipe().observeWeek(thisWeek())

    val wanted: Flow<List<WantedEntity>> = db.wanted().observeAll()

    /** Makes this week's deck when there is none yet. Returns false when it could not (no connection) and there is no deck. */
    suspend fun ensureDeck(): Boolean = withContext(Dispatchers.IO) {
        val week = thisWeek()
        if (db.swipe().count(week) > 0) return@withContext true
        val taste = discover.taste()
        val perGenre = taste.genres.map { genre -> discover.newBooks(genre, taste.languages, limit = 20) }
        val seen = db.swipe().allKeys().toSet() + db.wanted().all().map { it.key }
        val ideas = buildDeck(perGenre, seen, library.libraryBooks())
        if (ideas.isEmpty()) return@withContext false
        db.swipe().insertAll(ideas.mapIndexed { i, idea -> idea.toCard(week, i) })
        true
    }

    /** Records a swipe. Yes and love go to "Want to read" (love with a heart). */
    suspend fun decide(card: SwipeCardEntity, swipe: Swipe) = withContext(Dispatchers.IO) {
        val at = now()
        db.swipe().decide(card.week, card.key, swipe.code, at)
        if (swipe != Swipe.NO && db.wanted().get(card.key) == null) {
            db.wanted().upsert(card.toIdea().toWanted(WantList.READ, love = swipe == Swipe.LOVE, now = at))
        }
    }

    /** Takes back the last swipe of this week: the card is back in the deck and leaves the list again. */
    suspend fun undo(): SwipeCardEntity? = withContext(Dispatchers.IO) {
        val last = db.swipe().lastDecided(thisWeek()) ?: return@withContext null
        db.swipe().decide(last.week, last.key, null, null)
        // Only a book that still sits where the swipe put it goes away; one the user has moved to "buy" stays.
        db.wanted().get(last.key)?.takeIf { it.list == WantList.READ.name && it.addedAt == last.decidedAt }?.let { db.wanted().delete(last.key) }
        last
    }

    suspend fun move(key: String, list: WantList) = withContext(Dispatchers.IO) { db.wanted().setList(key, list.name) }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) { db.wanted().delete(key) }

    /** Saves an idea straight to a list (from the new-books rows, outside the weekly deck). */
    suspend fun want(idea: BookIdea, list: WantList) = withContext(Dispatchers.IO) {
        val existing = db.wanted().get(idea.key)
        if (existing == null) db.wanted().upsert(idea.toWanted(list, love = false, now = now())) else db.wanted().setList(idea.key, list.name)
    }

    /** The library book behind a wanted idea, when you have it by now. */
    suspend fun libraryBook(idea: BookIdea): Book? = discover.inLibrary(idea)

    fun wantedIn(list: WantList): Flow<List<WantedEntity>> = wanted.map { all -> all.filter { it.list == list.name } }
}
