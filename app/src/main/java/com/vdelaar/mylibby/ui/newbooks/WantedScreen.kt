package com.vdelaar.mylibby.ui.newbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.database.WantedEntity
import com.vdelaar.mylibby.data.BookIdea
import com.vdelaar.mylibby.data.WantList
import com.vdelaar.mylibby.data.libraryMatch
import com.vdelaar.mylibby.data.toIdea
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.navigation.AppNavigator

/** The books you want: to read (they can be requested through your book search) and to buy later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WantedScreen(navigator: AppNavigator) {
    val c = appContainer()
    val all by c.swipe.wanted.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableStateOf(WantList.READ) }
    var open by remember { mutableStateOf<BookIdea?>(null) }
    val library by produceState(emptyList<com.vdelaar.mylibby.core.model.Book>(), all.size) { value = runCatching { c.library.libraryBooks() }.getOrDefault(emptyList()) }
    val shown = all.filter { it.list == tab.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.want_title)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                WantList.entries.forEach { list ->
                    val n = all.count { it.list == list.name }
                    Tab(
                        selected = tab == list, onClick = { tab = list },
                        text = { Text(stringResource(if (list == WantList.READ) R.string.want_tab_read else R.string.want_tab_buy) + " ($n)") },
                    )
                }
            }
            if (shown.isEmpty()) {
                EmptyState(
                    if (tab == WantList.READ) "♥" else "🛒",
                    stringResource(if (tab == WantList.READ) R.string.want_empty_read else R.string.want_empty_buy),
                    stringResource(if (tab == WantList.READ) R.string.want_empty_read_body else R.string.want_empty_buy_body),
                    action = stringResource(R.string.want_to_swipe), onAction = navigator::swipe,
                )
            } else LazyColumn {
                items(shown, key = { it.key }) { w -> WantedRow(w, mine = libraryMatch(w.title, w.authors.split('|'), library) != null) { open = w.toIdea() } }
            }
        }
    }

    open?.let { idea ->
        IdeaSheet(idea, onOpenLibrary = { open = null; navigator.openBook(it.id) }, onDismiss = { open = null }, onRequested = navigator::back)
    }
}

@Composable
private fun WantedRow(w: WantedEntity, mine: Boolean, onClick: () -> Unit) {
    val idea = w.toIdea()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        IdeaCover(idea, Modifier.width(56.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(idea.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(idea.authorLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(if (w.love) "♥" else null, idea.year?.toString(), if (mine) stringResource(R.string.idea_in_library) else null).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
