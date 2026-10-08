package com.vdelaar.mylibby.ui.newbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.BookIdea
import com.vdelaar.mylibby.data.FreeBook
import com.vdelaar.mylibby.data.languageName
import com.vdelaar.mylibby.ui.components.GeneratedCover
import com.vdelaar.mylibby.ui.components.SectionHeader
import com.vdelaar.mylibby.ui.navigation.AppNavigator

/**
 * New books for the Discover tab: this week's picks to swipe through, new books in your genres and languages, and
 * free classics. Everything comes from public catalogues that need no account.
 */
@Composable
fun NewBooksBlock(vm: NewBooksViewModel, navigator: AppNavigator, onIdea: (BookIdea) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wanted by vm.wanted.collectAsStateWithLifecycle()
    var free by remember { mutableStateOf<FreeBook?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SwipeEntry(wantedCount = wanted.size, onSwipe = navigator::swipe, onWanted = navigator::wanted)

        val taste = state.taste
        if (taste != null && taste.languages.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(taste.languages) { iso ->
                    FilterChip(selected = iso in state.languages, onClick = { vm.toggleLanguage(iso) }, label = { Text(languageName(iso)) })
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        when {
            state.loading && state.rows.isEmpty() -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            state.error != null && state.rows.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (state.offline) R.string.nb_offline else R.string.nb_error),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                TextButton(onClick = vm::reload) { Text(stringResource(R.string.action_try_again)) }
            }
            else -> {
                if (taste != null && !taste.personal) {
                    Text(stringResource(R.string.nb_starter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (state.rows.isEmpty()) Text(stringResource(R.string.nb_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                state.rows.forEach { row ->
                    SectionHeader(stringResource(R.string.nb_new_in, genreTitle(row.genre)))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(row.ideas, key = { it.key }) { idea -> IdeaCard(idea, wanted[idea.key]) { onIdea(idea) } }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (state.free.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.nb_free))
                    Text(stringResource(R.string.nb_free_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.free, key = { it.id }) { b -> FreeCard(b, added = b.id in state.added) { free = b } }
                    }
                }
                Text(stringResource(R.string.ol_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp))
            }
        }
    }

    free?.let { b -> FreeSheet(b, adding = state.adding == b.id, added = b.id in state.added, onAdd = { vm.addFree(b) }, onDismiss = { free = null }) }
}

/** The way into this week's swipe deck and the want lists. */
@Composable
fun SwipeEntry(wantedCount: Int, onSwipe: () -> Unit, onWanted: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onSwipe),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🃏", style = MaterialTheme.typography.displaySmall)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.swipe_entry_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.swipe_entry_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            TextButton(onClick = onWanted) { Text(stringResource(R.string.want_list_link, wantedCount)) }
        }
    }
}

@Composable
private fun FreeCard(book: FreeBook, added: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        FreeCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (added) Text("✓ " + stringResource(R.string.free_on_device), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FreeCover(book: FreeBook, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Surface(modifier.then(Modifier.height(180.dp)), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
        val author = book.authors.joinToString(", ")
        if (book.coverUrl == null) GeneratedCover(book.title, author, Modifier.fillMaxWidth())
        else SubcomposeAsyncImage(
            model = book.coverUrl, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth(),
            loading = { GeneratedCover(book.title, author, Modifier.fillMaxWidth()) },
            error = { GeneratedCover(book.title, author, Modifier.fillMaxWidth()) },
        )
    }
}

/** A free public-domain book: add its EPUB to the books on this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeSheet(book: FreeBook, adding: Boolean, added: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FreeCover(book, Modifier.width(110.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    Text(book.authors.joinToString(", "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    book.language?.let { Text(languageName(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    if (book.subjects.isNotEmpty()) Text(book.subjects.take(3).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text(stringResource(R.string.free_public_domain), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, enabled = !adding && !added, modifier = Modifier.fillMaxWidth()) {
                if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(if (added) R.string.free_on_device else if (adding) R.string.free_adding else R.string.free_add),
                    modifier = Modifier.padding(start = if (adding) 8.dp else 0.dp),
                )
            }
            Text(stringResource(R.string.gutenberg_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }
}
