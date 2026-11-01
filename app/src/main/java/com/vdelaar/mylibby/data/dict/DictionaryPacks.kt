package com.vdelaar.mylibby.data.dict

import com.vdelaar.mylibby.core.network.AppJson
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

const val FREEDICT_CATALOG_URL = "https://freedict.org/freedict-database.json"

/** A dictionary FreeDict offers (as a StarDict package): ISO 639-3 codes of the language it explains and the one it explains it in. */
data class PackInfo(
    val name: String,
    val source: String,
    val target: String,
    val headwords: Int,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val sha512: String,
)

/** The dictionaries FreeDict lists that come as StarDict; the rest of its database is ignored. */
fun parseCatalog(json: String): List<PackInfo> {
    val root = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
    fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    return root.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o["name"].str() ?: return@mapNotNull null
        val parts = name.split('-')
        if (parts.size != 2) return@mapNotNull null
        val release = (o["releases"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it["platform"].str() == "stardict" } ?: return@mapNotNull null
        val url = release["URL"].str() ?: return@mapNotNull null
        PackInfo(
            name, parts[0], parts[1], o["headwords"].str()?.toIntOrNull() ?: 0, release["version"].str().orEmpty(), url,
            release["size"].str()?.toLongOrNull() ?: 0, release["checksum"].str().orEmpty(),
        )
    }.sortedBy { it.name }
}

/** A dictionary on the device. */
@Serializable
data class InstalledPack(
    val name: String,
    val source: String,
    val target: String,
    val bookName: String,
    val headwords: Int,
    val version: String,
    val bytes: Long,
)

sealed interface PackProgress {
    /** [fraction] is null while the size is not known. */
    data class Downloading(val fraction: Float?) : PackProgress
    data class Preparing(val fraction: Float?) : PackProgress
    data class Failed(val message: String) : PackProgress
}

/** Where an entry of a pack is. */
class Located(val offset: Long, val size: Int)

interface PackIndex {
    /** Entries whose headword is [norm] or, ignoring accents, [plain]. */
    fun find(norm: String, plain: String): List<Located>
    fun close()
}

interface PackIndexStore {
    /** Builds the index file from the entries [fill] hands to the function it is given. */
    fun build(file: File, fill: (add: (IdxEntry) -> Unit) -> Unit)
    fun open(file: File): PackIndex
}

fun interface PackDownloader {
    suspend fun download(url: String, target: File, onProgress: (done: Long, total: Long) -> Unit)
}

