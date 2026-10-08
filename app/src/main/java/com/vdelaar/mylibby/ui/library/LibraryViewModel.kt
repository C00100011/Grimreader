package com.vdelaar.mylibby.ui.library

import androidx.lifecycle.ViewModel
import com.vdelaar.mylibby.core.plural
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.FilterOptions
import com.vdelaar.mylibby.core.model.LibraryFilter
import com.vdelaar.mylibby.core.model.SeriesInfo
import com.vdelaar.mylibby.core.network.AuthorSummaryDto
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab(@androidx.annotation.StringRes val label: Int) { BOOKS(R.string.tab_lib_books), SERIES(R.string.tab_lib_series), AUTHORS(R.string.tab_lib_authors) }

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.BOOKS,
    val filter: LibraryFilter = LibraryFilter(),
    val books: List<Book> = emptyList(),
    val total: Long = 0,
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val options: FilterOptions = FilterOptions(),
    val series: List<SeriesInfo> = emptyList(),
    val authors: List<AuthorSummaryDto> = emptyList(),
    /** Series/Authors tabs: loading and error state are tracked apart from the (possibly empty) result,
     *  so "no results" is never mistaken for "still loading". `*For` = the search text the list belongs to. */
    val seriesLoading: Boolean = false,
    val seriesError: String? = null,
    val seriesFor: String? = null,
    val authorsLoading: Boolean = false,
    val authorsError: String? = null,
    val authorsFor: String? = null,
    val grid: Boolean = true,
    val selectedBookId: Long? = null,
    /** Books picked in multi-select mode (empty = not selecting). */
    val multiSelect: Set<Long> = emptySet(),
    val selecting: Boolean = false,
    val message: String? = null,
    /** Pull-to-refresh is running. */
    val refreshing: Boolean = false,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        LibraryUiState(
            grid = c.settings.app.value.libraryGrid,
            filter = LibraryFilter(sort = c.settings.app.value.librarySort, dir = c.settings.app.value.librarySortDir),
        )
    )
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    val favouriteIds = c.library.observeFavouriteIds().map { l -> l.map { it.bookId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadedIds = c.downloads.observeAll().map { l -> l.filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var seriesJob: Job? = null
    private var authorsJob: Job? = null

    init {
        reload()
        viewModelScope.launch { runCatching { c.library.filterOptions() }.onSuccess { o -> _state.update { it.copy(options = o) } } }
    }

    fun setTab(tab: LibraryTab) {
        _state.update { it.copy(tab = tab) }
        // (Re)load when the tab has never loaded or its list belongs to an older search text.
        val search = _state.value.filter.search
        when (tab) {
            LibraryTab.SERIES -> if (_state.value.seriesFor != search) loadSeries()
            LibraryTab.AUTHORS -> if (_state.value.authorsFor != search) loadAuthors()
            LibraryTab.BOOKS -> Unit
        }
    }

    fun onSearch(q: String) {
        _state.update { it.copy(filter = it.filter.copy(search = q)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            when (_state.value.tab) {
                LibraryTab.BOOKS -> reload()
                LibraryTab.SERIES -> loadSeries()
                LibraryTab.AUTHORS -> loadAuthors()
            }
        }
    }

    fun setFilter(f: LibraryFilter) {
        _state.update { it.copy(filter = f) }
        viewModelScope.launch { c.settings.updateApp { it.copy(librarySort = f.sort, librarySortDir = f.dir) } }
        reload()
    }

    fun clearFilters() = setFilter(LibraryFilter(search = _state.value.filter.search, sort = _state.value.filter.sort, dir = _state.value.filter.dir))

    fun toggleGrid() {
        val g = !_state.value.grid
        _state.update { it.copy(grid = g) }
        viewModelScope.launch { c.settings.updateApp { it.copy(libraryGrid = g) } }
    }

    fun select(id: Long?) = _state.update { it.copy(selectedBookId = id) }

    // Multi-select ---------------------------------------------------------------------------

    fun toggleSelect(id: Long) = _state.update {
        it.copy(selecting = true, multiSelect = if (id in it.multiSelect) it.multiSelect - id else it.multiSelect + id)
    }

    fun startSelecting() = _state.update { it.copy(selecting = true, multiSelect = emptySet()) }

    fun selectAll() = _state.update { s -> s.copy(multiSelect = s.books.map { it.id }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selecting = false, multiSelect = emptySet()) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun favouriteSelected(favourite: Boolean) {
        val ids = _state.value.multiSelect.filter { it > 0 }.toSet() // local books aren't in Grimmory
        viewModelScope.launch {
            c.library.setFavourites(ids, favourite)
            _state.update {
                it.copy(
                    selecting = false,
                    multiSelect = emptySet(),
                    message = if (favourite) plural(R.plurals.added_to_favorites_n, ids.size, ids.size) else plural(R.plurals.removed_from_favorites_n, ids.size, ids.size),
                )
            }
        }
    }

    fun downloadSelected() {
        val s = _state.value
        val books = s.books.filter { it.id in s.multiSelect && it.isReadable && it.id !in downloadedIds.value }
        viewModelScope.launch {
            books.forEach { c.downloads.enqueue(it) }
            _state.update { it.copy(selecting = false, multiSelect = emptySet(), message = plural(R.plurals.downloading_n, books.size, books.size)) }
        }
    }

    fun removeSelectedDownloads() {
        val ids = _state.value.multiSelect.filter { it in downloadedIds.value }
        viewModelScope.launch {
            ids.forEach { c.downloads.remove(it) }
            _state.update { it.copy(selecting = false, multiSelect = emptySet(), message = str(R.string.removed_from_device_n, ids.size)) }
        }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadBooks() }
    }

    private suspend fun loadBooks() {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { c.library.books(_state.value.filter, 0) }
            .onSuccess { p -> _state.update { it.copy(loading = false, books = p.books, page = 0, hasNext = p.hasNext, total = p.total, offline = p.offline) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: str(R.string.library_load_error)) }
            }
    }

    /**
     * Pull-to-refresh: bring the server in step first (pending changes out, favourites in; nothing without a server),
     * then reload what the current tab shows.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                if (!c.settings.app.value.noServer && c.tokens.accessToken != null) {
                    runCatching { com.vdelaar.mylibby.data.pushPendingChanges(c) }
                    runCatching { c.library.refreshFavourites() }
                    c.librarySync.launchFull(forced = false) // book list and covers carry on in the background
                }
                when (_state.value.tab) {
                    LibraryTab.BOOKS -> { loadJob?.cancel(); loadBooks() }
                    LibraryTab.SERIES -> { seriesJob?.cancel(); fetchSeries() }
                    LibraryTab.AUTHORS -> { authorsJob?.cancel(); fetchAuthors() }
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasNext || s.loadingMore || s.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching { c.library.books(s.filter, s.page + 1) }
                .onSuccess { p -> _state.update { it.copy(loadingMore = false, books = (it.books + p.books).distinctBy { b -> b.id }, page = s.page + 1, hasNext = p.hasNext) } }
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }

    fun loadSeries() {
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch { fetchSeries() }
    }

    private suspend fun fetchSeries() {
        val search = _state.value.filter.search
        _state.update { it.copy(seriesLoading = true, seriesError = null) }
        runCatching { c.library.series(search) }
            .onSuccess { list -> _state.update { it.copy(series = list, seriesLoading = false, seriesFor = search) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(seriesLoading = false, seriesError = e.message ?: str(R.string.series_load_error)) }
            }
    }

    fun loadAuthors() {
        authorsJob?.cancel()
        authorsJob = viewModelScope.launch { fetchAuthors() }
    }

    private suspend fun fetchAuthors() {
        val search = _state.value.filter.search
        _state.update { it.copy(authorsLoading = true, authorsError = null) }
        runCatching { c.library.authors(search) }
            .onSuccess { list -> _state.update { it.copy(authors = list, authorsLoading = false, authorsFor = search) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(authorsLoading = false, authorsError = e.message ?: str(R.string.authors_load_error)) }
            }
    }
}
