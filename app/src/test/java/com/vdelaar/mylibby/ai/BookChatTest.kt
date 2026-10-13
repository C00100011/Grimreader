package com.vdelaar.mylibby.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookChatTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Chapter n (1-based) is spine item n: the cover is item 0. */
    private fun at(chapter: Int, share: Float = 0f, percent: Int = 50) = ReadPosition(chapter, share, percent)

    private val later = listOf("greenhouse", "Mr Hale admits", "confesses", "blood on it", "sell Harrow House", "fingerprints", "corridor of chapter 6")

    @Test fun readsEpubInReadingOrderAndSkipsNavAndCover() {
        val sections = EpubText.read(TestBook.epub(tmp.root))
        assertEquals(10, sections.size)
        assertEquals("Chapter 1", sections.first().title)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), sections.map { it.spineIndex })
        assertTrue(sections[1].text.contains("tea & says nothing")) // entities decoded
        assertTrue(sections[1].text.contains("Anna’s husband"))
        assertFalse(sections.any { it.text.contains("Contents of the whole book") })
        assertFalse(sections.any { it.text.contains("p{}") }) // style stripped
    }

    @Test fun chunksStayInsideTheirSection() {
        val chunks = BookChunker.chunk(1, EpubText.read(TestBook.epub(tmp.root)))
        assertEquals(10, chunks.size)
        chunks.forEachIndexed { i, c ->
            assertEquals(i, c.idx)
            assertEquals(i + 1, c.spineIndex)
            assertEquals(0f, c.startShare, 0f)
            assertEquals(1f, c.endShare, 0.01f)
        }
        assertTrue(chunks.all { it.text.length <= BookChunker.TARGET_CHARS })
    }

    @Test fun longSectionsSplitWithGrowingShares() {
        val long = TextSection("Long", (1..200).joinToString("\n") { "Paragraph $it " + "word ".repeat(40) }, spineIndex = 3)
        val chunks = BookChunker.chunk(1, listOf(long))
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.spineIndex == 3 && it.text.length <= BookChunker.TARGET_CHARS })
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(a.endShare <= b.startShare + 0.001f) }
        assertEquals(1f, chunks.last().endShare, 0.01f)
    }

    @Test fun retrieverNeverReturnsWhatIsAhead() {
        val chunks = BookChunker.chunk(1, EpubText.read(TestBook.epub(tmp.root)))
        val read = Retriever.readable(chunks, at(6)) // start of chapter 6
        assertEquals(5, read.size)
        assertTrue(read.all { it.spineIndex < 6 })
        // Half way into chapter 6 the chapter itself is still not finished, so it stays out
        assertTrue(Retriever.readable(chunks, at(6, 0.5f)).none { it.spineIndex >= 6 })
        // A chapter counts as read once the reader is in the next one
        assertTrue(Retriever.readable(chunks, at(7)).any { it.spineIndex == 6 })
        assertTrue(Retriever.top(read, "who killed Victor with the candlestick", 5).all { it in read })
        assertTrue(Retriever.top(chunks, "Peter brother Anna", 1).first().text.contains("brother of Anna"))
    }

    private fun engine(ai: FakeLocalAi, rows: MemoryChunks = MemoryChunks()) = BookChatEngine(rows, ai) to rows

    @Test fun summarisesOnlyWhatWasRead() = runBlocking {
        val (engine, rows) = engine(FakeLocalAi())
        assertTrue(engine.prepare(7, TestBook.epub(tmp.root)))
        val result = engine.summarize(7, { at(5, 0.5f) }) { _, _ -> }
        assertEquals(IndexResult.Done, result)
        rows.rows.values.forEach { c -> assertEquals("chunk ${c.idx}", c.spineIndex < 5, c.summary != null) }
    }

    @Test fun promptContainsNothingFromAfterTheReadersPosition() = runBlocking {
        val ai = FakeLocalAi()
        val (engine, _) = engine(ai)
        engine.prepare(7, TestBook.epub(tmp.root))
        val here = at(4, 0.5f, percent = 35) // half way through chapter 4: Victor has just been found dead
        engine.summarize(7, { here }) { _, _ -> }
        val answer = engine.ask(7, "Harrow House", "A. Writer", here, emptyList(), "Who killed Victor and with what?") {}
        assertNotNull(answer)
        val (system, prompt) = ai.requests.last()
        for (word in later) assertFalse("leaked: $word", prompt.contains(word))
        assertTrue(prompt.contains("shot is heard")) // chapter 4, which the reader is in, up to where they are
        assertTrue(system.contains("35%"))
    }

    @Test fun theChapterBeingStartedIsNotUsed() = runBlocking {
        val ai = FakeLocalAi()
        val (engine, _) = engine(ai)
        engine.prepare(7, TestBook.epub(tmp.root))
        val here = at(4, 0f) // first page of chapter 4
        engine.summarize(7, { here }) { _, _ -> }
        engine.ask(7, "T", "", here, emptyList(), "What happens to Victor?") {}
        val prompt = ai.requests.last().second
        assertFalse(prompt.contains("shot is heard"))
        assertTrue(prompt.contains("Victor and Peter argue")) // chapter 3 is done
    }

    @Test fun answersFromEarlierPartsWhenAsked() = runBlocking {
        val ai = FakeLocalAi()
        val (engine, _) = engine(ai)
        engine.prepare(7, TestBook.epub(tmp.root))
        val here = at(10, 0f, 90)
        engine.summarize(7, { here }) { _, _ -> }
        val answer = engine.ask(7, "Harrow House", "", here, emptyList(), "Where was the candlestick found?") {}!!
        assertTrue(answer.startsWith("Simulated answer"))
        val prompt = ai.requests.last().second
        assertTrue(prompt.contains("greenhouse"))
        assertFalse(prompt.contains("sell Harrow House")) // the last chapter is still unread
    }

    @Test fun nothingReadMeansNoAnswer() = runBlocking {
        val (engine, _) = engine(FakeLocalAi())
        engine.prepare(7, TestBook.epub(tmp.root))
        assertNull(engine.ask(7, "T", "", at(0, 0f, 0), emptyList(), "Who is Peter?") {})
    }

    @Test fun refusesWhenTheModelIsNotAvailable() {
        val (engine, _) = engine(FakeLocalAi(status = AiStatus.UNAVAILABLE))
        val e = runCatching { runBlocking { engine.ask(7, "T", "", at(5), emptyList(), "q") {} } }.exceptionOrNull()
        assertTrue(e is AiException && !e.retryable)
    }

    @Test fun quotaPausesIndexingInsteadOfFailing() = runBlocking {
        val busy = object : LocalAi by FakeLocalAi() {
            override suspend fun generate(system: String, prompt: String, maxOutputTokens: Int, onPartial: (String) -> Unit): String =
                throw AiException("quota", retryable = true)
        }
        val rows = MemoryChunks()
        val engine = BookChatEngine(rows, busy)
        engine.prepare(7, TestBook.epub(tmp.root))
        val result = engine.summarize(7, { at(10) }) { _, _ -> }
        assertTrue(result is IndexResult.Paused)
        assertTrue(rows.rows.values.all { it.summary == null })
    }

    @Test fun nonEpubIsNotPrepared() = runBlocking {
        val (engine, _) = engine(FakeLocalAi())
        assertFalse(engine.prepare(7, tmp.newFile("book.mobi")))
    }

    @Test fun longQuestionsAndHistoryStayWithinTheTokenLimit() = runBlocking {
        val ai = FakeLocalAi()
        val (engine, _) = engine(ai)
        engine.prepare(7, TestBook.epub(tmp.root))
        engine.summarize(7, { at(10) }) { _, _ -> }
        val history = (1..10).map { ChatTurn(it % 2 == 0, "x".repeat(900)) }
        engine.ask(7, "T", "", at(10), history, "Tell me everything about Peter and Anna and Victor and the candlestick ".repeat(3)) {}
        val (system, prompt) = ai.requests.last()
        assertTrue(ai.countTokens(system + prompt) <= 4000 - 350)
    }

    @Test fun notesCanBeDeletedPerBookOrAllAtOnce() = runBlocking {
        val (engine, rows) = engine(FakeLocalAi())
        engine.prepare(1, TestBook.epub(tmp.newFolder()))
        engine.prepare(2, TestBook.epub(tmp.newFolder()))
        engine.summarize(1, { at(5) }) { _, _ -> }
        assertTrue(rows.notes(1).isNotEmpty())
        engine.clear(1)
        assertEquals(0, rows.count(1))
        assertTrue(rows.count(2) > 0)
        engine.clearAll()
        assertEquals(0, rows.count(2))
        // after deleting, the next chat rebuilds the pieces and the notes
        assertTrue(engine.prepare(1, TestBook.epub(tmp.newFolder())))
        assertEquals(IndexResult.Done, engine.summarize(1, { at(5) }) { _, _ -> })
        assertTrue(rows.notes(1).isNotEmpty())
    }
}
