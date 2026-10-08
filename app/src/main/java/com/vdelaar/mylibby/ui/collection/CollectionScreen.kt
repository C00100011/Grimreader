package com.vdelaar.mylibby.ui.collection

import androidx.compose.foundation.layout.Box
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.ErrorState
import com.vdelaar.mylibby.ui.library.BookListItem
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(private val c: AppContainer, private val kind: String, private val name: String) : ViewModel() {
    val books = MutableStateFlow<List<Book>?>(null)
    val error = MutableStateFlow<String?>(null)
    val favIds = c.library.observeFavouriteIds().map { l -> l.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val dlIds = c.downloads.observeAll().map { l -> l.filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init { load() }

    fun load() {
        viewModelScope.launch {
            error.value = null
            runCatching { if (kind == "series") c.library.seriesBooks(name) else c.library.authorBooks(name) }
                .onSuccess { books.value = it }
                .onFailure { error.value = it.message ?: str(R.string.collection_error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(kind: String, name: String, navigator: AppNavigator) {
    val vm = appViewModel(key = "$kind:$name") { CollectionViewModel(it, kind, name) }
    val books by vm.books.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val favs by vm.favIds.collectAsStateWithLifecycle()
    val dls by vm.dlIds.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        books?.let { Text(pluralStringResource(R.plurals.books_count, it.size, it.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val list = books
        when {
            error != null && list == null -> ErrorState(error!!, vm::load, Modifier.padding(padding))
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16)) {
                items(list, key = { it.id }) { b -> BookListItem(b, b.id in favs, b.id in dls) { navigator.openBook(b.id) } }
            }
        }
    }
}

private operator fun androidx.compose.ui.unit.Dp.plus(other: Int) = this + androidx.compose.ui.unit.Dp(other.toFloat())
