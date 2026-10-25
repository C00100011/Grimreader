package com.vdelaar.mylibby.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import java.time.YearMonth
import androidx.compose.runtime.remember
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.BookTotal
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.data.DayState
import com.vdelaar.mylibby.data.ReadingSpeed
import com.vdelaar.mylibby.data.StreakInfo
import com.vdelaar.mylibby.data.formatDuration
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.ProgressRing
import com.vdelaar.mylibby.ui.components.StatTile
import com.vdelaar.mylibby.ui.components.StreakFlame
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import com.vdelaar.mylibby.ui.theme.Flame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

class ActivityViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> StateFlowOf(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val streak = StateFlowOf(c.stats.streak, StreakInfo())
    val speed = StateFlowOf<ReadingSpeed?>(c.stats.speed, null)
    val totalSeconds = StateFlowOf(c.stats.totalSeconds, 0L)
    val totalPages = StateFlowOf(c.stats.totalPages, 0L)
    val avgChapter = StateFlowOf(c.stats.avgChapterSeconds, null)
    val avgPage = StateFlowOf(c.stats.avgSecondsPerPage, 0.0)
    val topBooks = StateFlowOf(c.stats.topBooks(5), emptyList())
    fun daySessions(date: LocalDate) = c.stats.daySessions(date.toString())
    val recent = StateFlowOf(c.stats.recentSessions(15), emptyList())
}

