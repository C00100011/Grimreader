package com.vdelaar.mylibby.data.dict

import com.vdelaar.mylibby.data.DictionaryHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** A tar archive as bytes: (name, content) pairs, ustar headers. */
private fun tar(vararg files: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((name, data) in files) {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h)
        "0000644\u0000".toByteArray().copyInto(h, 100)
        "%011o\u0000".format(data.size).toByteArray().copyInto(h, 124)
        h[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray().copyInto(h, 257)
        out.write(h); out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}

private fun xz(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options(1)).use { it.write(bytes) } }.toByteArray()
private fun gz(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

/** A dictzip file: gzip whose deflate data is cut in [chunk]-byte pieces, listed in the `RA` header field. */
private fun dictzip(data: ByteArray, chunk: Int): ByteArray {
    val pieces = data.toList().chunked(chunk).map { it.toByteArray() }
    val compressed = pieces.map { p ->
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(p)
        val buf = ByteArray(p.size + 64)
        val o = ByteArrayOutputStream()
        // Full flush ends a chunk without ending the stream, like the dictzip tool does.
        while (true) {
            val n = d.deflate(buf, 0, buf.size, Deflater.FULL_FLUSH)
            o.write(buf, 0, n)
            if (n < buf.size) break
        }
        d.end(); o.toByteArray()
    }
    val out = ByteArrayOutputStream()
    val ra = ByteArrayOutputStream()
    fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
    le16(ra, 1); le16(ra, chunk); le16(ra, compressed.size); compressed.forEach { le16(ra, it.size) }
    out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 4, 0, 0, 0, 0, 0, 3))
    le16(out, ra.size() + 4)
    out.write('R'.code); out.write('A'.code); le16(out, ra.size()); out.write(ra.toByteArray())
    compressed.forEach { out.write(it) }
    val last = Deflater(Deflater.DEFAULT_COMPRESSION, true).also { it.finish() }
    val tail = ByteArray(16); val n = last.deflate(tail); out.write(tail, 0, n)
    val crc = CRC32().also { it.update(data) }.value.toInt()
    le16(out, crc and 0xffff); le16(out, crc ushr 16); le16(out, data.size and 0xffff); le16(out, data.size ushr 16)
    return out.toByteArray()
}

private fun idx(vararg entries: Triple<String, Long, Int>, bits: Int = 32): ByteArray {
    val o = ByteArrayOutputStream()
    for ((w, off, size) in entries) {
        o.write(w.toByteArray()); o.write(0)
        for (i in (bits / 8 - 1) downTo 0) o.write((off shr (8 * i)).toInt() and 0xff)
        for (i in 3 downTo 0) o.write(size shr (8 * i) and 0xff)
    }
    return o.toByteArray()
}

class StarDictFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun tarEntriesAreReadWithTheirNamesAndPartlyReadOnesAreSkipped() {
        val a = ByteArray(1000) { 1 }; val b = "second".toByteArray()
        val seen = ArrayList<String>()
        TarReader(ByteArrayInputStream(tar("dir/a.bin" to a, "dir/b.txt" to b))).forEach { e, s ->
            seen += e.name + ":" + e.size
            if (e.name.endsWith("b.txt")) assertEquals("second", String(s.readBytes()))
            else assertEquals(10, s.readNBytes(10).size) // not read to the end
        }
        assertEquals(listOf("dir/a.bin:1000", "dir/b.txt:6"), seen)
    }

    @Test fun anXzCompressedTarIsReadThroughTheDecoder() {
        val bytes = xz(tar("p/p.ifo" to "bookname=Test\nwordcount=2\n".toByteArray()))
        val names = ArrayList<String>()
        org.tukaani.xz.XZInputStream(ByteArrayInputStream(bytes)).use { TarReader(it).forEach { e, _ -> names += e.name } }
        assertEquals(listOf("p/p.ifo"), names)
    }

    @Test fun ifoAndIndexAreParsedInBothOffsetSizes() {
        val ifo = parseIfo("StarDict's dict ifo file\nversion=3.0.0\nbookname=English-Dutch FreeDict Dictionary (en-nl)\nwordcount=7714\nidxoffsetbits=64\nsametypesequence=h\n")
        assertEquals("English-Dutch FreeDict Dictionary (en-nl)", ifo.bookName); assertEquals(7714, ifo.wordCount); assertEquals(64, ifo.offsetBits)
        assertEquals(32, parseIfo("bookname=x").offsetBits)

        val got32 = ArrayList<String>(); val got64 = ArrayList<String>()
        readIdx(ByteArrayInputStream(idx(Triple("body", 10, 20), Triple("café", 70000, 5))), 32) { got32 += "${it.word}@${it.offset}+${it.size}" }
        readIdx(ByteArrayInputStream(idx(Triple("body", 5_000_000_000, 20), bits = 64)), 64) { got64 += "${it.word}@${it.offset}+${it.size}" }
        assertEquals(listOf("body@10+20", "café@70000+5"), got32)
        assertEquals(listOf("body@5000000000+20"), got64)
    }

    @Test fun dictzipIsReadAnywhereByInflatingOnlyTheChunksNeeded() {
        val data = ByteArray(100_000) { (it * 31 % 251).toByte() }
        val f = tmp.newFile("d.dict.dz").also { it.writeBytes(dictzip(data, 16_384)) }
        val dz = DictzipDict.open(f)!!
        assertTrue(dz.read(0, 100).contentEquals(data.copyOfRange(0, 100)))
        assertTrue(dz.read(16_380, 10).contentEquals(data.copyOfRange(16_380, 16_390)))          // across a chunk border
        assertTrue(dz.read(10_000, 50_000).contentEquals(data.copyOfRange(10_000, 60_000)))      // over several chunks
        assertTrue(dz.read(99_990, 10).contentEquals(data.copyOfRange(99_990, 100_000)))         // the end
        assertEquals(0, dz.read(5, 0).size)
        dz.close()
    }

    @Test fun aPlainGzipIsNotADictzip() {
        val f = tmp.newFile("x.dz").also { it.writeBytes(gz("hello".toByteArray())) }
        assertNull(DictzipDict.open(f))
        val out = tmp.newFile("x.raw"); gunzipTo(f, out)
        assertEquals("hello", out.readText())
    }
}

private class MemoryIndexStore : PackIndexStore {
    val built = HashMap<String, List<IdxEntry>>()
    override fun build(file: File, fill: (add: (IdxEntry) -> Unit) -> Unit) {
        val list = ArrayList<IdxEntry>(); fill { list += it }
        built[file.parentFile.name.removePrefix(".tmp-")] = list; file.writeText("index")
    }
    override fun open(file: File): PackIndex {
        val list = built[file.parentFile.name.removePrefix(".tmp-")].orEmpty()
        return object : PackIndex {
            override fun find(norm: String, plain: String): List<Located> {
                val exact = list.filter { indexKey(it.word) == norm }
                return (exact.ifEmpty { list.filter { plainKey(it.word) == plain } }).map { Located(it.offset, it.size) }
            }
            override fun close() {}
        }
    }
}

class DictionaryPacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bodies = listOf(
        "body" to """<div>/<font color="gray">ˈbɑ.di</font>/<br><div><font class="grammar" color="green">noun</font></div><ol><li><div>lichaam</div></li><li><div>romp</div></li></ol></div>""",
        "café" to """<div><div>koffiehuis</div></div>""",
    )

    private fun packBytes(): ByteArray {
        val dict = ByteArrayOutputStream(); val entries = ArrayList<Triple<String, Long, Int>>()
        for ((w, html) in bodies) { val b = html.toByteArray(); entries += Triple(w, dict.size().toLong(), b.size); dict.write(b) }
        return xz(tar(
            "eng-nld/eng-nld.ifo" to "StarDict's dict ifo file\nbookname=English-Dutch test\nwordcount=2\nsametypesequence=h\n".toByteArray(),
            "eng-nld/eng-nld.idx.gz" to gz(idx(*entries.toTypedArray())),
            "eng-nld/eng-nld.dict.dz" to dictzip(dict.toByteArray(), 64),
            "eng-nld/README" to "x".toByteArray(),
        ))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b).joinToString("") { "%02x".format(it) }

    private fun info(bytes: ByteArray, checksum: String = sha(bytes)) = PackInfo("eng-nld", "eng", "nld", 2, "0.2", "https://x/eng-nld.tar.xz", bytes.size.toLong(), checksum)

    private fun packs(bytes: ByteArray, http: DictionaryHttp = DictionaryHttp { null }, store: MemoryIndexStore = MemoryIndexStore(), now: () -> Long = { 1_000L }) =
        DictionaryPacks(tmp.root, http, { _, target, onProgress -> target.writeBytes(bytes); onProgress(bytes.size.toLong(), bytes.size.toLong()) }, store, now)

    @Test fun installingUnpacksIndexesAndMakesWordsLookUpable() = runBlocking {
        val bytes = packBytes()
        val p = packs(bytes)
        assertTrue(p.installed.value.isEmpty())
        p.install(info(bytes))
        val installed = p.installed.value.single()
        assertEquals("English-Dutch test", installed.bookName); assertTrue(installed.bytes > 0)
        assertTrue(p.progress.value.isEmpty())
        assertTrue(p.hasSource("eng")); assertFalse(p.hasSource("nld"))
        assertFalse(File(tmp.root, "eng-nld/pack.tar.xz").exists()) // the archive itself is gone

        val answer = p.lookup("Body", "eng", "en").single()
        assertEquals("body", answer.matched)
        val entry = answer.entries.single()
        assertEquals("noun", entry.partOfSpeech); assertEquals("/ˈbɑ.di/", entry.phonetic)
        assertEquals(listOf(listOf("lichaam"), listOf("romp")), entry.senses.map { it.translations })
        val pe = answer.toPackEntry()
        assertEquals("lichaam", pe.meanings.single().senses.first().text)
    }

    @Test fun inflectedAndUnaccentedFormsFindTheirHeadword() = runBlocking {
        val bytes = packBytes(); val p = packs(bytes); p.install(info(bytes))
        assertEquals("body", p.lookup("bodies", "eng", "en").single().matched)
        assertEquals("koffiehuis", p.lookup("cafe", "eng", "en").single().entries.single().senses.single().translations.single())
        assertTrue(p.lookup("zzzz", "eng", "en").isEmpty())
        assertTrue(p.lookup("body", "nld", "nl").isEmpty()) // another language: nothing installed for it
    }

    @Test fun aDamagedDownloadIsRefusedAndLeavesNothingBehind() = runBlocking {
        val bytes = packBytes(); val p = packs(bytes)
        try { p.install(info(bytes, checksum = "00".repeat(64))); fail() } catch (e: IOException) { }
        assertTrue(p.installed.value.isEmpty())
        assertTrue(p.progress.value["eng-nld"] is PackProgress.Failed)
        assertFalse(File(tmp.root, ".tmp-eng-nld").exists())
        p.dismissFailure("eng-nld")
        assertTrue(p.progress.value.isEmpty())
    }

    @Test fun installedPacksSurviveARestartAndCanBeRemoved() = runBlocking {
        val bytes = packBytes(); val store = MemoryIndexStore()
        packs(bytes, store = store).install(info(bytes))
        val again = packs(bytes, store = store)
        assertEquals(listOf("eng-nld"), again.installed.value.map { it.name })
        assertEquals("body", again.lookup("body", "eng", "en").single().matched)
        again.remove("eng-nld")
        assertTrue(again.installed.value.isEmpty()); assertFalse(File(tmp.root, "eng-nld").exists())
    }

    @Test fun theCatalogListsStardictPacksAndIsKeptForAWeek() = runBlocking {
        val json = """[{"name":"eng-nld","headwords":"7714","releases":[{"platform":"slob","URL":"u0","size":"1","version":"1","checksum":"c"},
            {"platform":"stardict","URL":"https://d/eng-nld.tar.xz","size":"247000","version":"0.2","checksum":"abc"}]},
            {"name":"bad","releases":[]},{"name":"x-y-z","releases":[{"platform":"stardict","URL":"u"}]},{"releases":[]}]"""
        var asked = 0; var offline = false; var clock = 1_000L
        val http = DictionaryHttp { asked++; if (offline) throw IOException("off") else json }
        val p = packs(ByteArray(0), http, now = { clock })
        val cat = p.catalog()
        assertEquals(1, cat.size)
        assertEquals(PackInfo("eng-nld", "eng", "nld", 7714, "0.2", "https://d/eng-nld.tar.xz", 247000, "abc"), cat.single())
        p.catalog(); assertEquals(1, asked)                       // cached
        clock += 8L * 24 * 3600 * 1000; offline = true
        assertEquals(1, p.catalog().size)                          // stale copy beats nothing
    }

    @Test fun withoutACatalogOfflineIsAnError() = runBlocking {
        val p = packs(ByteArray(0), DictionaryHttp { throw IOException("off") })
        try { p.catalog(); fail() } catch (e: IOException) { }
    }
}

