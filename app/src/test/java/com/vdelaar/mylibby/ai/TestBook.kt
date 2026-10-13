package com.vdelaar.mylibby.ai

import com.vdelaar.mylibby.core.database.BookChunkDao
import com.vdelaar.mylibby.core.database.BookChunkEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An in-memory stand-in for the Room table. */
class MemoryChunks : BookChunkDao {
    val rows = sortedMapOf<Pair<Long, Int>, BookChunkEntity>(compareBy({ it.first }, { it.second }))
    override suspend fun count(bookId: Long) = rows.values.count { it.bookId == bookId }
    override suspend fun all(bookId: Long) = rows.values.filter { it.bookId == bookId }
    private fun before(c: BookChunkEntity, spine: Int, share: Float) = c.spineIndex < spine || (c.spineIndex == spine && c.endShare <= share)
    override suspend fun pending(bookId: Long, spine: Int, share: Float, n: Int) =
        rows.values.filter { it.bookId == bookId && it.summary == null && before(it, spine, share) }.take(n)
    override suspend fun countBefore(bookId: Long, spine: Int, share: Float) = rows.values.count { it.bookId == bookId && before(it, spine, share) }
    override suspend fun summarizedBefore(bookId: Long, spine: Int, share: Float) =
        rows.values.count { it.bookId == bookId && it.summary != null && before(it, spine, share) }
    override suspend fun insertAll(chunks: List<BookChunkEntity>) { chunks.forEach { rows[it.bookId to it.idx] = it } }
    override suspend fun setSummary(bookId: Long, idx: Int, summary: String) { rows[bookId to idx] = rows.getValue(bookId to idx).copy(summary = summary) }
    override suspend fun delete(bookId: Long) { rows.keys.removeAll { it.first == bookId } }
    override suspend fun deleteAll() { rows.clear() }
    override fun observeStats() = kotlinx.coroutines.flow.flowOf(
        rows.values.groupBy { it.bookId }.map { (id, cs) -> com.vdelaar.mylibby.core.database.BookNotesRow(id, cs.size, cs.count { it.summary != null }, cs.sumOf { (it.text.length + (it.summary?.length ?: 0)).toLong() }) }
    )
    override suspend fun notes(bookId: Long) = rows.values.filter { it.bookId == bookId && it.summary != null }.map { com.vdelaar.mylibby.core.database.NoteRow(it.idx, it.section, it.summary!!) }
}

/** A ten-chapter whodunit as a real EPUB file (OPF, spine, XHTML with entities and a nav page). */
object TestBook {
    val chapters = listOf(
        "Peter arrives at Harrow House by the evening train. Peter is the brother of Anna, who owns the house. The rain does not stop.",
        "Anna shows Peter the library. The old butler, Mr Hale, serves tea &amp; says nothing. Anna&#8217;s husband Victor is away in London.",
        "At dinner Victor returns unexpectedly. Victor and Peter argue about money. Anna leaves the table in tears.",
        "In the night a shot is heard. Victor is found dead in the library. The police arrive at dawn.",
        "The inspector asks about the weapon. A brass candlestick is missing from the mantelpiece. Peter says he saw nothing.",
        "The candlestick is found in the greenhouse, hidden under a flowerpot. Anna recognises it at once.",
        "Mr Hale admits he was in the library that night. He says Victor was already dead when he came in.",
        "Peter confesses he lied: he met Victor at midnight to ask for money. They fought, but Victor was alive when Peter left.",
        "The inspector examines the candlestick. The blood on it belongs to Victor. The fingerprints belong to Anna.",
        "In the end Anna confesses that she killed Victor with the candlestick because he planned to sell Harrow House.",
    )

    fun filler(i: Int) = (1..14).joinToString(" ") { "The corridor of chapter ${i + 1} was cold and quiet, and the clock ticked on the wall (note $it)." }

    fun epub(dir: File): File {
        val f = File(dir, "whodunit.epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest = StringBuilder("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            // A cover page comes first in the spine: it is skipped as text but still counts as spine item 0, as in the reader.
            manifest.append("""<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""")
            val spine = StringBuilder("""<itemref idref="cover"/>""")
            chapters.forEachIndexed { i, _ ->
                manifest.append("""<item id="c$i" href="text/ch$i.xhtml" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="c$i"/>""")
            }
            put("OEBPS/content.opf", """<package><manifest>$manifest</manifest><spine>$spine</spine></package>""")
            put("OEBPS/cover.xhtml", "<html><body><img src=\"c.jpg\"/></body></html>")
            put("OEBPS/nav.xhtml", "<html><body><nav>Contents of the whole book with plenty of words in it so that it would count if it were not skipped</nav></body></html>")
            chapters.forEachIndexed { i, text ->
                put(
                    "OEBPS/text/ch$i.xhtml",
                    """<html><head><title>x</title><style>p{}</style></head><body><h2>Chapter ${i + 1}</h2><p>$text</p><p>${filler(i)}</p></body></html>""",
                )
            }
        }
        return f
    }
}