@Composable
fun ActivityScreen(navigator: AppNavigator) {
    val vm = appViewModel { ActivityViewModel(it) }
    val streak by vm.streak.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val totalSeconds by vm.totalSeconds.collectAsStateWithLifecycle()
    val totalPages by vm.totalPages.collectAsStateWithLifecycle()
    val avgChapter by vm.avgChapter.collectAsStateWithLifecycle()
    val avgPage by vm.avgPage.collectAsStateWithLifecycle()
    val top by vm.topBooks.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val wide = rememberDeviceLayout().isWide

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Text(
                stringResource(R.string.tab_activity),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        item {
            Column(Modifier.widthIn(max = 900.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wide) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { StreakCard(streak, Modifier.fillMaxHeight()) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { WeekCard(streak, Modifier.fillMaxHeight()) }
                    }
                } else {
                    StreakCard(streak)
                    WeekCard(streak)
                }
                HeatmapCard(streak, vm::daySessions) { navigator.openBook(it) }
                Text(stringResource(R.string.your_reading), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                val tiles = listOf(
                    Triple(formatDuration(totalSeconds), stringResource(R.string.stat_total_time), Icons.Rounded.Schedule),
                    Triple("$totalPages", stringResource(R.string.stat_pages_turned), Icons.Rounded.AutoStories),
                    Triple(speed?.let { "${it.wordsPerMinute}" } ?: "–", stringResource(if (speed?.measured == true) R.string.stat_wpm else R.string.stat_wpm_est), Icons.Rounded.Speed),
                    Triple(avgChapter?.let { formatDuration(it.toLong()) } ?: "–", stringResource(R.string.stat_avg_chapter), Icons.Rounded.Timer),
                    Triple(if (avgPage > 0) formatDuration(avgPage.toLong()) else "–", stringResource(R.string.stat_avg_page), Icons.Rounded.Bolt),
                    Triple("${streak.longest}", stringResource(R.string.stat_longest), Icons.Rounded.AcUnit),
                )
                tiles.chunked(if (wide) 3 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (v, l, i) -> StatTile(v, l, Modifier.weight(1f), i) }
                    }
                }
                if (top.isNotEmpty()) {
                    Text(stringResource(R.string.most_read), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    TopBooks(top) { navigator.openBook(it) }
                }
                if (recent.isNotEmpty()) {
                    Text(stringResource(R.string.recent_sessions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    RecentSessions(recent) { navigator.openBook(it) }
                }
            }
        }
    }
}

@Composable
private fun StreakCard(streak: StreakInfo, modifier: Modifier = Modifier) {
    val unit = stringResource(if (streak.goalType == GoalType.MINUTES) R.string.unit_min else R.string.unit_pages)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            StreakFlame(active = streak.current > 0, modifier = Modifier.size(56.dp, 70.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.streak_days, streak.current), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    when {
                        streak.todayMet -> stringResource(R.string.streak_done_today)
                        streak.current > 0 -> stringResource(R.string.streak_keep_going, (streak.target - streak.todayValue).toInt(), unit)
                        else -> stringResource(R.string.streak_start)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (streak.freezesLeftThisWeek > 0) {
                    Text(pluralStringResource(R.plurals.freeze_left, streak.freezesLeftThisWeek, streak.freezesLeftThisWeek), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f), modifier = Modifier.padding(top = 4.dp))
                }
            }
            ProgressRing(streak.todayFraction, Modifier.size(64.dp), stroke = 7.dp, track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f)) {
                Text("${(streak.todayFraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun WeekCard(streak: StreakInfo, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { streak.minutes[it] ?: 0L }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(streak.target.toLong()).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.this_week), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.min_read_n, values.sum().toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Canvas(Modifier.width(18.dp).height(84.dp)) {
                            drawRoundRect(track, cornerRadius = CornerRadius(9.dp.toPx()))
                            val h = size.height * (values[i].toFloat() / max)
                            if (h > 0) drawRoundRect(primary, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = CornerRadius(9.dp.toPx()))
                            if (streak.goalType == GoalType.MINUTES) {
                                val gy = size.height * (1 - streak.target.toFloat() / max)
                                drawLine(goalColor, Offset(-4f, gy), Offset(size.width + 4f, gy), strokeWidth = 2.dp.toPx())
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            d.dayOfWeek.getDisplayName(TextStyle.NARROW, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (d == today) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HeatmapCard(streak: StreakInfo, daySessions: (LocalDate) -> kotlinx.coroutines.flow.Flow<List<ReadingSessionEntity>>, onOpen: (Long) -> Unit) {
    val today = LocalDate.now()
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text(stringResource(R.string.reading_calendar), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                MonthBrowser(streak, daySessions) { open = false; onOpen(it) }
            }
        }
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { open = true }) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.reading_calendar), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Cells of at most ~22dp: phones show ~4 months, wide screens up to a year.
            val weeks = (maxWidth.value / 22f).toInt().coerceIn(12, 53)
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
            Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
                val cell = size.width / weeks
                val gap = cell * .18f
                for (w in 0 until weeks) for (d in 0 until 7) {
                    val date = start.plusDays((w * 7 + d).toLong())
                    if (date.isAfter(today)) continue
                    val state = streak.days[date]
                    val minutes = streak.minutes[date] ?: 0L
                    val color = when {
                        state == DayState.FROZEN -> Color(0xFF7FB8E0)
                        state == DayState.MET -> primary.copy(alpha = (0.55f + (minutes / (streak.target * 3f).coerceAtLeast(1f))).coerceAtMost(1f))
                        minutes > 0 -> primary.copy(alpha = .28f)
                        else -> empty
                    }
                    drawRoundRect(
                        color,
                        topLeft = Offset(w * cell + gap / 2, d * cell + gap / 2),
                        size = Size(cell - gap, cell - gap),
                        cornerRadius = CornerRadius(cell * .22f),
                    )
                }
            }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(empty, stringResource(R.string.legend_none))
                Spacer(Modifier.width(12.dp))
                Legend(primary.copy(alpha = .28f), stringResource(R.string.legend_some))
                Spacer(Modifier.width(12.dp))
                Legend(primary, stringResource(R.string.legend_met))
                Spacer(Modifier.width(12.dp))
                Legend(Color(0xFF7FB8E0), stringResource(R.string.legend_freeze))
            }
        }
    }
}

