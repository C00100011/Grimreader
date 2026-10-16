package com.vdelaar.mylibby.ai

import com.vdelaar.mylibby.core.database.BookChunkEntity

/**
 * Cuts a book into pieces small enough to summarise in one go (the on-device model reads about 4,000 tokens at a time).
 * A piece never crosses a section (chapter) boundary and remembers where it sits inside its section, which is what lets the
 * chat keep answers free of spoilers: the reader reports the section and page it is on, and everything after that is left out.
 */
object BookChunker {
    const val TARGET_CHARS = 4500

    fun chunk(bookId: Long, sections: List<TextSection>): List<BookChunkEntity> {
        val out = ArrayList<BookChunkEntity>()
        for (section in sections) {
            val total = (section.text.length + 1).toFloat()
            var offset = 0
            var cur = StringBuilder()
            var curStart = 0

            fun emit() {
                if (cur.isEmpty()) return
                out += BookChunkEntity(
                    bookId, out.size, section.title, section.spineIndex,
                    curStart / total, ((curStart + cur.length) / total).coerceAtMost(1f), cur.toString(), null,
                )
                cur = StringBuilder()
            }

            for (piece in split(section.text)) {
                if (cur.isNotEmpty() && cur.length + piece.length + 1 > TARGET_CHARS) emit()
                if (cur.isEmpty()) curStart = offset
                else cur.append('\n')
                cur.append(piece)
                offset += piece.length + 1
            }
            emit()
        }
        return out
    }

    /** Paragraphs, with anything longer than a chunk cut at sentence ends. */
    private fun split(text: String): List<String> = text.split('\n').filter { it.isNotBlank() }.flatMap { p ->
        if (p.length <= TARGET_CHARS) listOf(p) else {
            val parts = ArrayList<String>()
            var cur = StringBuilder()
            for (sentence in p.split(Regex("(?<=[.!?…])\\s+"))) {
                if (cur.length + sentence.length + 1 > TARGET_CHARS && cur.isNotEmpty()) { parts += cur.toString(); cur = StringBuilder() }
                if (sentence.length > TARGET_CHARS) { sentence.chunked(TARGET_CHARS).forEach { parts += it } } else {
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(sentence)
                }
            }
            if (cur.isNotEmpty()) parts += cur.toString()
            parts
        }
    }
}
