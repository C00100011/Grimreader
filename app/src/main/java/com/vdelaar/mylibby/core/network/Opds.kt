package com.vdelaar.mylibby.core.network

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.SAXParserFactory

/** One way to get a book from an OPDS catalog (a file in one format). */
data class OpdsAcquisition(val url: String, val mime: String, val kind: String) {
    /** The app's name for the format ("EPUB", ...), or null for formats it cannot read. */
    val format: String? get() = opdsFormatFor(mime, url)
}

/** A line of an OPDS feed: either a book (acquisitions) or a folder / list to open ([navigationUrl]). */
data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String? = null,
    val language: String? = null,
    val updated: String? = null,
    val coverUrl: String? = null,
    val thumbnailUrl: String? = null,
    val navigationUrl: String? = null,
    val acquisitions: List<OpdsAcquisition> = emptyList(),
) {
    val isBook: Boolean get() = acquisitions.isNotEmpty()
    /** Acquisitions the app can open (read as local books). */
    val readable: List<OpdsAcquisition> get() = acquisitions.filter { it.format != null }
}

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String? = null,
    /** A link to an OpenSearch description (`opensearchdescription+xml`), if the catalog has one. */
    val searchDescriptionUrl: String? = null,
    /** A ready search URL template with `{searchTerms}` in it, if the catalog gives one directly. */
    val searchTemplate: String? = null,
)

private const val ATOM = "http://www.w3.org/2005/Atom"

/** Maps a MIME type (or, failing that, a file extension) to a format the app can read. */
fun opdsFormatFor(mime: String, url: String = ""): String? {
    val m = mime.lowercase().substringBefore(';').trim()
    return when {
        m == "application/epub+zip" -> "EPUB"
        m == "application/x-mobipocket-ebook" -> "MOBI"
        m == "application/x-mobi8-ebook" || m == "application/vnd.amazon.mobi8-ebook" || m == "application/vnd.amazon.ebook" -> "AZW3"
        m == "application/fb2+zip" || m == "application/x-fictionbook+xml" || m == "application/x-fb2+zip" -> "FB2"
        m == "application/vnd.comicbook+zip" || m == "application/x-cbz" || m == "application/x-cbr" -> "CBZ"
        else -> when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "epub" -> "EPUB"
            "mobi" -> "MOBI"
            "azw3" -> "AZW3"
            "fb2" -> "FB2"
            "cbz" -> "CBZ"
            else -> null
        }
    }
}

/** Resolves a (possibly relative) link of a feed against the feed's own address. */
fun resolveOpdsUrl(base: String, href: String): String =
    runCatching { URI(base).resolve(href.trim()).toString() }.getOrDefault(href.trim())

/** Fills a search template: `{searchTerms}` gets the query; optional parts (`&startPage={startPage?}`) are dropped. */
fun fillSearchTemplate(template: String, query: String): String {
    val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20") // %20 works in paths and in queries
    var s = template.replace("{searchTerms}", encoded)
    // an optional query parameter goes away together with its name
    s = s.replace(Regex("""([?&])[^?&=#{}]+=\{[^{}]*\?\}""")) { m -> if (m.groupValues[1] == "?") "?" else "" }
    s = s.replace(Regex("""\{[^{}]*\?\}"""), "").replace(Regex("""\{[^{}]*\}"""), "")
    return s.replace("?&", "?").replace("&&", "&").trimEnd('&', '?')
}

/**
 * Reads an OPDS 1.x (Atom) feed. Tolerant: unknown elements are ignored, links may be relative,
 * a book is any entry with an acquisition link, a folder any other entry with an Atom link.
 */
fun parseOpdsFeed(xml: String, feedUrl: String): OpdsFeed {
    val handler = FeedHandler(feedUrl)
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        // Never fetch external entities from a feed.
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }
    factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
    return handler.result()
}

/** Reads an OpenSearch description and returns its Atom search URL template (with `{searchTerms}`), if any. */
fun parseOpenSearchTemplate(xml: String): String? {
    var best: String? = null
    var bestScore = -1
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    factory.newSAXParser().parse(InputSource(StringReader(xml)), object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
            if (localName != "Url") return
            val type = a.getValue("type").orEmpty().lowercase()
            val template = a.getValue("template") ?: return
            if ("atom+xml" !in type && "opds" !in type) return
            val score = (if ("opds-catalog" in type) 2 else 1) + (if (a.getValue("rel").isNullOrBlank() || a.getValue("rel") == "results") 1 else 0)
            if (score > bestScore) { best = template; bestScore = score }
        }
    })
    return best
}

