package com.vdelaar.mylibby.ai

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** [spineIndex] is the section's place in the book's reading order, counted over every spine item, as the reader counts it. */
data class TextSection(val title: String, val text: String, val spineIndex: Int)

/** Reads the plain text of an EPUB in reading order, without a WebView, so it can run anywhere (and in unit tests). */
object EpubText {
    fun read(file: File): List<TextSection> = ZipFile(file).use { zip ->
        fun entry(path: String): String? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

        val container = entry("META-INF/container.xml") ?: return emptyList()
        val opfPath = attr(Regex("<rootfile\\b[^>]*>", RegexOption.IGNORE_CASE).find(container)?.value ?: return emptyList(), "full-path") ?: return emptyList()
        val opf = entry(opfPath) ?: return emptyList()
        val dir = opfPath.substringBeforeLast('/', "")

        val manifest = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).mapNotNull { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@mapNotNull null
            val href = attr(tag, "href") ?: return@mapNotNull null
            id to Item(href, attr(tag, "media-type").orEmpty(), attr(tag, "properties").orEmpty())
        }.toMap()

        val sections = ArrayList<TextSection>()
        Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEachIndexed { spineIndex, ref ->
            val item = manifest[attr(ref.value, "idref")] ?: return@forEachIndexed
            if (!item.mediaType.contains("html") && !item.mediaType.contains("xml")) return@forEachIndexed
            if ("nav" in item.properties.split(' ')) return@forEachIndexed // the table of contents page
            val path = resolve(dir, item.href)
            val html = entry(path) ?: return@forEachIndexed
            val text = htmlToText(html)
            if (text.length < MIN_SECTION_CHARS) return@forEachIndexed // cover, title page, blank spacer
            sections += TextSection(titleOf(html, sections.size + 1), text, spineIndex)
        }
        sections
    }

    private data class Item(val href: String, val mediaType: String, val properties: String)

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun resolve(dir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        (if (dir.isEmpty()) clean else "$dir/$clean").split('/').forEach { p ->
            when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += p }
        }
        return parts.joinToString("/")
    }

    private fun titleOf(html: String, number: Int): String {
        val heading = Regex("<h[1-3]\\b[^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
        val text = heading?.let { htmlToText(it) }?.replace('\n', ' ')?.trim().orEmpty()
        return text.take(80).ifBlank { "Section $number" }
    }

    private const val MIN_SECTION_CHARS = 80

    fun htmlToText(html: String): String {
        var s = html
        s = Regex("<head\\b.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("<(script|style)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(s, " ")
        s = Regex("<(br|/p|/div|/h[1-6]|/li|/tr|/blockquote|/section)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = decodeEntities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "laquo" to "«", "raquo" to "»",
    )

    private fun decodeEntities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }
}
