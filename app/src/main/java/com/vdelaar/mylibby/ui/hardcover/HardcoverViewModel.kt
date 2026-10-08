package com.vdelaar.mylibby.ui.hardcover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.HardcoverException
import com.vdelaar.mylibby.data.HardcoverBook
import com.vdelaar.mylibby.data.HardcoverDuration
import com.vdelaar.mylibby.data.HardcoverSuggestions
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HcLoad<out T> {
    data object Idle : HcLoad<Nothing>
    data object Loading : HcLoad<Nothing>
    data class Data<T>(val value: T) : HcLoad<T>
    data class Failed(val error: HardcoverException) : HcLoad<Nothing>
}

/** Trending and suggested books from Hardcover, for the Discover tab. Nothing is requested without a key. */
class HardcoverViewModel(private val c: AppContainer) : ViewModel() {
    val connected: StateFlow<Boolean> = c.tokens.hardcoverConnected
    val duration = MutableStateFlow(HardcoverDuration.WEEK)
    val trending = MutableStateFlow<HcLoad<List<HardcoverBook>>>(HcLoad.Idle)
    val suggestions = MutableStateFlow<HcLoad<HardcoverSuggestions>>(HcLoad.Idle)

    /** What is already in the library, to mark books you have. */
    val library = MutableStateFlow<List<Book>>(emptyList())

    private var trendingJob: Job? = null
    private var suggestionsJob: Job? = null

    /** Loads what is missing or failed; does nothing for what is already shown. */
    fun load() {
        if (!c.hardcover.hasKey) return reset()
        if (trending.value is HcLoad.Idle || trending.value is HcLoad.Failed) loadTrending()
        if (suggestions.value is HcLoad.Idle || suggestions.value is HcLoad.Failed) loadSuggestions()
        viewModelScope.launch { library.value = runCatching { c.library.libraryBooks() }.getOrDefault(emptyList()) }
    }

    fun reset() {
        trendingJob?.cancel(); suggestionsJob?.cancel()
        trending.value = HcLoad.Idle
        suggestions.value = HcLoad.Idle
    }

    fun setDuration(d: HardcoverDuration) {
        if (d == duration.value && trending.value !is HcLoad.Failed) return
        duration.value = d
        loadTrending()
    }

    fun retryTrending() = loadTrending()
    fun retrySuggestions() = loadSuggestions()

    private fun loadTrending() {
        trendingJob?.cancel()
        val d = duration.value
        trendingJob = viewModelScope.launch {
            trending.value = HcLoad.Loading
            trending.value = runCatching { c.hardcover.trending(d) }.fold({ HcLoad.Data(it) }, { fail(it) })
        }
    }

    private fun loadSuggestions() {
        suggestionsJob?.cancel()
        suggestionsJob = viewModelScope.launch {
            suggestions.value = HcLoad.Loading
            suggestions.value = runCatching { c.hardcover.suggestions() }.fold({ HcLoad.Data(it) }, { fail(it) })
        }
    }

    private fun fail(e: Throwable): HcLoad.Failed {
        if (e is CancellationException) throw e
        return HcLoad.Failed(e as? HardcoverException ?: HardcoverException(HardcoverException.Kind.SERVER, e.message ?: "Error"))
    }
}
