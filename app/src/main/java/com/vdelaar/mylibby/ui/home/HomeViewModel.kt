package com.vdelaar.mylibby.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.toBook
import com.vdelaar.mylibby.data.ReadingSpeed
import com.vdelaar.mylibby.data.StreakInfo
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val continueReading: List<Book> = emptyList(),
    val recentlyAdded: List<Book> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val demo: StateFlow<Boolean> = c.settings.app.map { it.demo }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.demo)

    val localOnly: StateFlow<Boolean> = c.settings.app.map { it.localOnly }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.localOnly)

    /** Leave the demo and go to sign-in. */
    fun connectServer(onDone: () -> Unit) = viewModelScope.launch {
        c.demo.exit()
        onDone()
    }

    val streak: StateFlow<StreakInfo> = c.stats.streak.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo())
    val speed: StateFlow<ReadingSpeed?> = c.stats.speed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val profile = c.settings.app.map { it.profile }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.profile)

    val favouriteIds: StateFlow<Set<Long>> = c.library.observeFavouriteIds().map { list -> list.map { it.bookId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadedIds: StateFlow<Set<Long>> = c.downloads.observeAll().map { list -> list.filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Favourites and downloads are read from the local cache so they also work offline. */
    val favourites: StateFlow<List<Book>> = c.library.observeFavouriteIds()
        .map { favs -> favs.mapNotNull { c.db.books().get(it.bookId)?.toBook() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ownBooks: StateFlow<List<Book>> = c.localBooks.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val onDevice: StateFlow<List<Book>> = c.downloads.observeAll()
        .map { list -> list.filter { it.state == DownloadState.DONE }.mapNotNull { c.db.books().get(it.bookId)?.toBook() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load()
    }

    private var lastLoad = 0L

    /** Refresh quietly when the screen comes back (e.g. after reading). */
    fun onResume() {
        if (System.currentTimeMillis() - lastLoad > 5_000) load(refresh = false)
    }

    fun load(refresh: Boolean = false) {
        lastLoad = System.currentTimeMillis()
        viewModelScope.launch {
            _state.update { it.copy(refreshing = refresh, loading = !refresh && it.continueReading.isEmpty()) }
            if (refresh) launch { c.library.refreshFavourites() }
            runCatching { c.library.home() }
                .onSuccess { h -> _state.update { it.copy(loading = false, refreshing = false, offline = h.offline, continueReading = h.continueReading, recentlyAdded = h.recentlyAdded, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, refreshing = false, error = e.message) } }
        }
    }
}
