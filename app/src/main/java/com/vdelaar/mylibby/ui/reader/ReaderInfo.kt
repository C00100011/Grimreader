package com.vdelaar.mylibby.ui.reader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.InfoItem
import com.vdelaar.mylibby.core.datastore.ReaderSettings
import com.vdelaar.mylibby.data.formatMinutes
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything an [InfoItem] can show. Items without data (e.g. page numbers before layout) render as nothing. */
data class InfoData(
    val bookTitle: String,
    val author: String,
    val location: RelocateEvent?,
    val chapterMinutes: Double?,
    val bookMinutes: Double?,
    val clock: String?,
    val battery: Int?,
)

/** Page / percentage maths shared by the footer text and the chapter progress slider. */
object ReaderInfoMath {
    /** Slider position inside the chapter: first page = 0, last page = 1. Null while the page count is unknown. */
    fun chapterSliderFraction(e: RelocateEvent?): Float? {
        if (e == null || e.pagesInSection < 1 || e.pageInSection < 1) return null
        return ((e.pageInSection - 1).toFloat() / max(1, e.pagesInSection - 1)).coerceIn(0f, 1f)
    }

    /** "How far through the chapter" counting the current page as read: the last page is 100 %. */
    fun chapterPercent(e: RelocateEvent?): Int? {
        if (e == null || e.pagesInSection < 1 || e.pageInSection < 1) return null
        return (100f * e.pageInSection / e.pagesInSection).roundToInt().coerceIn(0, 100)
    }

    /** The page (1-based) a slider position [f] points at. */
    fun pageAt(f: Float, pages: Int): Int = if (pages <= 1) 1 else (f.coerceIn(0f, 1f) * (pages - 1)).roundToInt() + 1
}

@Composable
fun infoText(item: InfoItem, d: InfoData): String? {
    val loc = d.location
    return when (item) {
        InfoItem.NONE -> null
        InfoItem.BOOK_TITLE -> d.bookTitle.ifBlank { null }
        InfoItem.AUTHOR -> d.author.ifBlank { null }
        InfoItem.CHAPTER_TITLE -> loc?.tocLabel?.ifBlank { null }
        InfoItem.PAGE_CHAPTER -> if (loc != null && loc.pagesInSection > 0 && loc.pageInSection > 0) stringResource(R.string.info_page_of, loc.pageInSection, loc.pagesInSection) else null
        InfoItem.PAGE_BOOK -> if (loc != null && loc.bookPages > 0) stringResource(R.string.info_page_of_book, loc.bookPage, loc.bookPages) else null
        InfoItem.PERCENT_BOOK -> loc?.let { "${(it.fraction * 100).toInt()}%" }
        InfoItem.PERCENT_CHAPTER -> ReaderInfoMath.chapterPercent(loc)?.let { "$it%" }
        InfoItem.TIME_LEFT_CHAPTER -> d.chapterMinutes?.let { stringResource(R.string.left_in_chapter, formatMinutes(it)) }
        InfoItem.TIME_LEFT_BOOK -> d.bookMinutes?.let { stringResource(R.string.left_in_book, formatMinutes(it)) }
        InfoItem.LOCATION -> if (loc != null && loc.locationTotal > 0) stringResource(R.string.info_location_of, loc.locationCurrent, loc.locationTotal) else null
        InfoItem.CLOCK -> d.clock
        InfoItem.BATTERY -> d.battery?.let { stringResource(R.string.info_battery_pct, it) }
    }
}

/**
 * One line of up to three texts: left, centre and right. The centre stays truly centred; each side may use
 * a limited share of the width so a long chapter title can't push the others off the line.
 */
@Composable
fun InfoStrip(left: String?, center: String?, right: String?, color: Color, modifier: Modifier = Modifier) {
    if (left == null && center == null && right == null) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = maxWidth
        val sideMax = when {
            center != null -> w * 0.28f
            left != null && right != null -> w * 0.48f
            else -> w * 0.9f
        }
        val centerMax = if (left == null && right == null) w else w * 0.44f
        if (left != null) Text(left, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).widthIn(max = sideMax))
        if (right != null) Text(right, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = sideMax))
        if (center != null) Text(center, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).widthIn(max = centerMax))
    }
}

/** Header and footer shown over the page while the reading chrome is hidden. */
@Composable
fun AmbientInfo(settings: ReaderSettings, data: InfoData, fg: Color, modifier: Modifier = Modifier) {
    val color = fg.copy(alpha = .5f)
    Box(modifier) {
        InfoStrip(
            infoText(settings.headerLeft, data), infoText(settings.headerCenter, data), infoText(settings.headerRight, data), color,
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 24.dp, end = 24.dp),
        )
        InfoStrip(
            infoText(settings.footerLeft, data), infoText(settings.footerCenter, data), infoText(settings.footerRight, data), color,
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

val ReaderSettings.usesClockOrBattery: Boolean
    get() = listOf(headerLeft, headerCenter, headerRight, footerLeft, footerCenter, footerRight).any { it == InfoItem.CLOCK || it == InfoItem.BATTERY }

/** Time of day and battery level, refreshed every 30 s, only while [active]. */
@Composable
fun rememberClockAndBattery(active: Boolean): Pair<String?, Int?> {
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            tick = System.currentTimeMillis()
            delay(30_000)
        }
    }
    if (!active) return null to null
    val minute = tick / 60_000
    val clock = remember(minute) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(tick)) }
    val battery = remember(minute) { batteryPercent(context) }
    return clock to battery
}

private fun batteryPercent(context: Context): Int? {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else null
}