/** One month at a time with arrows to go back (as far as the first recorded reading) and forward to today. */
@Composable
private fun MonthBrowser(streak: StreakInfo, daySessions: (LocalDate) -> kotlinx.coroutines.flow.Flow<List<ReadingSessionEntity>>, onOpen: (Long) -> Unit) {
    val today = LocalDate.now()
    val thisMonth = YearMonth.from(today)
    val earliest = (streak.minutes.keys + streak.days.keys).minOrNull()?.let { YearMonth.from(it) } ?: thisMonth
    var month by remember { mutableStateOf(thisMonth) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // Monday first
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { month = month.minusMonths(1); picked = null }, enabled = month > earliest) {
            Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.calendar_prev_month))
        }
        Text(
            month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) },
            Modifier.weight(1f).clickable(enabled = month != thisMonth) { month = thisMonth; picked = null },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { month = month.plusMonths(1); picked = null }, enabled = month < thisMonth) {
            Icon(Icons.Rounded.ChevronRight, stringResource(R.string.calendar_next_month))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { d ->
            Text(
                d.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (c in 0 until 7) {
                val date = first.plusDays((r * 7 + c - lead).toLong())
                val inMonth = YearMonth.from(date) == month && !date.isAfter(today)
                val minutes = streak.minutes[date] ?: 0L
                val state = streak.days[date]
                val fill = when {
                    !inMonth -> Color.Transparent
                    state == DayState.FROZEN -> Color(0xFF7FB8E0)
                    state == DayState.MET -> primary.copy(alpha = (0.55f + (minutes / (streak.target * 3f).coerceAtLeast(1f))).coerceAtMost(1f))
                    minutes > 0 -> primary.copy(alpha = .28f)
                    else -> empty
                }
                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (YearMonth.from(date) == month) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(fill)
                                .then(if (date == picked) Modifier.border(2.dp, primary, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable(enabled = inMonth) { picked = if (picked == date) null else date },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${date.dayOfMonth}", fontSize = 12.sp,
                                color = if (date == today) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (inMonth) 1f else .3f),
                                fontWeight = if (date == today) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            )
                        }
                    }
                }
            }
        }
    }
    val monthMinutes = (1..month.lengthOfMonth()).sumOf { streak.minutes[month.atDay(it)] ?: 0L }
    val readDays = (1..month.lengthOfMonth()).count { (streak.minutes[month.atDay(it)] ?: 0L) > 0 }
    val day = picked
    Text(
        if (day != null) {
            val m = streak.minutes[day] ?: 0L
            day.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)) + " · " +
                if (m > 0) formatDuration(m * 60) else stringResource(R.string.legend_none)
        } else stringResource(R.string.calendar_month_summary, formatDuration(monthMinutes * 60), readDays),
        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (day != null) {
        val sessions by remember(day) { daySessions(day) }.collectAsStateWithLifecycle(emptyList())
        val timeFmt = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            sessions.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(s.bookId) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.listening) "🎧" else "📖", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.bookTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            Instant.ofEpochMilli(s.startTime).atZone(ZoneId.systemDefault()).format(timeFmt) + " – " +
                                Instant.ofEpochMilli(s.endTime).atZone(ZoneId.systemDefault()).format(timeFmt) +
                                if (s.pagesTurned > 0) " · " + pluralStringResource(R.plurals.sessions_pages, s.pagesTurned, s.pagesTurned) else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDuration(s.durationSeconds.toLong()), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopBooks(top: List<BookTotal>, onOpen: (Long) -> Unit) {
    val max = top.maxOf { it.seconds }.coerceAtLeast(1)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            top.forEach { b ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(b.bookId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row {
                        Text(b.bookTitle, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatDuration(b.seconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color.Gray.copy(alpha = .15f))
                            drawRect(Flame, size = Size(size.width * b.seconds / max, size.height))
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun RecentSessions(list: List<ReadingSessionEntity>, onOpen: (Long) -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val timeFmt = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            list.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s.bookId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (s.listening) "🎧" else "📖", fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.bookTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            Instant.ofEpochMilli(s.startTime).atZone(ZoneId.systemDefault()).format(timeFmt) +
                                stringResource(R.string.session_meta, s.pagesTurned, "%.1f".format(s.endProgress - s.startProgress)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDuration(s.durationSeconds.toLong()), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
