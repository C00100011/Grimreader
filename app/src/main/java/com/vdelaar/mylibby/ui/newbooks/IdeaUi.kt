package com.vdelaar.mylibby.ui.newbooks

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.BookSearchSource
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.data.BookIdea
import com.vdelaar.mylibby.data.WantList
import com.vdelaar.mylibby.data.languageName
import com.vdelaar.mylibby.ui.UiRequests
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.components.GeneratedCover
import kotlinx.coroutines.launch

/** "science fiction" -> "Science Fiction". */
fun genreTitle(genre: String): String = genre.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** The cover of a book idea, from Open Library; a generated one while loading or when there is none. */
@Composable
fun IdeaCover(idea: BookIdea, modifier: Modifier = Modifier, size: Char = 'M') {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Surface(modifier.aspectRatio(2f / 3f), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
        val url = idea.coverUrl(size)
        if (url == null) GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxWidth())
        else SubcomposeAsyncImage(
            model = url,
            contentDescription = idea.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(),
            loading = { GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxWidth()) },
            error = { GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxWidth()) },
        )
    }
}

@Composable
fun IdeaCard(idea: BookIdea, wanted: WantList?, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        IdeaCover(idea, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(idea.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(idea.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(
            when (wanted) { WantList.READ -> "♥ " + stringResource(R.string.want_tab_read); WantList.BUY -> "🛒 " + stringResource(R.string.want_tab_buy); null -> null },
            idea.year?.toString(),
        ).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/**
 * What you can do with a book idea: save it to a want list, look it up with your book search, open it in
 * your library when you have it, or read about it on Open Library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeaSheet(idea: BookIdea, onOpenLibrary: (Book) -> Unit, onDismiss: () -> Unit, onRequested: () -> Unit = {}) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    val wanted by c.swipe.wanted.collectAsStateWithLifecycle(emptyList())
    val saved = wanted.firstOrNull { it.key == idea.key }
    var description by remember(idea.key) { mutableStateOf<String?>(null) }
    var mine by remember(idea.key) { mutableStateOf<Book?>(null) }
    LaunchedEffect(idea.key) {
        mine = runCatching { c.swipe.libraryBook(idea) }.getOrNull()
        description = c.discover.description(idea.key)
    }
    val canRequest = app.effectiveBookSearch == BookSearchSource.SHELFMARK && mine == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IdeaCover(idea, Modifier.width(110.dp), size = 'L')
                Column(Modifier.weight(1f)) {
                    Text(idea.title, style = MaterialTheme.typography.titleLarge)
                    Text(idea.authorLine, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    val facts = listOfNotNull(idea.year?.toString(), idea.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { languageName(it) })
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    if (idea.subjects.isNotEmpty()) Text(idea.subjects.take(4).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(16.dp))

            mine?.let { b ->
                Text(stringResource(R.string.idea_in_library), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { onOpenLibrary(b) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.idea_open_library)) }
            }
            if (mine == null) {
                when (saved?.list) {
                    null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { c.swipe.want(idea, WantList.READ) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.want_tab_read)) }
                        OutlinedButton(onClick = { scope.launch { c.swipe.want(idea, WantList.BUY) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.want_tab_buy)) }
                    }
                    WantList.READ.name -> {
                        Text("♥ " + stringResource(R.string.idea_on_read), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = { scope.launch { c.swipe.move(idea.key, WantList.BUY) } }) { Text(stringResource(R.string.idea_move_buy)) }
                            TextButton(onClick = { scope.launch { c.swipe.remove(idea.key) } }) { Text(stringResource(R.string.idea_remove)) }
                        }
                    }
                    else -> {
                        Text("🛒 " + stringResource(R.string.idea_on_buy), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = { scope.launch { c.swipe.move(idea.key, WantList.READ) } }) { Text(stringResource(R.string.idea_move_read)) }
                            TextButton(onClick = { scope.launch { c.swipe.remove(idea.key) } }) { Text(stringResource(R.string.idea_got_it)) }
                        }
                    }
                }
            }
            if (canRequest) {
                Button(
                    onClick = {
                        UiRequests.discoverQuery.value = listOf(idea.title, idea.authors.firstOrNull()).filterNotNull().joinToString(" ")
                        onDismiss()
                        onRequested() // from a screen above the tabs this goes back to them, where Discover picks the request up
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.idea_request)) }
            }
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(idea.pageUrl))) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.idea_view_on)) }
            Text(stringResource(R.string.ol_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }
}
