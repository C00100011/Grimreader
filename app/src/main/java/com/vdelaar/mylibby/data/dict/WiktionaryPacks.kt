package com.vdelaar.mylibby.data.dict

import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.data.PackEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/**
 * A Wiktionary edition's words in one language, as Kaikki.org extracts them (CC BY-SA). [edition] is the language the
 * definitions are written in: "en" for the English Wiktionary (which describes words of every language), or the
 * language itself for its own Wiktionary.
 */
data class WiktPackInfo(
    val id: String,
    /** Two-letter code of the language the words are in. */
    val language: String,
    val languageName: String,
    val edition: String,
    val url: String,
    /** The download, measured; a size is not announced because the server compresses on the fly. */
    val downloadBytes: Long,
) {
    /** True when the definitions are in the language of the words themselves. */
    val native: Boolean get() = edition == language
}

private fun mb(n: Int) = n * 1_000_000L

private fun english(code: String, name: String, downloadMb: Int) =
    WiktPackInfo("en-$code", code, name, "en", "https://kaikki.org/dictionary/$name/kaikki.org-dictionary-$name.jsonl", mb(downloadMb))

private fun native(code: String, wiktionary: String, name: String, downloadMb: Int) =
    WiktPackInfo("$code-$code", code, name, code, "https://kaikki.org/${wiktionary}wiktionary/$name/kaikki.org-dictionary-$name.jsonl", mb(downloadMb))

/** What can be downloaded; sizes are what the server sent when this list was made. */
val WIKTIONARY_PACKS: List<WiktPackInfo> = listOf(
    english("en", "English", 534),
    english("nl", "Dutch", 31),
    english("de", "German", 99),
    english("fr", "French", 59),
    english("es", "Spanish", 103),
    english("it", "Italian", 77),
    english("pt", "Portuguese", 60),
    native("nl", "nl", "Nederlands", 131),
)

/** The packs that describe words of [language] (two letters), the ones in that language's own words first. */
fun wiktionaryPacksFor(language: String): List<WiktPackInfo> =
    WIKTIONARY_PACKS.filter { it.language == language }.sortedByDescending { it.native }

/** A Wiktionary pack on the device. */
@Serializable
data class InstalledWikt(val id: String, val language: String, val languageName: String, val edition: String, val entries: Int, val bytes: Long)

/** What a pack's index finds for a headword: the stored entries. */
interface WiktIndex {
    fun find(norm: String, plain: String): List<WiktEntry>
    fun close()
}

interface WiktStore {
    /** Builds the index file from the lines [fill] hands over; returns how many entries it holds. */
    fun build(file: File, fill: (add: (word: String, entry: WiktEntry) -> Unit) -> Unit): Int
    fun open(file: File): WiktIndex
}

/** What one pack says about a word: [matched] is the form that was found, [entries] the entries of it and of the word it is a form of. */
class WiktAnswer(val pack: InstalledWikt, val matched: String, val entries: List<WiktEntry>)

/**
 * The Wiktionary dictionaries on this device: installing (download, then reading the file into a small database), removing and
 * looking words up. Lookups never touch the network.
 */
