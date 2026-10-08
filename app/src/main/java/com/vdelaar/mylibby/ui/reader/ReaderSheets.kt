package com.vdelaar.mylibby.ui.reader

import androidx.compose.foundation.background
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdelaar.mylibby.core.database.AnnotationEntity
import com.vdelaar.mylibby.core.database.BookmarkEntity
import com.vdelaar.mylibby.core.datastore.PageFlow
import com.vdelaar.mylibby.core.datastore.ReaderFont
import com.vdelaar.mylibby.core.datastore.ReaderSettings
import com.vdelaar.mylibby.core.datastore.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    if (showInfo) ReaderInfoSheet(settings, update, onDismiss = { showInfo = false })
    ModalBottomSheet(onDismissRequest = onDismiss, scrimColor = Color.Black.copy(alpha = .2f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            // Font size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.text_size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { update { it.copy(fontSizePx = (it.fontSizePx - 1).coerceAtLeast(12)) } }) { Icon(Icons.Rounded.Remove, stringResource(R.string.smaller_text)) }
                Text("${settings.fontSizePx}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { update { it.copy(fontSizePx = (it.fontSizePx + 1).coerceAtMost(40)) } }) { Icon(Icons.Rounded.Add, stringResource(R.string.larger_text)) }
            }
            Spacer(Modifier.height(16.dp))

            // Page colour
            Text(stringResource(R.string.page_colour), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ReaderTheme.entries) { t ->
                    val selected = settings.theme == t
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { update { it.copy(theme = t) } }.padding(4.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(Color(t.bg))
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = Color(t.fg), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) }
                        Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.dark_at_night), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(settings.followSystemDark, { v -> update { it.copy(followSystemDark = v) } })
            }
            Spacer(Modifier.height(16.dp))

            // Font
            Text(stringResource(R.string.font), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReaderFont.entries) { f ->
                    FilterChip(selected = settings.font == f, onClick = { update { it.copy(font = f) } }, label = { Text(f.labelRes?.let { stringResource(it) } ?: f.label) })
                }
            }
            Spacer(Modifier.height(16.dp))

            // Spacing
            LabeledSlider(stringResource(R.string.line_spacing), "%.2f".format(settings.lineHeight), settings.lineHeight, 1.1f..2.2f) { v -> update { it.copy(lineHeight = (v * 20).toInt() / 20f) } }
            LabeledSlider(stringResource(R.string.margins), "${settings.marginPercent}%", settings.marginPercent.toFloat(), 0f..16f) { v -> update { it.copy(marginPercent = v.toInt()) } }
            LabeledSlider(stringResource(R.string.paragraph_spacing), "%.1f".format(settings.paragraphSpacing), settings.paragraphSpacing, 0f..1.6f) { v -> update { it.copy(paragraphSpacing = (v * 10).toInt() / 10f) } }

            // Guided colours
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.guided_colors), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.guided_colors_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.guidedColors, { v -> update { it.copy(guidedColors = v) } })
            }
            if (settings.guidedColors) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GuidePalettes.forEachIndexed { i, (a, b) ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(a), Color(b))))
                                .border(if (settings.guidePalette == i) 3.dp else 1.dp, if (settings.guidePalette == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { update { it.copy(guidePalette = i) } },
                        )
                    }
                }
                LabeledSlider(stringResource(R.string.guided_intensity), "${(settings.guideIntensity * 100).toInt()}%", settings.guideIntensity, 0.2f..1f) { v -> update { it.copy(guideIntensity = (v * 20).toInt() / 20f) } }
            }
            Spacer(Modifier.height(16.dp))

            // Layout
            Text(stringResource(R.string.page_turning), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PageFlow.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = settings.flow == f,
                        onClick = { update { it.copy(flow = f) } },
                        shape = SegmentedButtonDefaults.itemShape(i, PageFlow.entries.size),
                    ) { Text(stringResource(if (f == PageFlow.PAGINATED) R.string.pages else R.string.scroll)) }
                }
            }
            ToggleRow(stringResource(R.string.justify_text), settings.justify) { v -> update { it.copy(justify = v) } }
            ToggleRow(stringResource(R.string.hyphenation), settings.hyphenate) { v -> update { it.copy(hyphenate = v) } }
            ToggleRow(stringResource(R.string.two_pages_wide), settings.twoColumnsOnWide) { v -> update { it.copy(twoColumnsOnWide = v) } }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showInfo = true }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.info_header_footer), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.info_header_footer_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = current, onValueChange = onChange, valueRange = range)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    toc: List<TocEntry>,
    currentHref: String?,
    annotations: List<AnnotationEntity>,
    bookmarks: List<BookmarkEntity>,
    onGo: (String) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.85f)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(stringResource(R.string.contents), stringResource(R.string.highlights_n, annotations.size), stringResource(R.string.bookmarks_n, bookmarks.size)).forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (toc.isEmpty()) item { Text(stringResource(R.string.no_toc), Modifier.padding(24.dp)) }
                    items(toc) { e ->
                        val current = currentHref != null && e.href.substringBefore('#') == currentHref.substringBefore('#')
                        Text(
                            e.label,
                            style = if (e.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (current) FontWeight.SemiBold else null,
                            modifier = Modifier.fillMaxWidth().clickable { onGo(e.href) }.padding(start = (24 + e.depth * 16).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                        )
                    }
                }
                1 -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (annotations.isEmpty()) item { Text(stringResource(R.string.no_highlights), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(annotations, key = { it.localId }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onGo(a.cfi) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(Color(android.graphics.Color.parseColor(a.color))))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                a.chapterTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                                a.note?.takeIf { it.isNotBlank() }?.let { Text("📝 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (bookmarks.isEmpty()) item { Text(stringResource(R.string.no_bookmarks), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(bookmarks, key = { it.localId }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { onGo(b.cfi) }.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title ?: stringResource(R.string.bookmark), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text("${b.percent.toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete_bookmark)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    results: List<Pair<String, SearchItem>>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.85f).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_this_book)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val resultsLabel = pluralStringResource(R.plurals.search_results_n, results.size, results.size)
            val hint = if (!searching && results.isNotEmpty()) resultsLabel else null
            hint?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results) { (label, item) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onGo(item.cfi) }.padding(12.dp)) {
                        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            buildAnnotatedString {
                                append(item.pre)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer)) { append(item.match) }
                                append(item.post)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Swatches for the guided colour palettes (must match PALETTES in reader.js). */
private val GuidePalettes = listOf(
    0xFF1D4ED8 to 0xFF0D9488,
    0xFFC2410C to 0xFFBE185D,
    0xFF15803D to 0xFFA16207,
    0xFF6D28D9 to 0xFF0369A1,
)