/** A word as the index keys it: lower case; and again without accents so "cafe" finds "café". */
fun indexKey(word: String): String = word.trim().lowercase(Locale.ROOT)
fun plainKey(word: String): String = Normalizer.normalize(indexKey(word), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** What one pack says about a word. */
class PackAnswer(val pack: InstalledPack, val matched: String, val entries: List<FdEntry>)

/**
 * The FreeDict dictionaries on this device: what exists (the catalog), what is installed, installing and removing, and looking
 * words up. Lookups never touch the network.
 */
class DictionaryPacks(
    private val root: File,
    private val http: com.vdelaar.mylibby.data.DictionaryHttp,
    private val downloader: PackDownloader,
    private val indexes: PackIndexStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _installed = MutableStateFlow(readInstalled())
    val installed: StateFlow<List<InstalledPack>> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, PackProgress>>(emptyMap())
    val progress: StateFlow<Map<String, PackProgress>> = _progress.asStateFlow()

    private val installLock = Mutex()
    private class Open(val index: PackIndex, val body: DictBody)
    private val open = HashMap<String, Open>()

    private fun readInstalled(): List<InstalledPack> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { d ->
            runCatching { AppJson.decodeFromString<InstalledPack>(File(d, "meta.json").readText()) }.getOrNull()
                ?.takeIf { File(d, "index.db").exists() }
        }.sortedBy { it.name }

    /** The list of dictionaries to choose from; kept a week, and an old copy is better than none when offline. */
    suspend fun catalog(): List<PackInfo> = withContext(Dispatchers.IO) {
        val cache = File(root, "catalog.json")
        val fresh = cache.exists() && now() - cache.lastModified() < CATALOG_TTL_MS
        if (!fresh) {
            try {
                val body = http.get(FREEDICT_CATALOG_URL)
                if (body != null && parseCatalog(body).isNotEmpty()) { root.mkdirs(); cache.writeText(body) }
            } catch (e: IOException) {
                if (!cache.exists()) throw e
            }
        }
        if (cache.exists()) parseCatalog(cache.readText()) else emptyList()
    }

    /** Downloads [info], unpacks it and builds its index. Progress is in [progress]; failures end up there too and are rethrown. */
    suspend fun install(info: PackInfo): Unit = installLock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(root, ".tmp-${info.name}")
            try {
                setProgress(info.name, PackProgress.Downloading(0f))
                tmp.deleteRecursively(); tmp.mkdirs()
                val archive = File(tmp, "pack.tar.xz")
                downloader.download(info.url, archive) { done, total ->
                    setProgress(info.name, PackProgress.Downloading(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null))
                }
                currentCoroutineContext().ensureActive()
                if (info.sha512.isNotBlank() && !sha512(archive).equals(info.sha512, true)) throw IOException("The download is damaged; try again")
                setProgress(info.name, PackProgress.Preparing(null))
                val installed = unpack(info, archive, tmp)
                File(tmp, "meta.json").writeText(AppJson.encodeToString(installed))
                archive.delete()
                closeHandle(info.name)
                val dir = File(root, info.name)
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw IOException("Could not store the dictionary")
                _installed.update { (it.filter { p -> p.name != info.name } + installed).sortedBy { p -> p.name } }
                _progress.update { it - info.name }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                if (e is kotlinx.coroutines.CancellationException) _progress.update { it - info.name }
                else setProgress(info.name, PackProgress.Failed(e.message ?: e.javaClass.simpleName))
                throw e
            }
        }
    }

    private suspend fun unpack(info: PackInfo, archive: File, dir: File): InstalledPack {
        var ifoText: String? = null
        var idxName: String? = null
        var dictName: String? = null
        archive.inputStream().buffered().use { raw ->
            XZInputStream(raw).use { xz ->
                TarReader(xz).forEach { entry, data ->
                    val base = entry.name.substringAfterLast('/')
                    when {
                        !entry.isFile -> {}
                        base.endsWith(".ifo") -> ifoText = data.readBytes().toString(Charsets.UTF_8)
                        base.endsWith(".idx") || base.endsWith(".idx.gz") -> { idxName = if (base.endsWith(".gz")) "idx.gz" else "idx"; File(dir, idxName!!).outputStream().use { data.copyTo(it) } }
                        base.endsWith(".dict") || base.endsWith(".dict.dz") -> { dictName = if (base.endsWith(".dz")) "dict.dz" else "dict"; File(dir, dictName!!).outputStream().use { data.copyTo(it) } }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val ifo = parseIfo(ifoText ?: throw IOException("The package has no .ifo"))
        val idxFile = File(dir, idxName ?: throw IOException("The package has no index"))
        var dictFile = File(dir, dictName ?: throw IOException("The package has no entries"))
        if (dictFile.name.endsWith(".dz")) {
            // A dictzip with its chunk table is read in place; any other gzip is unpacked once.
            val ok = DictzipDict.open(dictFile)?.also { it.close() } != null
            if (!ok) { val raw = File(dir, "dict"); gunzipTo(dictFile, raw); dictFile.delete(); dictFile = raw }
        }
        indexes.build(File(dir, "index.db")) { add ->
            val stream = if (idxFile.name.endsWith(".gz")) java.util.zip.GZIPInputStream(idxFile.inputStream().buffered()) else idxFile.inputStream().buffered()
            stream.use { readIdx(it, ifo.offsetBits, add) }
        }
        idxFile.delete()
        return InstalledPack(info.name, info.source, info.target, ifo.bookName.ifBlank { info.name }, ifo.wordCount.takeIf { it > 0 } ?: info.headwords, info.version, dir.listFiles().orEmpty().sumOf { it.length() })
    }

    fun remove(name: String) {
        closeHandle(name)
        File(root, name).deleteRecursively()
        _installed.update { it.filter { p -> p.name != name } }
    }

    fun hasSource(sourceIso3: String): Boolean = _installed.value.any { it.source == sourceIso3 }

    /** What the installed dictionaries that explain [sourceIso3] say about [word] (or about its stem: "bodies" finds "body"). */
    suspend fun lookup(word: String, sourceIso3: String, language2: String = ""): List<PackAnswer> = withContext(Dispatchers.IO) {
        val packs = _installed.value.filter { it.source == sourceIso3 }
        if (packs.isEmpty()) return@withContext emptyList()
        val candidates = stemCandidates(word, language2)
        packs.mapNotNull { pack ->
            val handle = handle(pack) ?: return@mapNotNull null
            for (c in candidates) {
                val locs = handle.index.find(indexKey(c), plainKey(c)).distinctBy { it.offset }
                if (locs.isEmpty()) continue
                val entries = locs.mapNotNull { l ->
                    runCatching { parseFreedictHtml(String(handle.body.read(l.offset, l.size), Charsets.UTF_8)) }.getOrNull()
                }.filter { it.senses.isNotEmpty() || it.phonetic != null }
                if (entries.isNotEmpty()) return@mapNotNull PackAnswer(pack, c, entries)
            }
            null
        }
    }

    @Synchronized private fun handle(pack: InstalledPack): Open? = open[pack.name] ?: runCatching {
        val dir = File(root, pack.name)
        val body: DictBody = File(dir, "dict.dz").takeIf { it.exists() }?.let { DictzipDict.open(it) } ?: RawDict(File(dir, "dict"))
        Open(indexes.open(File(dir, "index.db")), body)
    }.getOrNull()?.also { open[pack.name] = it }

    @Synchronized private fun closeHandle(name: String) {
        open.remove(name)?.let { runCatching { it.index.close() }; runCatching { it.body.close() } }
    }

    private fun setProgress(name: String, p: PackProgress) = _progress.update { it + (name to p) }

    /** Shows the download as started before the background work has got going. */
    fun markQueued(name: String) = setProgress(name, PackProgress.Downloading(0f))

    fun dismissFailure(name: String) = _progress.update { if (it[name] is PackProgress.Failed) it - name else it }

    private fun sha512(file: File): String {
        val md = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { s -> val buf = ByteArray(65536); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val CATALOG_TTL_MS = 7L * 24 * 3600 * 1000 }
}

/** ISO 639-3 code (what FreeDict names its dictionaries with) of a two- or three-letter language code. */
fun iso3(language: String): String {
    val l = language.trim().lowercase(Locale.ROOT).substringBefore('-')
    return when (l.length) {
        2 -> runCatching { Locale.forLanguageTag(l).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() } ?: l
        else -> l
    }
}

/**
 * The word and the forms it may come from, most likely first: a dictionary lists "body", not "bodies". These are plain
 * suffix rules for the languages people read most, not a lemmatiser; an unknown form simply finds nothing.
 */
fun stemCandidates(word: String, language: String): List<String> {
    val w = word.trim().lowercase(Locale.ROOT).replace('’', '\'')
    val out = LinkedHashSet<String>()
    out += w
    fun add(s: String) { if (s.length >= 2) out += s }
    fun undouble(s: String) { if (s.length >= 3 && s[s.length - 1] == s[s.length - 2]) add(s.dropLast(1)) }
    fun strip(suffix: String, repl: String = "") {
        if (w.length > suffix.length + 1 && w.endsWith(suffix)) { val stem = w.dropLast(suffix.length); add(stem + repl); if (repl.isEmpty()) undouble(stem) }
    }
    if (w.endsWith("'s")) add(w.dropLast(2))
    when (language.lowercase(Locale.ROOT).take(2)) {
        "en" -> {
            strip("ies", "y"); strip("ves", "f"); strip("es"); strip("s")
            strip("ied", "y"); strip("ed"); strip("d"); strip("ing"); strip("ing", "e")
            strip("ily", "y"); strip("ly"); strip("ier", "y"); strip("er"); strip("r"); strip("iest", "y"); strip("est"); strip("st")
        }
        "nl" -> {
            strip("ën"); strip("en"); strip("s"); strip("tje"); strip("je"); strip("e"); strip("de"); strip("te"); strip("t")
            if (w.startsWith("ge") && w.length > 5) { add(w.drop(2)); add(w.drop(2).removeSuffix("d").removeSuffix("t")); add(w.drop(2).removeSuffix("en") + "en") }
        }
        "es" -> {
            strip("es"); strip("s"); strip("mente"); strip("ando", "ar"); strip("iendo", "er"); strip("iendo", "ir")
            strip("ado", "ar"); strip("ido", "er"); strip("ido", "ir"); strip("a", "o"); strip("as", "o"); strip("os", "o")
        }
        "de" -> { strip("en"); strip("er"); strip("es"); strip("e"); strip("n"); strip("s"); strip("t"); strip("te") }
        "fr" -> { strip("aux", "al"); strip("es"); strip("s"); strip("x"); strip("e"); strip("ée", "er"); strip("é", "er") }
        else -> { strip("es"); strip("s"); strip("en"); strip("e") }
    }
    return out.take(14)
}

private fun languageName(iso3: String): String =
    Locale.forLanguageTag(iso3).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && !it.equals(iso3, true) } ?: iso3

/** "English → Dutch". */
fun packLabel(source: String, target: String) = "${languageName(source)} → ${languageName(target)}"

/** The answer of a pack in the shape the popover shows. */
fun PackAnswer.toPackEntry(): com.vdelaar.mylibby.data.PackEntry = com.vdelaar.mylibby.data.PackEntry(
    id = "freedict:${pack.name}",
    label = packLabel(pack.source, pack.target),
    matched = matched,
    phonetic = entries.firstNotNullOfOrNull { it.phonetic },
    translations = true,
    meanings = entries.filter { it.senses.isNotEmpty() }.map { e ->
        com.vdelaar.mylibby.data.WordMeaning(
            e.partOfSpeech,
            e.senses.map { s ->
                if (s.translations.isNotEmpty()) com.vdelaar.mylibby.data.WordSense(s.translations.joinToString("; "), gloss = s.gloss)
                else com.vdelaar.mylibby.data.WordSense(s.gloss.orEmpty())
            },
        )
    },
)