class WiktionaryPacks(
    private val root: File,
    private val downloader: PackDownloader,
    private val store: WiktStore,
) {
    private val _installed = MutableStateFlow(readInstalled())
    val installed: StateFlow<List<InstalledWikt>> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, PackProgress>>(emptyMap())
    val progress: StateFlow<Map<String, PackProgress>> = _progress.asStateFlow()

    private val installLock = Mutex()
    private val open = HashMap<String, WiktIndex>()

    private fun readInstalled(): List<InstalledWikt> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { d ->
            runCatching { AppJson.decodeFromString<InstalledWikt>(File(d, "meta.json").readText()) }.getOrNull()
                ?.takeIf { File(d, "index.db").exists() }
        }.sortedBy { it.id }

    fun hasLanguage(language: String): Boolean = _installed.value.any { it.language == language }

    /** Downloads [info] and reads it into the database. Progress is in [progress]; a failure ends up there too and is rethrown. */
    suspend fun install(info: WiktPackInfo): Unit = installLock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(root, ".tmp-${info.id}")
            try {
                setProgress(info.id, PackProgress.Downloading(0f))
                tmp.deleteRecursively(); tmp.mkdirs()
                val file = File(tmp, "pack.jsonl.gz")
                downloader.download(info.url, file) { done, total ->
                    // The server compresses as it goes and so does not know the size: the measured one stands in.
                    val whole = if (total > 0) total else info.downloadBytes
                    setProgress(info.id, PackProgress.Downloading(if (whole > 0) (done.toFloat() / whole).coerceIn(0f, .99f) else null))
                }
                currentCoroutineContext().ensureActive()
                setProgress(info.id, PackProgress.Preparing(0f))
                var entries = 0
                val job = currentCoroutineContext().job
                try {
                    entries = store.build(File(tmp, "index.db")) { add -> readLines(file, job) { fraction -> setProgress(info.id, PackProgress.Preparing(fraction)) }.forEach { add(it.word, it.entry) } }
                } catch (e: EOFException) { throw IOException("The download is damaged; try again", e) } catch (e: ZipException) { throw IOException("The download is damaged; try again", e) }
                if (entries == 0) throw IOException("The download holds no words")
                file.delete()
                val installed = InstalledWikt(info.id, info.language, info.languageName, info.edition, entries, tmp.listFiles().orEmpty().sumOf { it.length() })
                File(tmp, "meta.json").writeText(AppJson.encodeToString(installed))
                closeHandle(info.id)
                val dir = File(root, info.id)
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw IOException("Could not store the dictionary")
                _installed.update { (it.filter { p -> p.id != info.id } + installed).sortedBy { p -> p.id } }
                _progress.update { it - info.id }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                if (e is CancellationException) _progress.update { it - info.id }
                else setProgress(info.id, PackProgress.Failed(e.message ?: e.javaClass.simpleName))
                throw e
            }
        }
    }

    /** The entries of a downloaded file, lazily; [onProgress] is how much of the file has been read. A gzip is unpacked, plain text is read as it is. */
    private fun readLines(file: File, job: Job, onProgress: (Float) -> Unit): Sequence<WiktLine> = sequence {
        val counting = CountingStream(file.inputStream())
        counting.use {
            val buffered = BufferedInputStream(counting, 1 shl 16)
            buffered.mark(2)
            val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val stream: InputStream = if (gz) GZIPInputStream(buffered, 1 shl 16) else buffered
            val reader = stream.bufferedReader(Charsets.UTF_8)
            val length = file.length().coerceAtLeast(1)
            var n = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (++n % 2000 == 0) { onProgress((counting.count.toFloat() / length).coerceIn(0f, 1f)); if (n % 20000 == 0) job.ensureActive() }
                parseKaikkiLine(line)?.let { yield(it) }
            }
        }
    }

    fun remove(id: String) {
        closeHandle(id)
        File(root, id).deleteRecursively()
        _installed.update { it.filter { p -> p.id != id } }
    }

    /** Shows the download as started before the background work has got going. */
    fun markQueued(id: String) = setProgress(id, PackProgress.Downloading(0f))

    fun dismissFailure(id: String) = _progress.update { if (it[id] is PackProgress.Failed) it - id else it }

    /**
     * What the installed packs for [language] say about [word]: its entries, and where it is only a form ("worms", "plural of
     * worm") those of the word it is a form of. Plain suffix rules supply the likely stems when the word itself is not listed.
     */
    suspend fun lookup(word: String, language: String): List<WiktAnswer> = withContext(Dispatchers.IO) {
        val packs = _installed.value.filter { it.language == language }
        if (packs.isEmpty()) return@withContext emptyList()
        packs.mapNotNull { pack ->
            val index = handle(pack) ?: return@mapNotNull null
            fun find(w: String) = index.find(indexKey(w), plainKey(w))
            for (candidate in stemCandidates(word, language)) {
                val own = find(candidate)
                if (own.isEmpty()) continue
                val forms = own.filter { e -> e.s.all { it.f != null } }
                val direct = own - forms.toSet()
                val lemmas = forms.flatMap { e -> e.s.mapNotNull { it.f } }.distinctBy { it.lowercase() }.take(3)
                    .flatMap { find(it) }.filter { e -> e.s.any { it.f == null } }
                val entries = direct + forms.map { e -> WiktEntry(e.p, e.i, e.s.take(2), e.y, e.a) } + lemmas
                return@mapNotNull WiktAnswer(pack, candidate, entries)
            }
            null
        }
    }

    @Synchronized private fun handle(pack: InstalledWikt): WiktIndex? =
        open[pack.id] ?: runCatching { store.open(File(root, "${pack.id}/index.db")) }.getOrNull()?.also { open[pack.id] = it }

    @Synchronized private fun closeHandle(id: String) { open.remove(id)?.let { runCatching { it.close() } } }

    private fun setProgress(id: String, p: PackProgress) = _progress.update { it + (id to p) }
}

private class CountingStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

/** The answer of a pack in the shape the popover shows, or null when it has nothing to show. [label] is how the pack is named. */
fun WiktAnswer.toPackEntry(label: String): PackEntry? {
    val meanings = entries.toMeanings()
    if (meanings.isEmpty()) return null
    return PackEntry("wikt:${pack.id}", label, matched, entries.firstNotNullOfOrNull { it.i }, meanings, translations = false, source = "Wiktionary")
}
