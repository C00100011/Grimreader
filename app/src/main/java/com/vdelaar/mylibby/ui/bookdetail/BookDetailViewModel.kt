package com.vdelaar.mylibby.ui.bookdetail

import androidx.lifecycle.ViewModel
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.DownloadEntity
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailState(
    val book: Book? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val seriesBooks: List<Book> = emptyList(),
    /** Estimated minutes left to finish, based on your reading speed. */
    val minutesLeft: Double? = null,
    val message: String? = null,
    val reviews: List<com.vdelaar.mylibby.core.network.BookReviewDto> = emptyList(),
    /** Books Grimmory suggests based on this one. */
    val similar: List<Book> = emptyList(),
)

class BookDetailViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    val isFavourite: StateFlow<Boolean> = c.library.observeIsFavourite(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val download: StateFlow<DownloadEntity?> = c.downloads.observe(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val secondsRead: StateFlow<Long> = c.stats.bookSeconds(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    /** Reading pace of this book; recomputed whenever more reading time is recorded. */
    val pace: StateFlow<com.vdelaar.mylibby.data.BookPace?> = kotlinx.coroutines.flow.combine(
        c.stats.bookSeconds(bookId),
        c.settings.app.map { it.paceWpm }.distinctUntilChanged(),
    ) { _, _ -> }
        .map { c.stats.bookPace(bookId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.book == null, error = null) }
            if (bookId < 0) {
                // Local book: no server calls, just the stored metadata.
                runCatching { c.library.detail(bookId) }
                    .onSuccess { b -> _state.update { it.copy(book = b, loading = false) }; estimate(b) }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
                return@launch
            }
            runCatching { c.library.detail(bookId) }
                .onSuccess { b ->
                    _state.update { it.copy(book = b, loading = false) }
                    estimate(b)
                    launch { val r = c.library.reviews(bookId); _state.update { it.copy(reviews = r) } }
                    launch { val sim = c.library.similarBooks(bookId); _state.update { it.copy(similar = sim) } }
                    b.seriesName?.let { name ->
                        runCatching { c.library.seriesBooks(name) }.onSuccess { list -> _state.update { it.copy(seriesBooks = list) } }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: str(R.string.book_load_error)) } }
        }
    }

    private suspend fun estimate(b: Book) {
        val speed = c.stats.currentSpeed(bookId)
        val remainingFraction = 1.0 - ((c.reading.localPosition(bookId)?.percent ?: b.progress ?: 0f) / 100.0)
        val text = c.db.textStats().get(bookId)
        val minutes = when {
            text != null -> text.totalBytes * remainingFraction / speed.bytesPerMinute
            (b.pageCount ?: 0) > 0 -> b.pageCount!! * remainingFraction * 275.0 / speed.wordsPerMinute
            else -> null
        }
        _state.update { it.copy(minutesLeft = minutes) }
    }

    fun toggleFavourite() {
        val fav = !isFavourite.value
        viewModelScope.launch {
            c.library.setFavourite(bookId, fav)
            _state.update { it.copy(message = str(if (fav) R.string.added_to_favorites else R.string.removed_from_favorites)) }
        }
    }

    fun download() {
        val b = _state.value.book ?: return
        viewModelScope.launch {
            c.downloads.enqueue(b)
            _state.update { it.copy(message = str(R.string.saving_offline)) }
        }
    }

    fun removeDownload() {
        viewModelScope.launch {
            c.downloads.remove(bookId)
            _state.update { it.copy(message = str(R.string.removed_from_device)) }
        }
    }

    fun setStatus(status: String) {
        viewModelScope.launch {
            c.library.setStatus(bookId, status)
            _state.update { s -> s.copy(book = s.book?.copy(readStatus = status), message = str(R.string.marked_as, com.vdelaar.mylibby.core.model.ReadStatus.label(status).lowercase())) }
        }
    }

    fun setRating(rating: Int?) {
        _state.update { s -> s.copy(book = s.book?.copy(rating = rating)) }
        viewModelScope.launch { c.library.setRating(bookId, rating) }
    }

    fun deleteLocal(onDone: () -> Unit) {
        viewModelScope.launch {
            c.localBooks.delete(bookId)
            onDone()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
