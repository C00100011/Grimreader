package com.vdelaar.mylibby.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.PhoneAndroid
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.ReadStatus
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.bookdetail.BookDetailContent
import com.vdelaar.mylibby.ui.components.BookCover
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.components.ErrorState
import com.vdelaar.mylibby.ui.components.OfflineBanner
import com.vdelaar.mylibby.ui.navigation.AppNavigator

@Composable
fun LibraryScreen(navigator: AppNavigator) {
    val vm = appViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val layout = rememberDeviceLayout()

    if (layout.useListDetail) {
        // Foldables and tablets: list-detail, split at the hinge on book-posture foldables.
        BackHandler(enabled = state.selectedBookId != null) { vm.select(null) }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (layout.hasVerticalHinge) 1f else 0.48f).fillMaxHeight()) {
                LibraryList(vm, navigator, compactColumns = true, onBook = { vm.select(it.id) })
            }
            VerticalDivider()
            Box(Modifier.weight(if (layout.hasVerticalHinge) 1f else 0.52f).fillMaxHeight()) {
                val id = state.selectedBookId?.takeIf { sel -> state.books.any { it.id == sel } } ?: state.books.firstOrNull()?.id
                if (id != null) {
                    androidx.compose.runtime.key(id) { BookDetailContent(id, navigator, onBack = null, inPane = true) }
                } else {
                    EmptyState("📚", stringResource(R.string.select_a_book), stringResource(R.string.select_a_book_body), Modifier.align(Alignment.Center))
                }
            }
        }
    } else {
        LibraryList(vm, navigator, compactColumns = false, onBook = { navigator.openBook(it.id) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryList(vm: LibraryViewModel, navigator: AppNavigator, compactColumns: Boolean, onBook: (Book) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favIds by vm.favouriteIds.collectAsStateWithLifecycle()
    val dlIds by vm.downloadedIds.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val selecting = state.selecting
    BackHandler(enabled = selecting) { vm.clearSelection() }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp)) {
            if (selecting) {
                SelectionBar(
                    count = state.multiSelect.size,
                    enabled = state.multiSelect.isNotEmpty(),
                    allFavourite = state.multiSelect.all { it in favIds },
                    anyNotDownloaded = state.multiSelect.any { it !in dlIds },
                    anyDownloaded = state.multiSelect.any { it in dlIds },
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onFavourite = vm::favouriteSelected,
                    onDownload = vm::downloadSelected,
                    onRemoveDownloads = vm::removeSelectedDownloads,
                )
            } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
              // In a narrow list pane the five actions leave no room for the title: put them on a row below it.
              val stacked = compactColumns && maxWidth < 420.dp
              val title: @Composable (Modifier) -> Unit = { m ->
                  Text(stringResource(R.string.tab_books), style = MaterialTheme.typography.headlineMedium, modifier = m.padding(start = 4.dp))
              }
              val actions: @Composable () -> Unit = {
                if (state.tab == LibraryTab.BOOKS) {
                    com.vdelaar.mylibby.ui.components.ImportBookButton(onImported = { vm.reload() })
                    IconButton(onClick = vm::startSelecting) { Icon(Icons.Rounded.Checklist, stringResource(R.string.select_books)) }
                    SortMenu(state.filter.sort, state.filter.dir) { s, d -> vm.setFilter(state.filter.copy(sort = s, dir = d)) }
                    IconButton(onClick = vm::toggleGrid) {
                        Icon(if (state.grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, stringResource(if (state.grid) R.string.show_as_list else R.string.show_as_grid))
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (state.filter.activeCount > 0) Badge { Text("${state.filter.activeCount}") } }) {
                            Icon(Icons.Rounded.Tune, stringResource(R.string.filters))
                        }
                    }
                }
              }
              if (stacked) {
                  Column(Modifier.fillMaxWidth()) {
                      title(Modifier)
                      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
                  }
              } else {
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      title(Modifier.weight(1f))
                      actions()
                  }
              }
            }
            if (appContainer().settings.app.collectAsStateWithLifecycle().value.opdsActive) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.FilledTonalButton(onClick = { navigator.opds() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Public, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.opds_browse))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = state.filter.search,
                onValueChange = vm::onSearch,
                placeholder = { Text(stringResource(R.string.search_library)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (state.filter.search.isNotEmpty()) IconButton(onClick = { vm.onSearch("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear_search)) }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
            LibraryTab.entries.forEach { t ->
                Tab(selected = state.tab == t, onClick = { vm.setTab(t) }, text = { Text(stringResource(t.label)) })
            }
        }
        // Pull down on any of the three lists to refresh (and sync, when a server is connected).
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize()) {
                when (state.tab) {
                    LibraryTab.BOOKS -> {
                        QuickFilters(state, vm)
                        if (state.offline) OfflineBanner()
                        BooksContent(state, vm, favIds, dlIds, compactColumns, onBook)
                    }
                    LibraryTab.SERIES -> SeriesList(state, navigator, onRetry = vm::loadSeries)
                    LibraryTab.AUTHORS -> AuthorList(state, navigator, onRetry = vm::loadAuthors)
                }
            }
        }
    }

    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showFilters) {
        FilterSheet(state.filter, state.options, onApply = { vm.setFilter(it) }, onClear = vm::clearFilters, onDismiss = { showFilters = false })
    }
}

