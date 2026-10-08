package com.vdelaar.mylibby.ui.bookdetail

import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhoneAndroid
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vdelaar.mylibby.core.database.DownloadEntity
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.ReadStatus
import com.vdelaar.mylibby.core.model.formatSeriesNumber
import com.vdelaar.mylibby.data.formatDuration
import androidx.compose.ui.res.pluralStringResource
import com.vdelaar.mylibby.data.formatMinutes
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.BookCover
import com.vdelaar.mylibby.ui.components.ErrorState
import com.vdelaar.mylibby.ui.components.StarRating
import com.vdelaar.mylibby.ui.components.coverUrl
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BookDetailScreen(bookId: Long, navigator: AppNavigator, onBack: () -> Unit) {
    BookDetailContent(bookId, navigator, onBack = onBack, inPane = false)
}

/** Book details. Used full screen on phones and as the detail pane on foldables/tablets. */
@Composable
fun BookDetailContent(bookId: Long, navigator: AppNavigator, onBack: (() -> Unit)?, inPane: Boolean) {
    val vm = appViewModel(key = "book-$bookId") { BookDetailViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val isFav by vm.isFavourite.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val secondsRead by vm.secondsRead.collectAsStateWithLifecycle()
    val pace by vm.pace.collectAsStateWithLifecycle()
    val appForPace by com.vdelaar.mylibby.ui.appContainer().settings.app.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)) { padding ->
        val book = state.book
        when {
            book == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book == null -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                ErrorState(state.error ?: stringResource(R.string.not_found), onRetry = vm::load)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                Header(book, onBack, inPane)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.authorLine,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = book.authors.isNotEmpty()) { navigator.author(book.authors.first()) }.padding(4.dp),
                    )
                    book.seriesName?.let { s ->
                        Text(
                            stringResource(R.string.book_in_series, book.seriesNumber?.let { formatSeriesNumber(it) } ?: "?", s),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { navigator.series(s) }.padding(4.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (book.isLocal) {
                        LocalBookActions(
                            book = book,
                            isFav = isFav,
                            onFav = vm::toggleFavourite,
                            onRead = { navigator.read(book.id) },
                            onShare = { book.localPath?.let { ShareHelper.shareFile(context, book, File(it)) } },
                            onDelete = { vm.deleteLocal { onBack?.invoke() ?: navigator.back() } },
                        )
                    } else PrimaryActions(
                        book = book,
                        isFav = isFav,
                        download = download,
                        onRead = { navigator.read(book.id) },
                        onFav = vm::toggleFavourite,
                        onDownload = vm::download,
                        onRemoveDownload = vm::removeDownload,
                        onShare = { scope.launch { ShareHelper.shareBook(context, c, book) } },
                        onShareFile = download?.filePath?.let { path -> { ShareHelper.shareFile(context, book, File(path)) } },
                    )
                    Spacer(Modifier.height(20.dp))
                    InfoRow(book, state.minutesLeft, secondsRead)
                    pace?.let {
                        Spacer(Modifier.height(12.dp))
                        PaceCard(
                            it, finished = book.readStatus == "READ" || (book.progress ?: 0f) >= 98f,
                            referencePhrase = stringResource(appForPace.paceReference.phrase),
                            onChange = { navigator.settingsPage("READING") },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    StatusRow(book.readStatus, vm::setStatus)
                    if (!book.isLocal) {
                        Spacer(Modifier.height(12.dp))
                        RatingRow(book.rating, vm::setRating)
                    }
                    book.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Description(it)
                    }
                    if (book.categories.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Genres(book.categories)
                    }
                }
                if (state.reviews.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Reviews(state.reviews, Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally))
                }
                val others = state.seriesBooks.filter { it.id != book.id }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.more_in_series, book.seriesName.orEmpty()), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(others, key = { it.id }) { b ->
                            Column(Modifier.width(96.dp).clip(RoundedCornerShape(8.dp)).clickable { navigator.openBook(b.id) }) {
                                BookCover(b, Modifier.fillMaxWidth(), showProgress = true)
                                Spacer(Modifier.height(4.dp))
                                Text("#${b.seriesNumber?.let { formatSeriesNumber(it) } ?: "–"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(b.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (state.similar.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.similar_books), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.similar, key = { it.id }) { b ->
                            Column(Modifier.width(104.dp).clip(RoundedCornerShape(8.dp)).clickable { navigator.openBook(b.id) }) {
                                BookCover(b, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text(b.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(b.authorLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun Header(book: Book, onBack: (() -> Unit)?, inPane: Boolean) {
    Box(Modifier.fillMaxWidth().height(if (inPane) 300.dp else 360.dp)) {
        AsyncImage(
            model = coverUrl(book, thumbnail = true),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(36.dp),
            alpha = .6f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = .6f), MaterialTheme.colorScheme.surface))
            )
        )
        BookCover(
            book,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).height(if (inPane) 220.dp else 260.dp),
            thumbnail = false,
            elevation = 18.dp,
        )
        if (onBack != null) {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(8.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .7f)),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun PrimaryActions(
    book: Book,
    isFav: Boolean,
    download: DownloadEntity?,
    onRead: () -> Unit,
    onFav: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onShare: () -> Unit,
    onShareFile: (() -> Unit)?,
) {
    val progress = book.progress ?: 0f
    Button(
        onClick = onRead,
        enabled = book.isReadable,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                !book.isReadable -> stringResource(R.string.format_unreadable, book.primaryFileType ?: stringResource(R.string.this_format))
                progress >= 1f && progress < 99.5f -> stringResource(R.string.continue_reading_pct, progress.toInt())
                progress > 0f && progress < 1f -> stringResource(R.string.continue_reading)
                progress >= 99.5f -> stringResource(R.string.read_again)
                else -> stringResource(R.string.start_reading)
            }
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.favorite), tint = if (isFav) Color(0xFFE5484D) else null, onClick = onFav)
        when (download?.state) {
            DownloadState.DONE -> {
                var confirm by remember { mutableStateOf(false) }
                ActionButton(Icons.Rounded.DownloadDone, stringResource(R.string.on_device), onClick = { confirm = true })
                if (confirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text(stringResource(R.string.remove_from_device_q)) },
                        text = { Text(stringResource(R.string.remove_from_device_body)) },
                        confirmButton = { TextButton(onClick = { confirm = false; onRemoveDownload() }) { Text(stringResource(R.string.remove)) } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.keep)) } },
                    )
                }
            }
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { download.progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                Text(stringResource(R.string.saving), style = MaterialTheme.typography.labelMedium)
            }
            else -> ActionButton(Icons.Rounded.CloudDownload, stringResource(if (download?.state == DownloadState.FAILED) R.string.action_retry else R.string.download), enabled = book.isReadable, onClick = onDownload)
        }
        ActionButton(Icons.Rounded.Share, stringResource(R.string.share), onClick = onShare)
        if (onShareFile != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                ActionButton(Icons.Rounded.MoreVert, stringResource(R.string.more), onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.send_book_file)) }, onClick = { menu = false; onShareFile() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_device)) }, onClick = { menu = false; onRemoveDownload() })
                }
            }
        }
    }
}

