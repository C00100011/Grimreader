package com.vdelaar.mylibby.ui.reader

import com.vdelaar.mylibby.core.datastore.PageFlow
import com.vdelaar.mylibby.core.datastore.ReaderSettings
import com.vdelaar.mylibby.core.datastore.ReaderTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Events posted by reader.js -------------------------------------------------------------------

@Serializable
data class TocEntry(val label: String, val href: String, val depth: Int = 0)

@Serializable
data class ReadyEvent(
    val title: String = "",
    val author: String = "",
    val language: String = "",
    val toc: List<TocEntry> = emptyList(),
    val sectionCount: Int = 0,
    val totalBytes: Long = 0,
    val fixedLayout: Boolean = false,
)

@Serializable
data class RelocateEvent(
    val cfi: String = "",
    val fraction: Double = 0.0,
    val href: String = "",
    val tocLabel: String = "",
    val sectionIndex: Int = 0,
    val sectionTotal: Int = 0,
    val remainingSectionBytes: Double = 0.0,
    val remainingTotalBytes: Double = 0.0,
    val locationCurrent: Int = 0,
    val locationTotal: Int = 0,
    val pageLabel: String = "",
    /** Page within the chapter (1-based; per spread when two pages are shown) and the chapter's page count; 0 = unknown. */
    val pageInSection: Int = 0,
    val pagesInSection: Int = 0,
    /** Estimated page in the whole book and its estimated page count; 0 = not known (yet). */
    val bookPage: Int = 0,
    val bookPages: Int = 0,
)

@Serializable
data class SelectionEvent(val cfi: String, val text: String)

@Serializable
data class CfiEvent(val cfi: String)

@Serializable
data class MessageEvent(val message: String = "")

@Serializable
data class LinkEvent(val href: String = "")

@Serializable
data class SearchItem(val cfi: String, val pre: String = "", val match: String = "", val post: String = "")

@Serializable
data class SearchResultsEvent(val label: String = "", val items: List<SearchItem> = emptyList())

@Serializable
data class TtsSegmentDto(val mark: String, val text: String)

@Serializable
data class TtsBlockEvent(val segments: List<TtsSegmentDto> = emptyList(), val lang: String = "")

@Serializable
data class SpeedBlockEvent(val words: List<String> = emptyList(), val section: Int = 0, val lang: String = "")

@Serializable
data class WordStatsEvent(val words: Long = 0, val bytes: Long = 0)

// Payloads sent to reader.js -------------------------------------------------------------------

@Serializable
data class JsTheme(val bg: String, val fg: String, val link: String, val selection: String, val dark: Boolean)

@Serializable
data class JsSettings(
    val theme: JsTheme,
    val fontFamily: String,
    val fontSizePx: Int,
    val lineHeight: Float,
    val paragraphSpacing: Float,
    val marginPercent: Int,
    val verticalMarginPx: Int,
    val maxInlineSize: Int,
    val maxColumns: Int,
    val portraitColumns: Int,
    val justify: Boolean,
    val hyphenate: Boolean,
    val flow: String,
    val tapToTurn: Boolean,
    val guided: Boolean = false,
    val guidePalette: Int = 0,
    val guideIntensity: Float = 0.6f,
)

@Serializable
data class JsAnnotation(val value: String, val color: String, val style: String, val note: String? = null)

val BridgeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun Long.cssColor(): String {
    val a = (this ushr 24) and 0xFF
    val r = (this ushr 16) and 0xFF
    val g = (this ushr 8) and 0xFF
    val b = this and 0xFF
    return if (a == 0xFFL) "#%02x%02x%02x".format(r, g, b) else "rgba($r,$g,$b,${"%.2f".format(java.util.Locale.US, a / 255f)})"
}

fun ReaderSettings.effectiveTheme(systemDark: Boolean): ReaderTheme =
    if (followSystemDark && systemDark && !theme.dark) darkTheme else theme

fun ReaderSettings.toJs(systemDark: Boolean, columns: Int, portraitColumns: Int): JsSettings {
    val t = effectiveTheme(systemDark)
    return JsSettings(
        theme = JsTheme(t.bg.cssColor(), t.fg.cssColor(), t.link.cssColor(), t.selection.cssColor(), t.dark),
        fontFamily = font.css,
        fontSizePx = fontSizePx,
        lineHeight = lineHeight,
        paragraphSpacing = paragraphSpacing,
        marginPercent = marginPercent,
        verticalMarginPx = 44,
        maxInlineSize = 720,
        maxColumns = if (twoColumnsOnWide) columns else 1,
        portraitColumns = if (twoColumnsOnWide) portraitColumns else 1,
        justify = justify,
        hyphenate = hyphenate,
        flow = if (flow == PageFlow.SCROLLED) "scrolled" else "paginated",
        tapToTurn = tapToTurn,
        guided = guidedColors,
        guidePalette = guidePalette,
        guideIntensity = guideIntensity,
    )
}

val HighlightColors = listOf("#FFD54F", "#81C784", "#64B5F6", "#F48FB1", "#CE93D8")