private class FeedHandler(private val feedUrl: String) : DefaultHandler() {
    private class EntryBuilder {
        var id = ""; var title = ""; var summary: String? = null; var language: String? = null; var updated: String? = null
        val authors = ArrayList<String>()
        var cover: String? = null; var thumb: String? = null; var nav: String? = null
        val acquisitions = ArrayList<OpdsAcquisition>()
    }

    private val entries = ArrayList<OpdsEntry>()
    private var feedTitle = ""
    private var next: String? = null
    private var searchDescription: String? = null
    private var searchTemplate: String? = null
    private var entry: EntryBuilder? = null
    private var inAuthor = false
    private var depth = 0
    private var textDepthStart = -1       // depth of a <summary>/<content> we are collecting
    private val text = StringBuilder()
    private val summaryText = StringBuilder()

    fun result() = OpdsFeed(feedUrl, feedTitle.ifBlank { feedUrl }, entries, next, searchDescription, searchTemplate)

    override fun startElement(uri: String?, localName: String, qName: String?, a: Attributes) {
        depth++
        text.setLength(0)
        if (textDepthStart >= 0) { summaryText.append(' '); return } // nested markup inside xhtml content
        when (localName) {
            "entry" -> if (uri == ATOM) entry = EntryBuilder()
            "author" -> inAuthor = true
            "summary", "content" -> if (entry != null && uri == ATOM) { textDepthStart = depth; summaryText.setLength(0) }
            "link" -> link(a)
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
        if (textDepthStart >= 0) summaryText.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        val t = text.toString().trim()
        if (textDepthStart >= 0 && depth > textDepthStart) { depth--; return }
        val e = entry
        when (localName) {
            "entry" -> if (e != null && uri == ATOM) { finish(e); entry = null }
            "author" -> inAuthor = false
            "name" -> if (inAuthor && e != null && t.isNotEmpty()) e.authors += t
            "id" -> if (e != null && uri == ATOM) e.id = t
            "title" -> if (uri == ATOM) { if (e != null) e.title = t else if (feedTitle.isEmpty()) feedTitle = t }
            "updated" -> if (e != null && uri == ATOM) e.updated = t
            "language" -> if (e != null && t.isNotEmpty()) e.language = t
            "summary", "content" -> if (e != null && uri == ATOM && textDepthStart == depth) {
                val s = summaryText.toString().replace(Regex("\\s+"), " ").trim()
                if (s.isNotEmpty() && (localName == "summary" || e.summary == null)) e.summary = s
                textDepthStart = -1
            }
        }
        depth--
    }

    private fun link(a: Attributes) {
        val href = a.getValue("href") ?: return
        val rel = a.getValue("rel").orEmpty()
        val type = a.getValue("type").orEmpty()
        val url = resolveOpdsUrl(feedUrl, href)
        val e = entry
        if (e == null) {
            when {
                rel == "next" -> next = url
                rel == "search" && "opensearchdescription" in type -> searchDescription = url
                rel == "search" && ("atom" in type || type.isEmpty()) && "{searchTerms}" in href -> searchTemplate = resolveOpdsUrl(feedUrl, href.replace("{searchTerms}", "SEARCHTERMS")).replace("SEARCHTERMS", "{searchTerms}")
            }
            return
        }
        when {
            rel.startsWith("http://opds-spec.org/acquisition") -> {
                if (!rel.endsWith("/sample") && !rel.endsWith("/preview")) e.acquisitions += OpdsAcquisition(url, type, rel.substringAfter("acquisition").trim('/').ifEmpty { "acquisition" })
            }
            rel == "http://opds-spec.org/image/thumbnail" || rel == "x-stanza-cover-image-thumbnail" -> e.thumb = url
            rel == "http://opds-spec.org/image" || rel == "x-stanza-cover-image" -> e.cover = url
            // a folder / list: an Atom link that is not just the detail page of this entry
            ("atom+xml" in type || "opds-catalog" in type) && !type.contains("type=entry") && rel != "self" && rel != "next" &&
                rel != "previous" && rel != "first" && rel != "last" && rel != "up" && rel != "start" -> if (e.nav == null) e.nav = url
        }
    }

    private fun finish(e: EntryBuilder) {
        entries += OpdsEntry(
            id = e.id.ifBlank { e.title },
            title = e.title.ifBlank { "Untitled" },
            authors = e.authors,
            summary = e.summary,
            language = e.language,
            updated = e.updated,
            coverUrl = e.cover,
            thumbnailUrl = e.thumb ?: e.cover,
            navigationUrl = if (e.acquisitions.isEmpty()) e.nav else null,
            acquisitions = e.acquisitions,
        )
    }
}
