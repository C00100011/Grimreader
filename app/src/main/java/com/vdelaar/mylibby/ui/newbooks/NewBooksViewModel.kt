package com.vdelaar.mylibby.ui.newbooks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.data.BookIdea
import com.vdelaar.mylibby.data.DiscoverException
import com.vdelaar.mylibby.data.FreeBook
import com.vdelaar.mylibby.data.GenreRow
import com.vdelaar.mylibby.data.Taste
import com.vdelaar.mylibby.data.WantList
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class NewBooksState(
    val loading: Boolean = true,
    val taste: Taste? = null,
    /** The languages being looked in (a subset of what the library reads). */
    val languages: List<String> = emptyList(),
    val rows: List<GenreRow> = emptyList(),
    val free: List<FreeBook> = emptyList(),
    val offline: Boolean = false,
    val error: String? = null,
    /** Id of the free book being added to the device. */
    val adding: Int? = null,
    val added: Set<Int> = emptySet(),
    val message: String? = null,
)

/** New books in your genres and languages, and free classics, for the Discover tab. */
class NewBooksViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(NewBooksState())
    val state: StateFlow<NewBooksState> = _state.asStateFlow()

    /** key -> list of the books on a want list, to mark them in the rows. */
    val wanted: StateFlow<Map<String, WantList>> = c.swipe.wanted
        .map { all -> all.associate { it.key to (if (it.list == WantList.BUY.name) WantList.BUY else WantList.READ) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var job: Job? = null

    /** Loads once; later calls only fill in what is missing. */
    fun load() {
        if (_state.value.rows.isNotEmpty() && _state.value.error == null) return
        reload()
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, offline = false) }
            try {
                val taste = _state.value.taste ?: c.discover.taste()
                val langs = _state.value.languages.ifEmpty { taste.languages }
                _state.update { it.copy(taste = taste, languages = langs) }
                val rows = c.discover.newInGenres(taste, langs)
                val free = runCatching { c.discover.freeToRead(langs.first()) }.getOrDefault(emptyList())
                _state.update { it.copy(loading = false, rows = rows, free = free) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message, offline = (e as? DiscoverException)?.offline == true) }
            }
        }
    }

    /** Picks or drops a language; at least one stays. */
    fun toggleLanguage(iso: String) {
        val s = _state.value
        val next = if (iso in s.languages) (s.languages - iso).ifEmpty { return } else s.languages + iso
        _state.update { it.copy(languages = next) }
        reload()
    }

    fun want(idea: BookIdea, list: WantList) {
        viewModelScope.launch {
            c.swipe.want(idea, list)
            _state.update { it.copy(message = str(if (list == WantList.READ) R.string.want_added_read else R.string.want_added_buy, idea.title)) }
        }
    }

    /** Downloads a free EPUB and adds it to the books on this device. */
    fun addFree(book: FreeBook) {
        if (_state.value.adding != null) return
        viewModelScope.launch {
            _state.update { it.copy(adding = book.id) }
            val file = File(c.appContext.cacheDir, "free-${book.id}.epub")
            try {
                c.discover.download(book.epubUrl, file)
                c.localBooks.import(Uri.fromFile(file))
                // Books on this device show in Library: make sure they are switched on.
                c.settings.updateApp { it.copy(deviceBooks = true) }
                _state.update { it.copy(adding = null, added = it.added + book.id, message = str(R.string.free_added, book.title)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(adding = null, message = str(R.string.free_failed, e.message ?: "?")) }
            } finally {
                file.delete()
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
