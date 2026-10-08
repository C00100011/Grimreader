package com.vdelaar.mylibby.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.SpeedMode
import com.vdelaar.mylibby.core.datastore.SpeedReadSettings
import com.vdelaar.mylibby.core.datastore.SpeedStyle
import com.vdelaar.mylibby.data.formatMinutes

private val DyslexicFont = FontFamily(Font(R.font.speed_dyslexic))

/**
 * Full-screen speed reading (RSVP): one word, or a short phrase, at a fixed spot at a steady pace.
 * Tap the words to pause; the page underneath follows along so your place is never lost.
 */
@Composable
fun SpeedReadOverlay(
    state: SpeedUiState,
    settings: SpeedReadSettings,
    bg: Color,
    fg: Color,
    accent: Color,
    title: String,
    chapter: String,
    bookFraction: Float,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onWpm: (Int) -> Unit,
    onWpmFinished: () -> Unit,
    onSettings: ((SpeedReadSettings) -> SpeedReadSettings) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // Keep the screen awake while words are running.
    val view = LocalView.current
    DisposableEffect(state.playing) {
        if (state.playing) view.keepScreenOn = true
        onDispose { if (state.playing) view.keepScreenOn = false }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val muted = fg.copy(alpha = .6f)

    val scheme = MaterialTheme.colorScheme.copy(
        primary = accent, onPrimary = bg,
        surface = bg, onSurface = fg, onSurfaceVariant = muted,
        secondaryContainer = fg.copy(alpha = .16f), onSecondaryContainer = fg,
        surfaceVariant = fg.copy(alpha = .14f), outline = fg.copy(alpha = .4f), outlineVariant = fg.copy(alpha = .2f),
        primaryContainer = accent.copy(alpha = .3f), onPrimaryContainer = fg,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
    Surface(color = bg, contentColor = fg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.speed_close), tint = fg) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapter.isNotBlank()) Text(chapter, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Rounded.Tune, stringResource(R.string.speed_settings), tint = if (showSettings) accent else fg) }
            }

            // The words
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading && state.unit == null -> CircularProgressIndicator(color = accent)
                    state.finished -> Text(stringResource(R.string.speed_end), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                    state.unit != null -> {
                        SpeedDisplay(state.unit, settings, fg, accent)
                        if (!state.playing) {
                            Text(
                                stringResource(R.string.speed_tap_start),
                                style = MaterialTheme.typography.labelLarge, color = muted,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                            )
                        }
                    }
                }
            }

            // Controls
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).align(Alignment.CenterHorizontally).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                val wordsLeft = (state.wordCount - state.wordIndex).coerceAtLeast(0)
                LinearProgressIndicator(progress = { bookFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = accent, trackColor = fg.copy(alpha = .12f), drawStopIndicator = {})
                Text(
                    stringResource(R.string.speed_left_chapter, formatMinutes(SpeedReading.minutesLeft(wordsLeft, state.wpm)), state.wpm) + " · ${(bookFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(end = 20.dp).size(52.dp)) { Icon(Icons.Rounded.Replay, stringResource(R.string.speed_back_sentence)) }
                    }
                    FilledIconButton(onClick = onToggle, enabled = !state.loading && !state.finished, modifier = Modifier.size(68.dp)) {
                        Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (state.playing) R.string.pause else R.string.resume), modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.weight(1f)) // keeps the play button in the middle
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onWpm(state.wpm - 25); onWpmFinished() }) { Icon(Icons.Rounded.Remove, stringResource(R.string.speed_slower)) }
                    Slider(
                        value = state.wpm.toFloat(),
                        onValueChange = { onWpm(((it / 25).toInt() * 25)) },
                        onValueChangeFinished = onWpmFinished,
                        valueRange = SpeedReading.MIN_WPM.toFloat()..SpeedReading.MAX_WPM.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onWpm(state.wpm + 25); onWpmFinished() }) { Icon(Icons.Rounded.Add, stringResource(R.string.speed_faster)) }
                }
                Text(stringResource(R.string.speed_wpm, state.wpm), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))

                if (showSettings) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 260.dp)) { SpeedSettingsPanel(settings, onSettings) }
                }
            }
        }
    }
    }
}

