package com.vdelaar.mylibby.data.dict

/** What one FreeDict entry says about a word: its part of speech, pronunciation, and the senses with their translations. */
data class FdEntry(val partOfSpeech: String, val phonetic: String?, val senses: List<FdSense>)

/** One sense: the [translations] (the words that mean the same in the other language) and a [gloss] explaining it, if any. */
data class FdSense(val translations: List<String>, val gloss: String?)

private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)>")
private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val HAS_LETTER = Regex("[\\p{L}\\p{N}]")
private val SPACES = Regex("\\s+")

fun decodeEntities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; e == "apos" -> "'"; e == "nbsp" -> " "
        else -> m.value
    }
}

private class Frame(val name: String, val cssGray: Boolean = false, val grammar: Boolean = false) {
    val text = StringBuilder()
    var hasChild = false
}

private class SenseBuilder {
    val translations = ArrayList<String>()
    val gloss = ArrayList<String>()
    fun build() = if (translations.isEmpty() && gloss.isEmpty()) null else FdSense(translations.toList(), gloss.joinToString(" ").ifBlank { null })
}

/**
 * Reads the HTML of a FreeDict entry. The shape varies between dictionaries, but all use the same few elements: a gray
 * `<font>` for the pronunciation, a green one for the part of speech, `<ol><li>` for the senses, and `<div>`s for the
 * translations (a `<div>` with text and no other elements in it) while loose text is a gloss. Cross-references (`<a>`) are left out.
 */
fun parseFreedictHtml(html: String, maxSenses: Int = 6, maxTranslations: Int = 8): FdEntry {
    val frames = ArrayList<Frame>()
    val senses = ArrayList<FdSense>()
    val phonetics = ArrayList<String>()
    val pos = StringBuilder()
    var current: SenseBuilder? = null
    var olDepth = 0
    var anchor = 0

    fun sense() = current ?: SenseBuilder().also { current = it }
    fun flushSense() { current?.build()?.let { senses += it }; current = null }
    fun clean(s: String) = decodeEntities(s).replace(SPACES, " ").trim()

    fun text(raw: String) {
        if (raw.isEmpty() || anchor > 0) return
        val top = frames.lastOrNull()
        top?.text?.append(raw)
    }

    fun close(name: String) {
        val idx = frames.indexOfLast { it.name == name }
        if (idx < 0) return
        while (frames.size > idx + 1) frames.removeAt(frames.size - 1) // unclosed inner tags
        val f = frames.removeAt(idx)
        // The slashes round a pronunciation are loose text in the entry's outer element: not part of a gloss.
        val whole = clean(f.text.toString())
        val t = whole.trimStart('/', ',', ' ', '[', ']')
        when {
            f.cssGray -> if (whole.isNotEmpty()) phonetics += whole
            f.grammar -> if (whole.isNotEmpty()) { if (pos.isNotEmpty()) pos.append(", "); pos.append(whole) }
            name == "a" -> anchor--
            name == "div" -> if (t.isNotEmpty() && HAS_LETTER.containsMatchIn(t)) {
                if (!f.hasChild) sense().translations += t else sense().gloss += t
            }
            name == "li" -> {
                if (t.isNotEmpty() && HAS_LETTER.containsMatchIn(t)) sense().gloss += t
                if (olDepth == 1) flushSense()
            }
            name == "ol" -> olDepth--
        }
    }

    var last = 0
    for (m in TAG.findAll(html)) {
        text(html.substring(last, m.range.first))
        last = m.range.last + 1
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3].lowercase()
        if (closing) { close(name); continue }
        if (m.groupValues[4] == "/" || name == "br" || name == "hr" || name == "img") continue
        when (name) {
            "div", "ol", "li" -> {
                frames.lastOrNull()?.hasChild = true
                if (name == "ol") olDepth++
                if (name == "li" && olDepth == 1) flushSense()
                frames += Frame(name)
            }
            "font" -> frames += Frame(name, cssGray = "gray" in attrs, grammar = "grammar" in attrs || ("green" in attrs && "gray" !in attrs))
            "a" -> { anchor++; frames += Frame(name) }
            else -> frames += Frame(name)
        }
    }
    text(html.substring(last))
    while (frames.isNotEmpty()) close(frames.last().name)
    flushSense()

    val phonetic = phonetics.joinToString(", ") { if (it.startsWith("[")) it else "/$it/" }.ifBlank { null }
    val capped = senses.take(maxSenses).map { it.copy(translations = it.translations.distinct().take(maxTranslations)) }
    if (capped.isEmpty() && phonetic == null && pos.isEmpty()) {
        val plain = clean(TAG.replace(html, " "))
        if (plain.isNotEmpty()) return FdEntry(pos.toString(), phonetic, listOf(FdSense(emptyList(), plain)))
    }
    return FdEntry(pos.toString(), phonetic, capped)
}
