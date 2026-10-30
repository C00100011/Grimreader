package com.vdelaar.mylibby.data.dict

/** The dictionaries to suggest for a book in [source]: those that explain it in the reader's own language first, then English, the biggest first. */
fun recommendPacks(catalog: List<PackInfo>, source: String, preferredTarget: String, limit: Int = 4): List<PackInfo> =
    catalog.filter { it.source == source }
        .sortedWith(compareBy<PackInfo> { if (it.target == preferredTarget) 0 else if (it.target == "eng") 1 else 2 }.thenByDescending { it.headwords })
        .take(limit)

/** What a size in bytes looks like to a reader. */
fun packSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** "Dutch (definitions in English)", in the language of the app. */
fun wiktLabel(context: android.content.Context, info: WiktPackInfo): String {
    val locale = context.resources.configuration.locales[0]
    fun name(code: String) = java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale).ifBlank { code }
    return context.getString(com.vdelaar.mylibby.R.string.dict_wikt_row, name(info.language).replaceFirstChar { it.titlecase(locale) }, name(info.edition))
}