@Composable
private fun SpeedSettingsPanel(settings: SpeedReadSettings, update: ((SpeedReadSettings) -> SpeedReadSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpeedMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = settings.mode == m,
                    onClick = { update { it.copy(mode = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, SpeedMode.entries.size),
                ) { Text(stringResource(if (m == SpeedMode.WORD) R.string.speed_mode_word else R.string.speed_mode_phrase)) }
            }
        }
        if (settings.mode == SpeedMode.PHRASE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.speed_phrase_size), modifier = Modifier.weight(1f))
                (2..4).forEach { n ->
                    FilterChip(selected = settings.phraseWords == n, onClick = { update { it.copy(phraseWords = n) } }, label = { Text("$n") }, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SpeedStyle.entries) { s ->
                FilterChip(selected = settings.style == s, onClick = { update { it.copy(style = s) } }, label = { Text(stringResource(s.label)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.speed_text_size), modifier = Modifier.padding(end = 12.dp))
            Slider(value = settings.fontSizeSp.toFloat(), onValueChange = { v -> update { it.copy(fontSizeSp = v.toInt()) } }, valueRange = 24f..72f, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedDisplay(unit: SpeedUnit, settings: SpeedReadSettings, fg: Color, accent: Color) {
    val family = when (settings.style) {
        SpeedStyle.MONOSPACE -> FontFamily.Monospace
        SpeedStyle.DYSLEXIC -> DyslexicFont
        SpeedStyle.FOCUS_BOLD -> FontFamily.SansSerif
        else -> FontFamily.Serif
    }
    val phrase = settings.mode == SpeedMode.PHRASE && unit.first != unit.last
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        val maxWidthSp = maxWidth.value
        val base = settings.fontSizeSp.toFloat()
        // Long words shrink to fit instead of running off the screen.
        val charWidth = if (settings.style == SpeedStyle.MONOSPACE) 0.62f else 0.58f
        val size: Float
        val content: @Composable () -> Unit
        if (phrase) {
            size = minOf(base, maxWidthSp / (unit.text.length * charWidth).coerceAtLeast(1f))
            content = { Text(unit.text, fontSize = size.sp, fontFamily = family, color = fg, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
        } else {
            val orp = SpeedReading.orpIndex(unit.text).coerceIn(0, (unit.text.length - 1).coerceAtLeast(0))
            val before = unit.text.take(orp)
            val pivot = unit.text.getOrNull(orp)?.toString().orEmpty()
            val after = unit.text.drop(orp + 1)
            size = minOf(base, (maxWidthSp / 2f) / (maxOf(before.length, after.length + 1) * charWidth).coerceAtLeast(1f))
            val bold = settings.style == SpeedStyle.FOCUS_BOLD
            content = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text(before, fontSize = size.sp, fontFamily = family, color = fg, maxLines = 1, softWrap = false)
                    }
                    Text(
                        pivot, fontSize = (if (bold) size * 1.1f else size).sp, fontFamily = family, color = accent,
                        fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(after, fontSize = size.sp, fontFamily = family, color = fg, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        val reticle = settings.style == SpeedStyle.RETICLE
        Box(
            Modifier.fillMaxWidth().then(
                if (reticle) Modifier.drawBehind {
                    val gap = size.sp.toPx() * 1.05f
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val line = fg.copy(alpha = .28f)
                    drawLine(line, Offset(this.size.width * .1f, cy - gap), Offset(this.size.width * .9f, cy - gap), 2f)
                    drawLine(line, Offset(this.size.width * .1f, cy + gap), Offset(this.size.width * .9f, cy + gap), 2f)
                    val tick = size.sp.toPx() * .4f
                    drawLine(accent, Offset(cx, cy - gap), Offset(cx, cy - gap + tick), 4f)
                    drawLine(accent, Offset(cx, cy + gap), Offset(cx, cy + gap - tick), 4f)
                } else Modifier
            ).padding(vertical = (size * 1.4f).dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