@Composable
private fun QuickFilters(state: LibraryUiState, vm: LibraryViewModel) {
    val f = state.filter
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = f.favouritesOnly,
                onClick = { vm.setFilter(f.copy(favouritesOnly = !f.favouritesOnly)) },
                label = { Text(stringResource(R.string.favorites)) },
                leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        item {
            FilterChip(
                selected = f.downloadedOnly,
                onClick = { vm.setFilter(f.copy(downloadedOnly = !f.downloadedOnly)) },
                label = { Text(stringResource(R.string.on_device)) },
                leadingIcon = { Icon(Icons.Rounded.DownloadDone, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        item {
            FilterChip(
                selected = f.localOnly,
                onClick = { vm.setFilter(f.copy(localOnly = !f.localOnly)) },
                label = { Text(stringResource(R.string.local_filter)) },
                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        item {
            val reading = "READING" in f.statuses
            FilterChip(selected = reading, onClick = {
                vm.setFilter(f.copy(statuses = if (reading) f.statuses - "READING" else f.statuses + "READING"))
            }, label = { Text(stringResource(R.string.status_reading)) })
        }
        item {
            val unread = "UNREAD" in f.statuses
            FilterChip(selected = unread, onClick = {
                vm.setFilter(f.copy(statuses = if (unread) f.statuses - "UNREAD" else f.statuses + "UNREAD"))
            }, label = { Text(stringResource(R.string.status_unread)) })
        }
        // Active advanced filters shown as removable chips
        (f.genres.map { "genre" to it } + f.authors.map { "author" to it } + f.series.map { "series" to it } + f.languages.map { "lang" to it }).forEach { (kind, value) ->
            item(key = "$kind:$value") {
                FilterChip(
                    selected = true,
                    onClick = {
                        vm.setFilter(
                            when (kind) {
                                "genre" -> f.copy(genres = f.genres - value)
                                "author" -> f.copy(authors = f.authors - value)
                                "series" -> f.copy(series = f.series - value)
                                else -> f.copy(languages = f.languages - value)
                            }
                        )
                    },
                    label = { Text(value, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.remove_filter), Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
    }
}

@Composable
private fun BooksContent(
    state: LibraryUiState,
    vm: LibraryViewModel,
    favIds: Set<Long>,
    dlIds: Set<Long>,
    compactColumns: Boolean,
    onBook: (Book) -> Unit,
) {
    val appState by appContainer().settings.app.collectAsStateWithLifecycle()
    val localOnly = appState.localOnly
    when {
        state.loading && state.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && state.books.isEmpty() -> ErrorState(state.error, onRetry = vm::reload)
        state.books.isEmpty() -> EmptyState(
            "🔎", stringResource(R.string.no_books_found),
            stringResource(if (state.filter.activeCount > 0 || state.filter.search.isNotBlank()) R.string.no_books_filtered else if (localOnly) R.string.library_empty_local else R.string.library_empty),
            action = if (state.filter.activeCount > 0) stringResource(R.string.clear_filters) else null,
            onAction = vm::clearFilters,
        )
        state.grid -> {
            val gridState = rememberLazyGridState()
            val nearEnd by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
            LaunchedEffect(nearEnd, state.books.size) { if (nearEnd >= state.books.size - 8) vm.loadMore() }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(if (compactColumns) 104.dp else 108.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(pluralStringResource(R.plurals.books_count, state.total.toInt(), state.total.toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.books, key = { it.id }) { book ->
                    val selecting = state.selecting
                    val selected = !selecting && state.selectedBookId == book.id
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(
                                onClickLabel = stringResource(if (selecting) R.string.select_book_label else R.string.open_book_label, book.title),
                                onLongClickLabel = stringResource(R.string.select),
                                onLongClick = { vm.toggleSelect(book.id) },
                                onClick = { if (selecting) vm.toggleSelect(book.id) else onBook(book) },
                            )
                            .padding(if (selected) 6.dp else 0.dp)
                    ) {
                        BookCover(
                            book, Modifier.fillMaxWidth(), showProgress = true, downloaded = book.id in dlIds, favourite = book.id in favIds,
                            selected = if (selecting) book.id in state.multiSelect else null,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }
        }
        else -> {
            val listState = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
            LaunchedEffect(nearEnd, state.books.size) { if (nearEnd >= state.books.size - 6) vm.loadMore() }
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                items(state.books, key = { it.id }) { book ->
                    val selecting = state.selecting
                    BookListItem(
                        book, book.id in favIds, book.id in dlIds,
                        selected = !selecting && state.selectedBookId == book.id,
                        checked = if (selecting) book.id in state.multiSelect else null,
                        onLongClick = { vm.toggleSelect(book.id) },
                    ) { if (selecting) vm.toggleSelect(book.id) else onBook(book) }
                }
                if (state.loadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }
        }
    }
}

@Composable
fun BookListItem(
    book: Book,
    favourite: Boolean,
    downloaded: Boolean?,
    selected: Boolean = false,
    checked: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected || checked == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) {
                androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onClick() })
                Spacer(Modifier.width(8.dp))
            }
            BookCover(book, Modifier.width(56.dp), elevation = 3.dp, downloaded = downloaded, favourite = favourite)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.authorLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                book.seriesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                val p = book.progress ?: 0f
                if (p > 0f) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.weight(1f).height(4.dp), drawStopIndicator = {})
                        Spacer(Modifier.width(8.dp))
                        Text("${p.toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (book.readStatus != null && book.readStatus != "UNSET") {
                    Text(stringResource(ReadStatus.labelRes(book.readStatus)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Contextual top bar shown while selecting books (Material contextual action bar). */
@Composable
private fun SelectionBar(
    count: Int,
    enabled: Boolean,
    allFavourite: Boolean,
    anyNotDownloaded: Boolean,
    anyDownloaded: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavourite: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onRemoveDownloads: () -> Unit,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.stop_selecting)) }
            Text(if (count == 0) stringResource(R.string.select_books) else pluralStringResource(R.plurals.books_selected, count, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
            if (!enabled) return@Row
            IconButton(onClick = { onFavourite(!allFavourite) }) {
                Icon(if (allFavourite) Icons.Rounded.HeartBroken else Icons.Rounded.Favorite, stringResource(if (allFavourite) R.string.remove_from_favorites else R.string.add_to_favorites))
            }
            if (anyNotDownloaded) IconButton(onClick = onDownload) { Icon(Icons.Rounded.CloudDownload, stringResource(R.string.download_selected)) }
            if (anyDownloaded) IconButton(onClick = onRemoveDownloads) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.remove_downloads)) }
        }
    }
}

@Composable
private fun SortMenu(sort: String, dir: String, onChange: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        Triple("addedOn", "desc", R.string.sort_recently_added),
        Triple("lastReadTime", "desc", R.string.sort_recently_read),
        Triple("title", "asc", R.string.sort_title_az),
        Triple("title", "desc", R.string.sort_title_za),
        Triple("seriesName", "asc", R.string.sort_series),
        Triple("publishedDate", "desc", R.string.sort_newest),
        Triple("personalRating", "desc", R.string.sort_rating),
    )
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (s, d, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = { onChange(s, d); open = false },
                    trailingIcon = { if (s == sort && d == dir) Text("✓", color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

/** What the Series / Authors tabs show instead of a list: spinner only while loading, otherwise error or "no results". */
@Composable
private fun ListPlaceholder(
    loading: Boolean,
    error: String?,
    search: String,
    @androidx.annotation.StringRes emptyTitle: Int,
    @androidx.annotation.StringRes emptyBody: Int,
    onRetry: () -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorState(error, onRetry = onRetry)
        else -> EmptyState(
            "🔎", stringResource(emptyTitle),
            if (search.isNotBlank()) stringResource(R.string.no_match_for, search.trim()) else stringResource(emptyBody),
        )
    }
}

@Composable
private fun SeriesList(state: LibraryUiState, navigator: AppNavigator, onRetry: () -> Unit) {
    val api = appContainer().api
    if (state.series.isEmpty()) {
        ListPlaceholder(
            loading = state.seriesLoading || state.seriesFor == null && state.seriesError == null,
            error = state.seriesError,
            search = state.filter.search,
            emptyTitle = R.string.no_series_found,
            emptyBody = R.string.series_empty,
            onRetry = onRetry,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.series, key = { it.name }) { s ->
            Row(
                Modifier.fillMaxWidth().clickable { navigator.series(s.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(84.dp).height(76.dp)) {
                    s.coverBookIds.take(3).reversed().forEachIndexed { i, id ->
                        AsyncImage(
                            model = api.coverUrl(id, thumbnail = true),
                            contentDescription = null,
                            modifier = Modifier.padding(start = (i * 14).dp, top = ((2 - i) * 2).dp).width(50.dp).height(72.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        pluralStringResource(R.plurals.series_line, s.bookCount, "${s.bookCount}${s.total?.let { "/$it" } ?: ""}", s.booksRead),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = 112.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
}

@Composable
private fun AuthorList(state: LibraryUiState, navigator: AppNavigator, onRetry: () -> Unit) {
    if (state.authors.isEmpty()) {
        ListPlaceholder(
            loading = state.authorsLoading || state.authorsFor == null && state.authorsError == null,
            error = state.authorsError,
            search = state.filter.search,
            emptyTitle = R.string.no_authors_found,
            emptyBody = R.string.authors_empty,
            onRetry = onRetry,
        )
        return
    }
    val api = appContainer().api
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.authors, key = { it.id }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { navigator.author(a.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                    if (a.hasPhoto) {
                        AsyncImage(
                            model = "${api.grimmoryBaseUrl}api/v1/media/author/${a.id}/thumbnail",
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(a.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.books_count, a.bookCount, a.bookCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
