package com.vdelaar.mylibby.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A model download in progress; [fraction] is null while the system does not say how far it is. */
data class DownloadUi(val fraction: Float?, val bytes: Long = 0, val total: Long = 0)

data class BookChatState(
    /** The chat button is only shown when this is true: an EPUB, and a phone that can (or can download) the on-device model. */
    val offered: Boolean = false,
    val status: AiStatus = AiStatus.UNAVAILABLE,
    val download: DownloadUi? = null,
    val messages: List<ChatTurn> = emptyList(),
    val answering: Boolean = false,
    val partial: String = "",
    val indexed: Int = 0,
    val indexTotal: Int = 0,
    val indexing: Boolean = false,
    val paused: String? = null,
    val error: String? = null,
    /** Set when the last question came before the reader had read anything. */
    val nothingRead: Boolean = false,
)

/** Connects the reader to [BookChatEngine]: owns the conversation and keeps the book's summaries up to date while the chat is open. */
class BookChatController(
    private val engine: BookChatEngine,
    private val ai: LocalAi,
    private val bookId: Long,
    private val scope: CoroutineScope,
    private val position: () -> ReadPosition,
    private val bookInfo: () -> Pair<String, String>,
    /** How often the status is asked while a download runs: AICore downloads in the background and does not always report progress. */
    private val pollMs: Long = 3000,
) {
    private val _state = MutableStateFlow(BookChatState())
    val state: StateFlow<BookChatState> = _state.asStateFlow()

    private var file: File? = null
    private var prepared = false
    private var indexJob: Job? = null
    private var askJob: Job? = null
    private var downloadJob: Job? = null

    /** Called when the book is open. Works out whether the chat is offered; reads nothing yet. */
    fun attach(book: File) {
        file = book
        scope.launch {
            val status = if (book.extension.equals("epub", ignoreCase = true)) ai.status() else AiStatus.UNAVAILABLE
            _state.update { it.copy(offered = status != AiStatus.UNAVAILABLE, status = status) }
        }
    }

    /** The chat sheet opened: get the book ready and start summarising what has been read. */
    fun open() {
        val book = file ?: return
        scope.launch {
            val status = ai.status()
            _state.update { it.copy(status = status, offered = status != AiStatus.UNAVAILABLE) }
            if (status == AiStatus.DOWNLOADING) watchDownload() // started earlier, here or in the system settings
            if (status != AiStatus.AVAILABLE) return@launch
            // Always asked: the notes may have been deleted from the settings since the last time.
            prepared = engine.prepare(bookId, book)
            if (!prepared) { _state.update { it.copy(offered = false) }; return@launch }
            startIndexing()
        }
    }

    fun close() {
        indexJob?.cancel() // the model only answers an app that is in front, so there is no point in carrying on
        indexJob = null
        _state.update { it.copy(indexing = false) }
    }

    private fun startIndexing() {
        if (indexJob?.isActive == true) return
        indexJob = scope.launch {
            _state.update { it.copy(indexing = true, paused = null) }
            val result = engine.summarize(bookId, position) { done, total -> _state.update { s -> s.copy(indexed = done, indexTotal = total) } }
            _state.update { it.copy(indexing = false, paused = (result as? IndexResult.Paused)?.reason) }
        }
    }

    fun retryIndexing() = startIndexing()

    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.update { it.copy(download = DownloadUi(null), error = null) }
            val poll = launch { pollUntilReady() }
            try {
                ai.download().collect { d ->
                    when (d) {
                        is AiDownload.Progress -> _state.update { it.copy(download = DownloadUi(d.fraction, d.bytes, d.total)) }
                        AiDownload.Done -> {}
                        is AiDownload.Failed -> { _state.update { it.copy(download = null, error = d.reason) }; return@collect }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(download = null, error = e.message ?: e.javaClass.simpleName) }
            }
            // The flow may end (or never say "done") while the system is still busy; the status is the truth.
            poll.join()
        }
    }

    private fun watchDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.update { it.copy(download = it.download ?: DownloadUi(null)) }
            pollUntilReady()
        }
    }

    /** Asks the status until the model is no longer downloading, then carries on with the chat. */
    private suspend fun pollUntilReady() {
        while (true) {
            delay(pollMs)
            val status = ai.status()
            _state.update { it.copy(status = status) }
            if (status == AiStatus.DOWNLOADING) continue
            _state.update { it.copy(download = null) }
            if (status == AiStatus.AVAILABLE) open()
            return
        }
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.answering) return
        val history = _state.value.messages
        _state.update { it.copy(messages = it.messages + ChatTurn(true, q), answering = true, partial = "", error = null, nothingRead = false) }
        askJob = scope.launch {
            try {
                val (title, authors) = bookInfo()
                val answer = engine.ask(bookId, title, authors, position(), history, q) { p -> _state.update { it.copy(partial = p) } }
                _state.update {
                    if (answer == null) it.copy(answering = false, partial = "", nothingRead = true)
                    else it.copy(answering = false, partial = "", messages = it.messages + ChatTurn(false, answer))
                }
            } catch (e: AiException) {
                _state.update { it.copy(answering = false, partial = "", error = e.message) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(answering = false, partial = "", error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun clearConversation() { askJob?.cancel(); _state.update { it.copy(messages = emptyList(), answering = false, partial = "", error = null, nothingRead = false) } }

    /** Forget the summaries of this book (they are made again the next time the chat opens). */
    fun deleteIndex() {
        indexJob?.cancel()
        scope.launch {
            engine.clear(bookId)
            prepared = false
            _state.update { it.copy(indexed = 0, indexTotal = 0, indexing = false) }
            open()
        }
    }
}
