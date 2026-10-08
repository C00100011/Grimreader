package com.vdelaar.mylibby.tts.neural

/**
 * Turns book text into something a neural voice can pronounce: numbers become words and common
 * Dutch abbreviations are spelled out. Everything else is left to the model.
 */
object DutchTextNormalizer {

    private val abbreviations = listOf(
        "bijv." to "bijvoorbeeld", "o.a." to "onder andere", "d.w.z." to "dat wil zeggen",
        "enz." to "enzovoort", "etc." to "et cetera", "m.b.t." to "met betrekking tot",
        "t.o.v." to "ten opzichte van", "i.p.v." to "in plaats van", "dhr." to "de heer",
        "mevr." to "mevrouw", "mr." to "meester", "drs." to "doctorandus", "dr." to "dokter",
        "prof." to "professor", "ir." to "ingenieur", "nr." to "nummer", "blz." to "bladzijde",
        "ca." to "circa", "evt." to "eventueel", "vs." to "versus", "St." to "Sint",
    )

    private val units = listOf(
        "nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen", "tien",
        "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien", "achttien", "negentien",
    )
    private val tens = listOf("", "", "twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig")
    private val ordinals = mapOf(
        1L to "eerste", 2L to "tweede", 3L to "derde", 4L to "vierde", 5L to "vijfde",
        6L to "zesde", 7L to "zevende", 8L to "achtste", 9L to "negende", 10L to "tiende",
    )

    private val yearPrefix = Regex("""(?i)\b(in|sinds|tot|van|vanaf|rond|omstreeks|eind|begin|medio|anno|jaar)$""")
    private val decimal = Regex("""(\d+),(\d+)""")
    private val thousands = Regex("""\d{1,3}(?:\.\d{3})+""")
    private val ordinal = Regex("""\b(\d+)(?:e|de|ste)\b""")
    private val number = Regex("""\d+""")
    private val euro = Regex("""€\s?(\d+(?:,\d+)?)""")

    fun normalize(input: String): String {
        var text = input
        for ((abbr, full) in abbreviations) text = text.replace(Regex("""(?<![\p{L}])${Regex.escape(abbr)}""", RegexOption.IGNORE_CASE), full)
        text = text.replace("&", " en ").replace("%", " procent").replace("°C", " graden Celsius")
        text = euro.replace(text) { "${it.groupValues[1]} euro" }
        text = thousands.replace(text) { it.value.replace(".", "") }
        text = decimal.replace(text) { m -> "${m.groupValues[1]} komma " + m.groupValues[2].map { cardinal(it.digitToInt().toLong()) }.joinToString(" ") }
        text = ordinal.replace(text) { m -> ordinalWord(m.groupValues[1].toLongOrNull() ?: return@replace m.value) }
        val out = StringBuilder()
        var last = 0
        for (m in number.findAll(text)) {
            out.append(text, last, m.range.first)
            val n = m.value.toLongOrNull()
            val before = text.substring(0, m.range.first).trimEnd()
            out.append(
                when {
                    n == null || m.value.length > 12 -> m.value.map { cardinal(it.digitToInt().toLong()) }.joinToString(" ")
                    m.value.length == 4 && n in 1100..1999 && yearPrefix.containsMatchIn(before) -> year(n)
                    m.value.length > 1 && m.value.startsWith("0") -> m.value.map { cardinal(it.digitToInt().toLong()) }.joinToString(" ")
                    else -> cardinal(n)
                }
            )
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun year(n: Long): String {
        val rest = n % 100
        return cardinal(n / 100) + "honderd" + if (rest > 0) " " + cardinal(rest) else ""
    }

    private fun ordinalWord(n: Long): String = ordinals[n] ?: run {
        val c = cardinal(n)
        if (n < 20) c + "de" else c + "ste"
    }

    fun cardinal(n: Long): String = when {
        n < 20 -> units[n.toInt()]
        n < 100 -> {
            val t = (n / 10).toInt()
            val u = (n % 10).toInt()
            if (u == 0) tens[t] else {
                val unit = units[u]
                unit + (if (unit.endsWith("e")) "ën" else "en") + tens[t]
            }
        }
        n < 1_000 -> {
            val h = n / 100
            val rest = n % 100
            (if (h == 1L) "honderd" else units[h.toInt()] + "honderd") + if (rest > 0) " " + cardinal(rest) else ""
        }
        n < 1_000_000 -> {
            val th = n / 1_000
            val rest = n % 1_000
            (if (th == 1L) "duizend" else cardinal(th) + "duizend") + if (rest > 0) " " + cardinal(rest) else ""
        }
        n < 1_000_000_000 -> {
            val m = n / 1_000_000
            val rest = n % 1_000_000
            cardinal(m) + (if (m == 1L) " miljoen" else " miljoen") + if (rest > 0) " " + cardinal(rest) else ""
        }
        else -> {
            val b = n / 1_000_000_000
            val rest = n % 1_000_000_000
            cardinal(b) + " miljard" + if (rest > 0) " " + cardinal(rest) else ""
        }
    }
}
