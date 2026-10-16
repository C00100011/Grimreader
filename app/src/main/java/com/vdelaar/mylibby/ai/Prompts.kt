package com.vdelaar.mylibby.ai

import com.vdelaar.mylibby.core.database.BookChunkEntity

data class ChatTurn(val fromReader: Boolean, val text: String)

/** The prompts for the on-device model. English on purpose: the model answers in the language of the question. */
object Prompts {
    fun summarySystem() =
        "You write short notes about a passage from a book, for the reader's memory. Write 3 to 5 plain sentences in the language of the passage. " +
            "Keep the names of characters, who is related to whom, places, important objects and what happens. " +
            "Use only what is in the passage. No introduction, no opinions."

    fun summaryPrompt(section: String, text: String) = "Passage (from \"$section\"):\n$text\n\nNotes:"

    fun askSystem(percent: Int) =
        "You are a reading companion inside an e-reader. The reader is $percent% through the book. " +
            "Answer ONLY from the excerpts you are given: they contain only what the reader has already read. " +
            "Never use what you know about the book from elsewhere, never guess what happens later and never hint at later events. " +
            "If the excerpts do not answer the question, say that it has not come up in what the reader has read so far. " +
            "Be brief: at most four sentences. Answer in the language of the question."

    fun label(c: BookChunkEntity) = "[Part ${c.idx + 1} · ${c.section}]"

    fun askPrompt(title: String, authors: String, excerpts: List<BookChunkEntity>, latest: String, history: List<ChatTurn>, question: String): String =
        buildString {
            append("Book: ").append(title)
            if (authors.isNotBlank()) append(" by ").append(authors)
            append("\n\n")
            if (excerpts.isNotEmpty()) {
                append("Notes on what has been read so far:\n")
                excerpts.forEach { c ->
                    append(label(c)).append('\n').append(c.summary ?: c.text.take(UNSUMMARISED_CHARS)).append("\n\n")
                }
            }
            if (latest.isNotBlank()) append("The passage the reader is reading right now (up to where they are):\n").append(latest).append("\n\n")
            if (history.isNotEmpty()) {
                append("Conversation so far:\n")
                history.forEach { append(if (it.fromReader) "Reader: " else "Companion: ").append(it.text.take(400)).append('\n') }
                append('\n')
            }
            append("Question: ").append(question).append("\nAnswer:")
        }

    private const val UNSUMMARISED_CHARS = 700
}
