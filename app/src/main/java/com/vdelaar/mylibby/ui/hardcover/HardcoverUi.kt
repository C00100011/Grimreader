package com.vdelaar.mylibby.ui.hardcover

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.HardcoverException
import com.vdelaar.mylibby.data.HardcoverBook
import com.vdelaar.mylibby.data.HardcoverDuration
import com.vdelaar.mylibby.data.inLibrary
import com.vdelaar.mylibby.ui.components.GeneratedCover
import com.vdelaar.mylibby.ui.components.SectionHeader
import java.util.Locale

/** Hardcover rows for the Discover tab: trending (with a period picker) and suggestions for you. */
@Composable
fun HardcoverBlock(vm: HardcoverViewModel, onBook: (HardcoverBook) -> Unit, onSetup: () -> Unit) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    LaunchedEffect(connected) { if (connected) vm.load() else vm.reset() }

    if (!connected) {
        ConnectCard(onSetup)
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionHeader(stringResource(R.string.hc_trending))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HardcoverDuration.entries) { d ->
                FilterChip(selected = d == duration, onClick = { vm.setDuration(d) }, label = { Text(stringResource(d.label)) })
            }
        }
        Spacer(Modifier.height(10.dp))
        HardcoverRow(trending, emptyText = stringResource(R.string.hc_trending_empty), onRetry = vm::retryTrending, onBook = onBook, map = { it })

        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.hc_suggestions))
        (suggestions as? HcLoad.Data)?.value?.because?.takeIf { it.isNotEmpty() }?.let {
            Text(
                stringResource(R.string.hc_because, it.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        HardcoverRow(suggestions, emptyText = stringResource(R.string.hc_suggestions_empty), onRetry = vm::retrySuggestions, onBook = onBook, map = { it.books })
        Text(
            stringResource(R.string.hc_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
    }
}

@Composable
private fun ConnectCard(onSetup: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📈", style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.hc_connect_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hc_connect_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onSetup) { Text(stringResource(R.string.hc_connect_button)) }
        }
    }
}

@Composable
private fun <T> HardcoverRow(state: HcLoad<T>, emptyText: String, onRetry: () -> Unit, onBook: (HardcoverBook) -> Unit, map: (T) -> List<HardcoverBook>) {
    when (state) {
        HcLoad.Idle, HcLoad.Loading -> Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        is HcLoad.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hardcoverErrorText(state.error), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
        }
        is HcLoad.Data -> {
            val books = map(state.value)
            if (books.isEmpty()) Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            else LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(books, key = { it.id }) { b -> HardcoverCard(b) { onBook(b) } }
            }
        }
    }
}

@Composable
private fun HardcoverCard(book: HardcoverBook, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        HardcoverCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.authors.isNotEmpty()) Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ratingLine(book)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun HardcoverCover(book: HardcoverBook, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Surface(modifier.aspectRatio(2f / 3f), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
        if (book.imageUrl == null) GeneratedCover(book.title, book.authorLine, Modifier.fillMaxWidth())
        else SubcomposeAsyncImage(
            model = book.imageUrl,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(),
            loading = { GeneratedCover(book.title, book.authorLine, Modifier.fillMaxWidth()) },
            error = { GeneratedCover(book.title, book.authorLine, Modifier.fillMaxWidth()) },
        )
    }
}

/** Details of a Hardcover book, with what you can do about it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardcoverBookSheet(book: HardcoverBook, library: List<Book>, canFind: Boolean, onFind: () -> Unit, onOpenLibrary: (Book) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mine = inLibrary(book, library)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HardcoverCover(book, Modifier.width(110.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    book.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (book.authors.isNotEmpty()) Text(book.authorLine, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    book.series?.let { s -> Text(book.seriesPosition?.let { "$s #${formatPosition(it)}" } ?: s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val facts = listOfNotNull(
                        ratingLine(book, full = true),
                        book.readers.takeIf { it > 0 }?.let { stringResource(R.string.hc_readers, formatCount(it)) },
                        book.year?.toString(),
                        book.pages?.let { stringResource(R.string.hc_pages, it) },
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            book.description?.let { d ->
                val text = HtmlCompat.fromHtml(d, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
            if (mine != null) Button(onClick = { onOpenLibrary(mine) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hc_open_library)) }
            else if (canFind) Button(onClick = onFind, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hc_find_it)) }
            book.url?.let { url ->
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.hc_view_on))
                }
            }
            Text(stringResource(R.string.hc_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }
}

@Composable
fun hardcoverErrorText(e: HardcoverException): String = stringResource(
    when (e.kind) {
        HardcoverException.Kind.NO_KEY, HardcoverException.Kind.INVALID_KEY -> R.string.hc_error_invalid
        HardcoverException.Kind.MISSING_SCOPE -> R.string.hc_error_scope
        HardcoverException.Kind.RATE_LIMITED -> R.string.hc_error_rate
        HardcoverException.Kind.OFFLINE -> R.string.hc_error_offline
        HardcoverException.Kind.SERVER, HardcoverException.Kind.BAD_QUERY -> R.string.hc_error_server
    }
)

@Composable
private fun ratingLine(b: HardcoverBook, full: Boolean = false): String? {
    val r = b.rating?.takeIf { it > 0 } ?: return null
    val stars = "★ " + String.format(Locale.US, "%.1f", r)
    return if (full && b.ratingsCount > 0) stringResource(R.string.hc_rating_full, stars, formatCount(b.ratingsCount)) else stars
}

internal fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

private fun formatPosition(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
