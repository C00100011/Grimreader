package com.vdelaar.mylibby.ui.shelves

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.database.ShelfEntity
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.plural
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.data.FAVOURITES_SHELF
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.BookCover
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.library.BookListItem
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShelfSort(@StringRes val label: Int) {
    ADDED(R.string.sort_shelf_added),
    TITLE(R.string.sort_title_az),
    AUTHOR(R.string.sort_author_az),
}

data class ShelfUiState(
    val sort: ShelfSort = ShelfSort.ADDED,
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val message: String? = null,
)

/** One shelf's books, and what is being done with them. [shelfId] is [FAVOURITES_SHELF] for Favourites. */
class ShelfViewModel(private val c: AppContainer, val shelfId: Long) : ViewModel() {
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    val favourites get() = shelfId == FAVOURITES_SHELF

    /** The shelf; null for Favourites, and for a shelf that is gone (see [exists]). */
    val shelf: StateFlow<ShelfEntity?> =
        (if (favourites) flowOf(null) else c.shelves.observeShelf(shelfId)).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null until known; false once the shelf has been deleted (here or on the server). */
    val exists: StateFlow<Boolean?> =
        (if (favourites) flowOf(true) else c.shelves.observeShelf(shelfId).map { it != null }).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val books: StateFlow<List<Book>?> = combine(c.shelves.observeBooks(shelfId), _state.map { it.sort }) { list, sort ->
        when (sort) {
            ShelfSort.ADDED -> list
            ShelfSort.TITLE -> list.sortedBy { it.title.lowercase() }
            ShelfSort.AUTHOR -> list.sortedBy { it.authorLine.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favouriteIds = c.library.observeFavouriteIds().map { l -> l.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadedIds = c.downloads.observeAll().map { l -> l.filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun setSort(sort: ShelfSort) = _state.update { it.copy(sort = sort) }
    fun startSelecting() = _state.update { it.copy(selecting = true, selected = emptySet()) }
    fun clearSelection() = _state.update { it.copy(selecting = false, selected = emptySet()) }
    fun toggle(id: Long) = _state.update { it.copy(selecting = true, selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = _state.update { s -> s.copy(selected = books.value.orEmpty().map { it.id }.toSet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun toggleGrid() { viewModelScope.launch { c.settings.updateApp { it.copy(libraryGrid = !it.libraryGrid) } } }

    /** Takes the picked books off this shelf (off Favourites, for that one). */
    fun removeSelected(name: String) {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (favourites) c.library.setFavourites(ids, false) else c.shelves.remove(shelfId, ids)
            _state.update {
                it.copy(
                    selecting = false, selected = emptySet(),
                    message = if (favourites) plural(R.plurals.removed_from_favorites_n, ids.size, ids.size) else plural(R.plurals.removed_from_shelf_n, ids.size, ids.size, name),
                )
            }
        }
    }

    /** The other shelves' names, to say at once when a name is taken. */
    val siblingNames: StateFlow<List<String>> = c.shelves.observeCards()
        .map { l -> l.filter { !it.favourites && !it.shared && it.id != shelfId }.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Renames the shelf; the reason when the name is not allowed. */
    suspend fun rename(name: String): com.vdelaar.mylibby.data.ShelfNameError? =
        try { c.shelves.rename(shelfId, name); null } catch (e: com.vdelaar.mylibby.data.ShelfNameException) { e.error }

    fun delete() { viewModelScope.launch { c.shelves.delete(shelfId) } }
}

/** A shelf as a screen of its own (phones). */
@Composable
fun ShelfScreen(shelfId: Long, navigator: AppNavigator) {
    val vm = appViewModel(key = "shelf-$shelfId") { ShelfViewModel(it, shelfId) }
    val exists by vm.exists.collectAsStateWithLifecycle()
    // Deleted (here, or on another device): nothing left to show.
    LaunchedEffect(exists) { if (exists == false) navigator.back() }
    ShelfBooks(vm, navigator, onBack = navigator::back, onDeleted = navigator::back)
}

/** A shelf in the detail pane next to the list of shelves (tablets, unfolded foldables). */
@Composable
fun ShelfPane(shelfId: Long, navigator: AppNavigator, onGone: () -> Unit) {
    val vm = appViewModel(key = "shelf-$shelfId") { ShelfViewModel(it, shelfId) }
    val exists by vm.exists.collectAsStateWithLifecycle()
    LaunchedEffect(exists) { if (exists == false) onGone() }
    ShelfBooks(vm, navigator, onBack = null, onDeleted = onGone)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBooks(vm: ShelfViewModel, navigator: AppNavigator, onBack: (() -> Unit)?, onDeleted: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val favIds by vm.favouriteIds.collectAsStateWithLifecycle()
    val dlIds by vm.downloadedIds.collectAsStateWithLifecycle()
    val grid = appContainer().settings.app.collectAsStateWithLifecycle().value.libraryGrid
    val snackbar = remember { SnackbarHostState() }
    var addTo by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val name = if (vm.favourites) stringResource(R.string.favorites) else shelf?.name.orEmpty()
    val editable = !vm.favourites && shelf?.shared == false

    BackHandler(enabled = state.selecting) { vm.clearSelection() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp).padding(top = 8.dp)) {
                if (state.selecting) {
                    SelectionPill(
                        count = state.selected.size,
                        removeLabel = stringResource(if (vm.favourites) R.string.remove_from_favorites else R.string.remove_from_shelf),
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                        onAddToShelf = { addTo = true },
                        onRemove = { vm.removeSelected(name) },
                    )
                } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Four actions leave a long shelf name no room on a narrow screen: they go on a row of their own.
                    val stacked = maxWidth < 560.dp
                    val title: @Composable (Modifier) -> Unit = { m ->
                        Column(m.padding(start = if (onBack == null) 12.dp else 0.dp)) {
                            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            books?.let {
                                Text(
                                    pluralStringResource(R.plurals.books_count, it.size, it.size) + if (shelf?.shared == true) " · " + stringResource(R.string.shelf_shared) else "",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val actions: @Composable () -> Unit = {
                        IconButton(onClick = vm::startSelecting, enabled = books.orEmpty().isNotEmpty()) { Icon(Icons.Rounded.Checklist, stringResource(R.string.select_books)) }
                        SortButton(state.sort, vm::setSort)
                        IconButton(onClick = vm::toggleGrid) {
                            Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, stringResource(if (grid) R.string.show_as_list else R.string.show_as_grid))
                        }
                        if (editable) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.shelf_options)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.shelf_rename)) }, onClick = { menu = false; renaming = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.shelf_delete)) }, onClick = { menu = false; deleting = true })
                                }
                            }
                        }
                    }
                    val back: @Composable () -> Unit = {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                    }
                    if (stacked) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) { back(); title(Modifier.weight(1f)) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) { back(); title(Modifier.weight(1f)); actions() }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = books
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> EmptyState(
                        if (vm.favourites) "💛" else "📖",
                        stringResource(R.string.shelf_empty_title),
                        stringResource(if (vm.favourites) R.string.shelf_empty_favorites_body else R.string.shelf_empty_body),
                        Modifier.align(Alignment.Center),
                    )
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(108.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.id }) { book ->
                            Column(
                                Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(
                                    onClickLabel = stringResource(if (state.selecting) R.string.select_book_label else R.string.open_book_label, book.title),
                                    onLongClickLabel = stringResource(R.string.select),
                                    onLongClick = { vm.toggle(book.id) },
                                    onClick = { if (state.selecting) vm.toggle(book.id) else navigator.openBook(book.id) },
                                )
                            ) {
                                BookCover(
                                    book, Modifier.fillMaxWidth(), showProgress = true, downloaded = book.id in dlIds, favourite = book.id in favIds,
                                    selected = if (state.selecting) book.id in state.selected else null,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { book ->
                            BookListItem(
                                book, book.id in favIds, book.id in dlIds,
                                checked = if (state.selecting) book.id in state.selected else null,
                                onLongClick = { vm.toggle(book.id) },
                            ) { if (state.selecting) vm.toggle(book.id) else navigator.openBook(book.id) }
                        }
                    }
                }
            }
        }
    }

    if (addTo) AddToShelvesSheet(state.selected, onDismiss = { addTo = false; vm.clearSelection() })
    if (renaming) {
        val siblings by vm.siblingNames.collectAsStateWithLifecycle()
        ShelfNameDialog(
            title = stringResource(R.string.shelf_rename),
            confirm = stringResource(R.string.shelf_rename_action),
            initial = shelf?.name.orEmpty(),
            existing = siblings,
            onDismiss = { renaming = false },
            onConfirm = vm::rename,
        )
    }
    if (deleting) DeleteShelfDialog(name, shelf?.remoteId != null, onDismiss = { deleting = false }, onDelete = { vm.delete(); onDeleted() })
}

/** Contextual bar while picking books (like the one in Library). */
@Composable
private fun SelectionPill(count: Int, removeLabel: String, onClose: () -> Unit, onSelectAll: () -> Unit, onAddToShelf: () -> Unit, onRemove: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.stop_selecting)) }
            Text(
                if (count == 0) stringResource(R.string.select_books) else pluralStringResource(R.plurals.books_selected, count, count),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
            if (count > 0) {
                IconButton(onClick = onAddToShelf) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_shelf)) }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.RemoveCircleOutline, removeLabel) }
            }
        }
    }
}

@Composable
private fun SortButton(sort: ShelfSort, onChange: (ShelfSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShelfSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(s.label)) },
                    onClick = { onChange(s); open = false },
                    trailingIcon = { if (s == sort) Text("✓", color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}
