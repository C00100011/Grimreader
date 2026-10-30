package com.vdelaar.mylibby.data.dict

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

private fun gz(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

/** A line of Kaikki's JSON, with the parts the app ignores included so skipping them is tested too. */
private fun line(word: String, pos: String, senses: String, extra: String = "") =
    """{"word": "$word", "lang_code": "en", "pos": "$pos", "etymology_text": "From x (“y”).", "categories": [{"name": "A", "kind": "other", "parents": []}], $extra "senses": [$senses]}"""

private class MemoryWiktStore : WiktStore {
    val built = HashMap<String, List<Pair<String, WiktEntry>>>()
    override fun build(file: File, fill: (add: (String, WiktEntry) -> Unit) -> Unit): Int {
        val list = ArrayList<Pair<String, WiktEntry>>(); fill { w, e -> list += w to e }
        built[file.parentFile.name.removePrefix(".tmp-")] = list; file.writeText("index")
        return list.size
    }
    override fun open(file: File): WiktIndex {
        val list = built[file.parentFile.name.removePrefix(".tmp-")].orEmpty()
        return object : WiktIndex {
            override fun find(norm: String, plain: String): List<WiktEntry> {
                val exact = list.filter { indexKey(it.first) == norm }
                return exact.ifEmpty { list.filter { plainKey(it.first) == plain } }.map { it.second }
            }
            override fun close() {}
        }
    }
}

class KaikkiLineTest {
    @Test fun keepsGlossesExamplesSynonymsAndPronunciation() {
        val l = parseKaikkiLine(
            line(
                "happy", "adj",
                """{"glosses": ["Feeling pleasure."], "examples": [{"text": "I am happy.", "bold_text_offsets": [[5, 10]]}], "synonyms": [{"word": "glad"}, {"word": "cheerful"}], "antonyms": [{"word": "sad"}], "id": "x"}""",
                """"sounds": [{"audio": "a.ogg"}, {"ipa": "/ˈhæpi/"}], "synonyms": [{"word": "content"}],""",
            ),
        )!!
        assertEquals("happy", l.word)
        assertEquals("adj", l.entry.p)
        assertEquals("/ˈhæpi/", l.entry.i)
        assertEquals("Feeling pleasure.", l.entry.s[0].g)
        assertEquals("I am happy.", l.entry.s[0].e)
        assertEquals(listOf("glad", "cheerful"), l.entry.s[0].y)
        assertEquals(listOf("sad"), l.entry.s[0].a)
        assertEquals(listOf("content"), l.entry.y)
    }

    @Test fun theLastOfNestedGlossesSaysWhatTheSenseIs() {
        val l = parseKaikkiLine(line("run", "verb", """{"glosses": ["To move fast.", "To go on foot quickly."]}"""))!!
        assertEquals("To go on foot quickly.", l.entry.s[0].g)
    }

    @Test fun aFormPointsAtItsLemma() {
        val l = parseKaikkiLine(line("worms", "noun", """{"glosses": ["plural of worm"], "form_of": [{"word": "worm"}]}"""))!!
        assertEquals("worm", l.entry.s[0].f)
        // A word is not a form of itself.
        assertNull(parseKaikkiLine(line("worm", "noun", """{"glosses": ["x"], "form_of": [{"word": "worm"}]}"""))!!.entry.s[0].f)
    }

    @Test fun linesWithNothingToShowAreSkipped() {
        assertNull(parseKaikkiLine(""))
        assertNull(parseKaikkiLine("not json"))
        assertNull(parseKaikkiLine("""{"word": "x", "pos": "noun", "senses": [{"tags": ["no-gloss"]}]}"""))
        assertNull(parseKaikkiLine("""{"pos": "noun", "senses": [{"glosses": ["a"]}]}"""))
    }

    @Test fun meaningsGroupByPartOfSpeechAndGatherSynonyms() {
        val a = WiktEntry("noun", null, listOf(WiktSense("a one", "ex", y = listOf("uno"))), y = listOf("single"))
        val b = WiktEntry("noun", null, listOf(WiktSense("a two")))
        val c = WiktEntry("verb", null, listOf(WiktSense("to do"), WiktSense("to make")))
        val m = listOf(a, b, c).toMeanings()
        assertEquals(listOf("noun", "verb"), m.map { it.partOfSpeech })
        assertEquals(listOf("a one", "a two"), m[0].senses.map { it.text })
        assertEquals("ex", m[0].senses[0].example)
        assertEquals(listOf("single", "uno"), m[0].synonyms)
    }
}

class WiktionaryPacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val worm = line("worm", "noun", """{"glosses": ["A small crawling animal."], "synonyms": [{"word": "grub"}]}""", """"sounds": [{"ipa": "/wɜːm/"}],""")
    private val worms = line("worms", "noun", """{"glosses": ["plural of worm"], "form_of": [{"word": "worm"}]}""")
    private val cafe = line("café", "noun", """{"glosses": ["A coffee house."]}""")
    private val info = WiktPackInfo("en-en", "en", "English", "en", "https://example.org/en.jsonl", 1000)

    private fun packs(content: ByteArray, store: MemoryWiktStore = MemoryWiktStore(), root: File = tmp.newFolder()) =
        WiktionaryPacks(root, { _, target, onProgress -> target.writeBytes(content); onProgress(content.size.toLong(), -1) }, store)

    private fun installed(vararg lines: String): WiktionaryPacks = packs(gz(lines.joinToString("\n"))).also { runBlocking { it.install(info) } }

    @Test fun installingReadsTheDownloadIntoTheIndexAndTidiesUp() = runBlocking {
        val root = tmp.newFolder()
        val p = packs(gz(listOf(worm, worms, "garbage", cafe).joinToString("\n")), root = root)
        p.install(info)
        assertEquals(listOf("en-en"), p.installed.value.map { it.id })
        assertEquals(3, p.installed.value.single().entries)
        assertTrue(p.hasLanguage("en"))
        assertFalse(p.hasLanguage("nl"))
        assertEquals(setOf("en-en"), root.listFiles()!!.map { it.name }.toSet())
        assertEquals(setOf("index.db", "meta.json"), File(root, "en-en").list()!!.toSet())
        assertTrue(p.progress.value.isEmpty())
    }

    @Test fun anInstalledPackIsFoundAgainByANewInstance() = runBlocking {
        val root = tmp.newFolder(); val store = MemoryWiktStore()
        packs(gz(worm), store, root).install(info)
        assertEquals(listOf("en-en"), packs(gz(worm), store, root).installed.value.map { it.id })
    }

    @Test fun aWordIsLookedUpWithItsSynonymsAndPronunciation() = runBlocking {
        val e = installed(worm, worms, cafe).lookup("Worm", "en").single().toPackEntry("English")!!
        assertEquals("worm", e.matched)
        assertEquals("wikt:en-en", e.id)
        assertEquals("/wɜːm/", e.phonetic)
        assertEquals("A small crawling animal.", e.meanings.single().senses.single().text)
        assertEquals(listOf("grub"), e.meanings.single().synonyms)
        assertFalse(e.translations)
        assertEquals("Wiktionary", e.source)
    }

    @Test fun aFormShowsWhatItIsAndTheMeaningsOfItsLemma() = runBlocking {
        val e = installed(worm, worms).lookup("worms", "en").single().toPackEntry("English")!!
        assertEquals("worms", e.matched)
        assertEquals(listOf("plural of worm", "A small crawling animal."), e.meanings.single().senses.map { it.text })
    }

    @Test fun aStemIsTriedWhenTheWordIsNotListed() = runBlocking {
        val answers = installed(worm).lookup("worms", "en")
        assertEquals("worm", answers.single().matched)
    }

    @Test fun accentsAreOptional() = runBlocking {
        assertEquals("A coffee house.", installed(cafe).lookup("cafe", "en").single().toPackEntry("English")!!.meanings.single().senses.single().text)
    }

    @Test fun anUnknownWordOrLanguageFindsNothing() = runBlocking {
        val p = installed(worm)
        assertTrue(p.lookup("zebra", "en").isEmpty())
        assertTrue(p.lookup("worm", "nl").isEmpty())
    }

    @Test fun removingForgetsThePack() = runBlocking {
        val p = installed(worm)
        p.remove("en-en")
        assertTrue(p.installed.value.isEmpty())
        assertTrue(p.lookup("worm", "en").isEmpty())
    }

    @Test fun aPlainTextFileIsReadToo() = runBlocking {
        val p = packs(worm.toByteArray()); p.install(info)
        assertEquals(1, p.installed.value.single().entries)
    }

    @Test fun aTruncatedDownloadFailsAndLeavesNothingBehind() = runBlocking {
        val root = tmp.newFolder()
        val whole = gz((1..500).joinToString("\n") { worm })
        val p = packs(whole.copyOf(whole.size / 2), root = root)
        try { p.install(info); fail("expected a failure") } catch (e: IOException) { assertTrue(e.message!!.contains("damaged")) }
        assertTrue(p.installed.value.isEmpty())
        assertTrue(p.progress.value["en-en"] is PackProgress.Failed)
        assertTrue(root.listFiles()!!.isEmpty())
        p.dismissFailure("en-en")
        assertTrue(p.progress.value.isEmpty())
    }

    @Test fun aFileWithoutWordsFails() = runBlocking {
        val p = packs(gz("nothing here\n"))
        try { p.install(info); fail("expected a failure") } catch (e: IOException) { assertNotNull(e.message) }
        assertTrue(p.installed.value.isEmpty())
    }

    @Test fun theRegistryPointsAtKaikkiAndOwnLanguageEditionsComeFirst() {
        val nl = wiktionaryPacksFor("nl")
        assertEquals(listOf("nl-nl", "en-nl"), nl.map { it.id })
        assertTrue(nl.first().native)
        assertEquals("https://kaikki.org/nlwiktionary/Nederlands/kaikki.org-dictionary-Nederlands.jsonl", nl.first().url)
        assertEquals("https://kaikki.org/dictionary/Dutch/kaikki.org-dictionary-Dutch.jsonl", nl.last().url)
        assertTrue(wiktionaryPacksFor("xx").isEmpty())
    }
}
