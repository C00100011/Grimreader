package com.vdelaar.mylibby.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Grimmory's `/api/v1/app` routes report a book's `readProgress` as the position of the first reader that has one:
 * KOReader, then Kobo, then the web reader. KOReader's position is stored as a fraction (0.55 = 55%) while the others
 * are percentages (55.0), and the route hands it on unchanged. So a book at 55% in KOReader showed up as 0.55%, "0%" in
 * the app (issue #4). The newer `/books` API converts it; these routes do not.
 *
 * `koreaderProgress.percentage` carries the same raw number, so it says where the value came from. A value of 1 or less
 * is only a fraction when it equals KOReader's number; the web reader's own percentage (`epubProgress`), when there is
 * one, settles the doubtful cases (a book at 0.5% in the web reader is not 50%).
 */
internal fun koreaderIsFraction(overall: Float?, koreader: Float?, web: Float?): Boolean {
    if (overall == null || koreader == null) return false
    if (koreader <= 0f || koreader > 1f || abs(overall - koreader) > 1e-4f) return false
    if (web == null) return true
    // KOReader's sync also writes the web reader's percentage (fraction * 100); if that is what is there, it is a fraction.
    return abs(web - koreader * 100f) <= 2f || abs(web - koreader) > 2f
}

internal fun BookProgressResponseDto.withPercentages(): BookProgressResponseDto =
    if (koreaderIsFraction(readProgress, koreaderProgress?.percentage, epubProgress?.percentage)) copy(readProgress = readProgress!! * 100f) else this

internal fun BookDetailDto.withPercentages(): BookDetailDto =
    if (koreaderIsFraction(readProgress, koreaderProgress?.percentage, epubProgress?.percentage)) copy(readProgress = readProgress!! * 100f) else this

/** A summary only has the overall number, so a value of 1 or less could be either; the book's own progress decides. */
private fun BookSummaryDto.doubtful() = readProgress?.let { it > 0f && it <= 1f } == true

/**
 * The regular [GrimmoryApi] with the KOReader fraction (see [koreaderIsFraction]) turned into a percentage in everything
 * it returns, so the rest of the app only ever sees percentages.
 */
internal class KoreaderFixedApi(private val api: GrimmoryApi) : GrimmoryApi by api {
    /** What was decided for a doubtful book: the raw number it had, and the percentage it really is. Saves asking again. */
    private val decided = ConcurrentHashMap<Long, Pair<Float, Float>>()

    override suspend fun books(
        page: Int, size: Int, sort: String?, dir: String?, search: String?, shelfId: Long?, authors: List<String>?,
        series: List<String>?, category: List<String>?, language: List<String>?, status: List<String>?, fileType: List<String>?,
    ): PageResponse<BookSummaryDto> =
        api.books(page, size, sort, dir, search, shelfId, authors, series, category, language, status, fileType).let { it.copy(content = fix(it.content)) }

    override suspend fun seriesBooks(name: String, page: Int, size: Int): PageResponse<BookSummaryDto> =
        api.seriesBooks(name, page, size).let { it.copy(content = fix(it.content)) }

    override suspend fun continueReading(limit: Int): List<BookSummaryDto> = fix(api.continueReading(limit))

    override suspend fun recentlyAdded(limit: Int): List<BookSummaryDto> = fix(api.recentlyAdded(limit))

    override suspend fun bookDetail(id: Long): BookDetailDto = api.bookDetail(id).withPercentages()

    override suspend fun bookProgress(id: Long): BookProgressResponseDto = api.bookProgress(id).withPercentages()

    private suspend fun fix(books: List<BookSummaryDto>): List<BookSummaryDto> {
        val doubtful = books.filter { it.doubtful() }
        if (doubtful.isEmpty()) return books
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            doubtful.filter { decided[it.id]?.first != it.readProgress }.take(MAX_CHECKS).map { b ->
                async {
                    gate.withPermit {
                        // If the lookup fails the number stays as the server gave it.
                        runCatching { api.bookProgress(b.id) }.getOrNull()?.let { decided[b.id] = b.readProgress!! to it.withPercentages().readProgress!! }
                    }
                }
            }.forEach { it.await() }
        }
        return books.map { b -> decided[b.id]?.takeIf { b.doubtful() && it.first == b.readProgress }?.let { b.copy(readProgress = it.second) } ?: b }
    }

    private companion object {
        const val PARALLEL = 4
        const val MAX_CHECKS = 40
    }
}
