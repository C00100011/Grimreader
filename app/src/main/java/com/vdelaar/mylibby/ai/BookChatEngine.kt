package com.vdelaar.mylibby.ai

import com.vdelaar.mylibby.core.database.BookChunkDao
import com.vdelaar.mylibby.core.database.BookChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface IndexResult {
    data object Done : IndexResult
    /** Stopped for a reason that goes away on its own (quota, busy, app not in front); try again later. */
    data class Paused(val reason: String) : IndexResult
}

/**
 * The book chat. The book is cut into pieces once; pieces the reader has finished get a short summary written by the on-device
 * model. A question is answered from the summaries that match it best plus the passage being read, and the prompt only ever
 * contains text from before the reader's position, so an answer cannot reveal what comes later.
 */
class BookChatEngine(private val chunks: BookChunkDao, private val ai: LocalAi) {
    /** One request at a time reaches the model; a question waits for at most the summary in progress. */
    private val modelLock = Mutex()

    /** Reads the book into pieces if that has not happened yet. False when the book has no readable text (or is not an EPUB). */
    suspend fun prepare(bookId: Long, file: File): Boolean = withContext(Dispatchers.IO) {
        if (chunks.count(bookId) > 0) return@withContext true
        if (!file.extension.equals("epub", ignoreCase = true)) return@withContext false
        val cut = runCatching { BookChunker.chunk(bookId, EpubText.read(file)) }.getOrDefault(emptyList())
        if (cut.isEmpty()) return@withContext false
        chunks.insertAll(cut)
        true
    }

    suspend fun progress(bookId: Long, at: ReadPosition): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val share = at.share - ReadPosition.SECTION_MARGIN
        chunks.summarizedBefore(bookId, at.spineIndex, share) to chunks.countBefore(bookId, at.spineIndex, share)
    }

    /** Summarises the pieces the reader has finished, oldest first. [position] is read again every round, so it follows the reader. */
    suspend fun summarize(bookId: Long, position: () -> ReadPosition, onProgress: (done: Int, total: Int) -> Unit): IndexResult {
        while (true) {
            val at = position()
            val next = withContext(Dispatchers.IO) { chunks.pending(bookId, at.spineIndex, at.share - ReadPosition.SECTION_MARGIN, 1).firstOrNull() }
            val (done, total) = progress(bookId, at)
            onProgress(done, total)
            next ?: return IndexResult.Done
            val summary = try {
                modelLock.withLock { summarizeOne(next) }
            } catch (e: AiException) {
                if (e.retryable) return IndexResult.Paused(e.message ?: "paused")
                fallbackSummary(next)
            }
            withContext(Dispatchers.IO) { chunks.setSummary(bookId, next.idx, summary) }
        }
    }

    private suspend fun summarizeOne(c: BookChunkEntity): String {
        val limit = ai.tokenLimit()
        var text = c.text
        var prompt = Prompts.summaryPrompt(c.section, text)
        // A dense page of another language can need more tokens than characters / 3; cut the passage until it fits.
        repeat(4) {
            if (ai.countTokens(Prompts.summarySystem() + prompt) <= limit - SUMMARY_TOKENS) return@repeat
            text = text.take(text.length * 3 / 4)
            prompt = Prompts.summaryPrompt(c.section, text)
        }
        val out = ai.generate(Prompts.summarySystem(), prompt, SUMMARY_TOKENS)
        return out.ifBlank { fallbackSummary(c) }
    }

    private fun fallbackSummary(c: BookChunkEntity) = c.text.take(400).substringBeforeLast(' ') + "…"

    /** The answer, or null when the reader has not read anything yet. */
    suspend fun ask(
        bookId: Long, title: String, authors: String, at: ReadPosition,
        history: List<ChatTurn>, question: String, onPartial: (String) -> Unit,
    ): String? {
        if (ai.status() != AiStatus.AVAILABLE) throw AiException("The on-device model is not available", retryable = false)
        val all = withContext(Dispatchers.IO) { chunks.all(bookId) }
        val read = Retriever.readable(all, at)
        val latestFull = Retriever.readPartOfCurrent(all, at)
        if (read.isEmpty() && latestFull.isBlank()) return null

        val matches = Retriever.top(read, question, MAX_EXCERPTS)
        val recent = read.takeLast(2)
        val limit = ai.tokenLimit()
        var k = MAX_EXCERPTS
        var tail = 1800
        var turns = 4
        var prompt: String
        var rounds = 0
        val system = Prompts.askSystem(at.percent.coerceIn(0, 100))
        do {
            val excerpts = (matches.take(k) + recent).distinctBy { it.idx }.sortedBy { it.idx }
            prompt = Prompts.askPrompt(title, authors, excerpts, latestFull.takeLast(tail), history.takeLast(turns), question)
            val fits = ai.countTokens(system + prompt) <= limit - ANSWER_TOKENS
            if (fits) break
            when {
                tail > 600 -> tail -= 400
                turns > 0 -> turns -= 2
                k > 1 -> k--
                else -> tail = (tail / 2).coerceAtLeast(0)
            }
        } while (++rounds < 12)

        val answer = modelLock.withLock { ai.generate(system, prompt, ANSWER_TOKENS, onPartial) }
        return answer.removePrefix("Companion:").trim()
    }

    suspend fun clear(bookId: Long) = withContext(Dispatchers.IO) { chunks.delete(bookId) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { chunks.deleteAll() }

    private companion object {
        const val SUMMARY_TOKENS = 220
        const val ANSWER_TOKENS = 350
        const val MAX_EXCERPTS = 5
    }
}