@Composable
private fun LocalBookActions(book: Book, isFav: Boolean, onFav: () -> Unit, onRead: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Button(onClick = onRead, enabled = book.isReadable, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
        Spacer(Modifier.width(10.dp))
        val p = book.progress ?: 0f
        Text(
            when {
                p >= 1f && p < 99.5f -> stringResource(R.string.continue_reading_pct, p.toInt())
                p >= 99.5f -> stringResource(R.string.read_again)
                else -> stringResource(R.string.start_reading)
            }
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.favorite), tint = if (isFav) Color(0xFFE5484D) else null, onClick = onFav)
        ActionButton(Icons.Rounded.Share, stringResource(R.string.share), onClick = onShare)
        ActionButton(Icons.Rounded.Delete, stringResource(R.string.delete), onClick = { confirm = true })
    }
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.local_book_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_local_q)) },
            text = { Text(stringResource(R.string.delete_local_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, tint = tint ?: androidx.compose.material3.LocalContentColor.current)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun InfoRow(book: Book, minutesLeft: Double?, secondsRead: Long) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Only show what we actually know.
            val facts = listOfNotNull(
                book.pageCount?.let { it.toString() to stringResource(R.string.fact_pages) },
                book.language?.takeIf { it.isNotBlank() }?.let { it.uppercase().take(5) to stringResource(R.string.fact_language) },
                minutesLeft?.let { formatMinutes(it) to stringResource(if ((book.progress ?: 0f) > 0f) R.string.fact_left else R.string.fact_to_read) },
                book.publishedDate?.take(4)?.let { it to stringResource(R.string.fact_published) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                facts.forEach { (value, label) -> Info(value, label) }
            }
            val p = book.progress ?: 0f
            if (p > 0f || secondsRead > 0) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.weight(1f).height(6.dp), drawStopIndicator = {})
                    Spacer(Modifier.width(12.dp))
                    Text("${p.toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                if (secondsRead > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.time_spent_book, formatDuration(secondsRead)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** How fast this book is being read (or was read), set against the average adult reader. */
@Composable
private fun PaceCard(pace: com.vdelaar.mylibby.data.BookPace, finished: Boolean, referencePhrase: String, onChange: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.pace_title), style = MaterialTheme.typography.titleSmall)
            val time = formatDuration(pace.readSeconds)
            Text(
                if (finished) pluralStringResource(R.plurals.pace_finished, pace.spanDays, time, pace.spanDays)
                else pluralStringResource(R.plurals.pace_so_far, pace.spanDays, time, pace.spanDays),
                style = MaterialTheme.typography.bodyMedium,
            )
            pace.wordsPerMinute?.let { wpm ->
                val pct = pace.percentVsAverage ?: 0
                val avg = pace.referenceWpm
                Text(
                    when {
                        pct >= 5 -> stringResource(R.string.pace_faster, wpm, pct, avg, referencePhrase)
                        pct <= -5 -> stringResource(R.string.pace_slower, wpm, -pct, avg, referencePhrase)
                        else -> stringResource(R.string.pace_same, wpm, avg, referencePhrase)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            pace.averageSeconds?.let {
                Text(stringResource(R.string.pace_average_needs, formatDuration(it), pace.referenceWpm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.TextButton(onClick = onChange, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.pace_ref_change), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun Info(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(status: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.status_label, stringResource(ReadStatus.labelRes(status))))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReadStatus.all.forEach { s ->
                DropdownMenuItem(text = { Text(stringResource(ReadStatus.labelRes(s))) }, onClick = { open = false; onChange(s) })
            }
        }
    }
}

@Composable
private fun Description(html: String) {
    var expanded by remember { mutableStateOf(false) }
    val text = remember(html) { HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Text(stringResource(R.string.about_book), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (text.length > 300) TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.show_less else R.string.read_more)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Genres(genres: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
    }
}

@Composable
private fun RatingRow(rating: Int?, onChange: (Int?) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.your_rating), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (rating == null) stringResource(R.string.rating_hint) else stringResource(R.string.rating_value, rating / 2f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StarRating(rating ?: 0, starSize = 34.dp, onChange = onChange)
        }
    }
}

@Composable
private fun Reviews(reviews: List<com.vdelaar.mylibby.core.network.BookReviewDto>, modifier: Modifier = Modifier) {
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) reviews else reviews.take(3)
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.reviews_title, reviews.size), style = MaterialTheme.typography.titleLarge)
        shown.forEach { ReviewCard(it) }
        if (reviews.size > 3) TextButton(onClick = { showAll = !showAll }) { Text(stringResource(if (showAll) R.string.show_less else R.string.show_all_reviews, reviews.size)) }
    }
}

@Composable
private fun ReviewCard(r: com.vdelaar.mylibby.core.network.BookReviewDto) {
    var expanded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(r.spoiler != true) }
    val body = remember(r.body) { r.body?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty() }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.reviewerName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.anonymous_reviewer), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(r.metadataProvider?.lowercase()?.replaceFirstChar { it.uppercase() }, r.date?.take(10)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                r.rating?.let { StarRating(Math.round(it * 2), starSize = 16.dp) }
            }
            r.title?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                if (revealed) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = { revealed = true }) { Text(stringResource(R.string.review_spoiler)) }
                }
            }
        }
    }
}