class StemAndOfferTest {
    @Test fun englishFormsLeadBackToTheirBase() {
        assertTrue("body" in stemCandidates("bodies", "en")); assertTrue("run" in stemCandidates("running", "en"))
        assertTrue("like" in stemCandidates("liked", "en")); assertTrue("stop" in stemCandidates("stopped", "en"))
        assertTrue("pale" in stemCandidates("paler", "en")); assertTrue("worm" in stemCandidates("worms", "en"))
        assertEquals("pallid", stemCandidates("Pallid", "en").first())
        assertTrue("jack" in stemCandidates("jack's", "en"))
    }

    @Test fun dutchAndSpanishFormsToo() {
        assertTrue("hond" in stemCandidates("honden", "nl")); assertTrue("man" in stemCandidates("mannen", "nl")); assertTrue("boek" in stemCandidates("boeken", "nl"))
        assertTrue("hablar" in stemCandidates("hablando", "es")); assertTrue("gato" in stemCandidates("gatos", "es"))
    }

    @Test fun nothingSillyComesOutOfShortWords() {
        assertEquals(listOf("is"), stemCandidates("is", "en"))
        assertTrue(stemCandidates("sheep", "en").size <= 14)
    }

    @Test fun languageCodesBecomeFreedictCodes() {
        assertEquals("eng", iso3("en")); assertEquals("nld", iso3("nl")); assertEquals("spa", iso3("es-ES")); assertEquals("deu", iso3("de")); assertEquals("nld", iso3("nld"))
    }

    @Test fun offersPutTheReadersLanguageThenEnglishFirst() {
        fun p(n: String, h: Int) = PackInfo(n, n.take(3), n.drop(4), h, "1", "u", 1, "")
        val cat = listOf(p("eng-spa", 64000), p("eng-nld", 7700), p("eng-deu", 460000), p("eng-fra", 8800), p("fra-eng", 8500), p("eng-ita", 1))
        assertEquals(listOf("eng-nld", "eng-deu", "eng-spa", "eng-fra"), recommendPacks(cat, "eng", "nld").map { it.name })
        assertEquals(listOf("fra-eng"), recommendPacks(cat, "fra", "nld").map { it.name })
        assertEquals("247 KB", packSize(247_000)); assertEquals("4.9 MB", packSize(4_892_000))
    }
}
