package com.vdelaar.mylibby.ui.suggestions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.data.Suggestions
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.library.BookListItem
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SuggestionsViewModel(private val c: AppContainer) : ViewModel() {
    val suggestions = MutableStateFlow<Suggestions?>(null)
    val favIds = c.library.observeFavouriteIds().map { l -> l.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val dlIds = c.downloads.observeAll().map { l -> l.filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch { suggestions.value = runCatching { c.library.suggestions(40) }.getOrDefault(Suggestions(emptyList(), emptyList())) }
    }
}

/** "Your next read": unread books picked from the genres you read, favourite and rate highest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(navigator: AppNavigator) {
    val vm = appViewModel { SuggestionsViewModel(it) }
    val result by vm.suggestions.collectAsStateWithLifecycle()
    val favs by vm.favIds.collectAsStateWithLifecycle()
    val dls by vm.dlIds.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.next_reads)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.books.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("🔮", stringResource(R.string.next_read_empty), stringResource(R.string.next_read_empty_body))
            }
            else -> LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
                item {
                    Text(
                        stringResource(R.string.next_read_because, r.genres.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(r.books, key = { it.id }) { b -> BookListItem(b, b.id in favs, b.id in dls) { navigator.openBook(b.id) } }
            }
        }
    }
}
