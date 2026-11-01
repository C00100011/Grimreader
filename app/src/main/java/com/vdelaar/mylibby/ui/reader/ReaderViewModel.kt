package com.vdelaar.mylibby.ui.reader

import androidx.lifecycle.ViewModel
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.database.AnnotationEntity
import com.vdelaar.mylibby.core.database.BookmarkEntity
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.data.ReadingSpeed
import com.vdelaar.mylibby.data.dict.toPackEntry
import com.vdelaar.mylibby.data.StatsRepository
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.tts.TtsSegment
import com.vdelaar.mylibby.tts.TtsSource
import com.vdelaar.mylibby.tts.TtsStatus
import com.vdelaar.mylibby.widget.StreakWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

data class ReaderUiState(
    val book: Book? = null,
    val loadingMessage: String? = null,
    val downloadProgress: Float? = null,
    val error: String? = null,
    val file: File? = null,
    val fileUrl: String? = null,
    val initialCfi: String? = null,
    val ready: ReadyEvent? = null,
    val location: RelocateEvent? = null,
    val chromeVisible: Boolean = true,
    val selection: SelectionEvent? = null,
    val activeAnnotation: AnnotationEntity? = null,
    val searchResults: List<Pair<String, SearchItem>> = emptyList(),
    val searching: Boolean = false,
    val serverPosition: ServerPosition? = null,
    val message: String? = null,
    val bridgeReady: Boolean = false,
    val opened: Boolean = false,
    /** The reading timer is paused because nothing has happened for a while (see [ReadingClock]). */
    val idlePaused: Boolean = false,
)

/** The dictionaries suggested in the popover when none is installed for the book's language. */
sealed interface Offers {
    data object Idle : Offers
    data object Loading : Offers
    data object Unavailable : Offers
    /** [wikt] gives definitions and synonyms, [packs] (FreeDict) translations. */
    data class Ready(val packs: List<com.vdelaar.mylibby.data.dict.PackInfo>, val wikt: List<com.vdelaar.mylibby.data.dict.WiktPackInfo> = emptyList()) : Offers
}

/** How a dictionary lookup stands. */
sealed interface LookupResult {
    data object Loading : LookupResult
    data class Found(val entry: com.vdelaar.mylibby.data.WordEntry) : LookupResult
    data object NotFound : LookupResult
    /** Nothing is downloaded for the language of this book. */
    data object NoDictionary : LookupResult
}

/** The dictionary popover: the [word] looked up, the [context] sentence it was in, and the words looked up before it ([trail]). */
data class LookupUi(
    val word: String,
    val context: String = "",
    val result: LookupResult = LookupResult.Loading,
    val trail: List<String> = emptyList(),
    /** Two-letter code of the book's language when no dictionary for it is on the device yet: the popover then offers to download one. */
    val offerFor: String? = null,
)

data class SpeedUiState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val finished: Boolean = false,
    val unit: SpeedUnit? = null,
    val wpm: Int = 300,
    val wordIndex: Int = 0,
    val wordCount: Int = 0,
)

/** A position elsewhere that is further than ours: an exact spot ([cfi]) or, from a Kobo or KOReader, only a [percent]. */
data class ServerPosition(val cfi: String?, val percent: Float, val other: Boolean)

class ReaderViewModel(private val c: AppContainer, val bookId: Long) : ViewModel(), TtsSource {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val settings = c.settings.reader

    /** The app's Light / Dark / System choice; the reader's dark page follows it. */
    val themeMode: StateFlow<com.vdelaar.mylibby.core.datastore.ThemeMode> =
        c.settings.app.map { it.themeMode }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.themeMode)
    val ttsState = c.tts.state
    val annotations: StateFlow<List<AnnotationEntity>> = c.reading.observeAnnotations(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = c.reading.observeBookmarks(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Questions about the book, answered on the phone from what has been read so far. */
    val chat = com.vdelaar.mylibby.ai.BookChatController(
        engine = c.bookChat, ai = c.localAi, bookId = bookId, scope = viewModelScope,
        position = {
            val l = _state.value.location
            // The start of the page being read, inside the section the reader reports: nothing from this page on is used.
            val share = if (l != null && l.pagesInSection > 0) (l.pageInSection - 1).coerceAtLeast(0).toFloat() / l.pagesInSection else 0f
            com.vdelaar.mylibby.ai.ReadPosition(l?.sectionIndex ?: 0, share, ((l?.fraction ?: 0.0) * 100).toInt())
        },
        bookInfo = { (_state.value.book?.title.orEmpty()) to (_state.value.book?.authors?.joinToString(", ").orEmpty()) },
    )

    val speedSettings = c.settings.speed
    private val _speedRead = MutableStateFlow(SpeedUiState())
    val speedRead: StateFlow<SpeedUiState> = _speedRead.asStateFlow()
    private var speedWords: List<String> = emptyList()
    private var speedUnits: List<SpeedUnit> = emptyList()
    private var speedIndex = 0
    private var speedLang: String? = null
    private var speedJob: Job? = null
    private var speedAutoplay = false
    private var speedMs = 0L
    private var speedWordsRead = 0

    private val _speed = MutableStateFlow(ReadingSpeed(StatsRepository.DEFAULT_BYTES_PER_MINUTE, 213, false))
    val speed: StateFlow<ReadingSpeed> = _speed.asStateFlow()

    /** Set by the WebView host to run JavaScript in the reader page. */
    var js: ((String) -> Unit)? = null

    private var bookFileId: Long? = null
    private var progressJob: Job? = null
    private var ttsTicker: Job? = null
    private var lastTtsMarkCfi: String? = null

    // Session tracking
    private var sessionStart = 0L
    private var sessionStartProgress = 0f
    private var sessionStartCfi: String? = null
    private val clock = ReadingClock(
        enabled = { c.settings.reader.value.idleDetection },
        multiplier = { c.settings.reader.value.idleMultiplier },
    )
    private var idleJob: Job? = null
    private var lastTouch = 0L
    private var bytesRead = 0.0
    private var pagesTurned = 0
    private var listenedMs = 0L
    private var pageStart = 0L
    private var lastLocation: RelocateEvent? = null
    private var sessionOpen = false

    init {
        // A dictionary that finishes installing while a word is open (the download ran on in the background): look the word up again.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(c.dictionaryPacks.installed, c.wiktionary.installed) { a, b -> a.size + b.size }
                .drop(1).collect { if (_lookup.value != null) retryLookup() }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadingMessage = str(R.string.opening_book), error = null) }
            try {
                val book = c.library.detail(bookId)
                bookFileId = book.primaryFileId
                _state.update { it.copy(book = book) }
                if (!book.isReadable) {
                    _state.update { it.copy(error = str(R.string.format_unsupported, book.primaryFileType ?: str(R.string.this_format)), loadingMessage = null) }
                    return@launch
                }
                launch { c.reading.pullAnnotations(bookId) }
                launch { c.reading.pullBookmarks(bookId) }
                _speed.value = c.stats.currentSpeed(bookId)

                val local = c.reading.localPosition(bookId)
                val start = c.reading.startPosition(bookId)
                val initial = if (start.serverNewer) local?.cfi else start.cfi
                if (start.serverNewer && start.cfi != null) _state.update { it.copy(serverPosition = ServerPosition(start.cfi, start.percent, other = false)) }
                // A Kobo (or KOReader) is further: no exact spot, a percentage; offer to jump there.
                else if (start.otherPercent != null) {
                    // Until the reader chooses, our position (the start of the book) must not be sent: it would be the
                    // newest and a two-way sync would hand it to the Kobo, replacing the position the Kobo is at.
                    holdProgress = true
                    _state.update { it.copy(serverPosition = ServerPosition(null, start.otherPercent, other = true)) }
                }

                _state.update { it.copy(loadingMessage = str(R.string.downloading_book)) }
                val file = c.downloads.readableFile(book) { p -> _state.update { it.copy(downloadProgress = p) } }
                val base = when {
                    file.parentFile?.name == "local" -> "local"
                    file.parentFile?.name == "books" && file.parentFile?.parentFile == c.appContext.filesDir -> "books"
                    else -> "cache"
                }
                chat.attach(file)
                _state.update {
                    it.copy(file = file, fileUrl = "https://appassets.androidplatform.net/$base/${file.name}", initialCfi = initial, loadingMessage = str(R.string.preparing_pages), downloadProgress = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: str(R.string.open_error), loadingMessage = null) }
            }
        }
    }

    fun retry() = load()

    /** Opens the book in the WebView once both the page script and the file are ready. */
    fun openIfReady(settingsJson: String) {
        val s = _state.value
        if (!s.bridgeReady || s.fileUrl == null || s.opened) return
        _state.update { it.copy(opened = true) }
        val name = s.file?.name ?: "book.epub"
        js?.invoke("reader.open(${q(s.fileUrl)}, ${q(name)}, ${s.initialCfi?.let { q(it) } ?: "null"}, $settingsJson, ${annotationsJson()})")
    }

    fun applySettings(settingsJson: String) {
        if (_state.value.opened) js?.invoke("reader.applySettings($settingsJson)")
    }

    // Bridge --------------------------------------------------------------------------------------

    fun annotationsJson(): String = BridgeJson.encodeToString(annotations.value.map { it.toJs() })

    private fun AnnotationEntity.toJs() = JsAnnotation(cfi, color, style, note)

    private fun q(s: String) = BridgeJson.encodeToString(s)

    fun onBridgeEvent(type: String, json: String) {
        when (type) {
            "bridgeReady" -> _state.update { it.copy(bridgeReady = true) }
            "ready" -> {
                val e = BridgeJson.decodeFromString<ReadyEvent>(json)
                _state.update { it.copy(ready = e, loadingMessage = null) }
                viewModelScope.launch {
                    if (!c.stats.hasTextStats(bookId)) js?.invoke("reader.computeWordStats()")
                }
            }
            "relocate" -> onRelocate(BridgeJson.decodeFromString(json))
            "tap" -> {
                markActivity()
                _state.update { it.copy(chromeVisible = !it.chromeVisible, activeAnnotation = null) }
            }
            "selection" -> {
                val e = BridgeJson.decodeFromString<SelectionEvent>(json)
                _state.update { it.copy(selection = e, activeAnnotation = null) }
                // Holding down on one word is how you look it up; a longer selection keeps its menu.
                if (settings.value.lookupOnHold && com.vdelaar.mylibby.data.isSingleWord(e.text)) lookUpSelection()
            }
            "selectionCleared" -> _state.update { it.copy(selection = null) }
            "annotationClick" -> {
                val cfi = BridgeJson.decodeFromString<CfiEvent>(json).cfi
                _state.update { it.copy(activeAnnotation = annotations.value.firstOrNull { a -> a.cfi == cfi }) }
            }
            "externalLink" -> _state.update { it.copy(message = "link:" + BridgeJson.decodeFromString<LinkEvent>(json).href) }
            "searchResults" -> {
                val e = BridgeJson.decodeFromString<SearchResultsEvent>(json)
                _state.update { s -> s.copy(searchResults = s.searchResults + e.items.map { e.label to it }) }
            }
            "searchDone" -> _state.update { it.copy(searching = false) }
            "wordStats" -> {
                val e = BridgeJson.decodeFromString<WordStatsEvent>(json)
                viewModelScope.launch {
                    c.stats.saveTextStats(bookId, e.bytes, e.words)
                    _speed.value = c.stats.currentSpeed(bookId)
                }
            }
            "speedBlock" -> onSpeedBlock(BridgeJson.decodeFromString(json))
            "speedEnd" -> {
                speedJob?.cancel()
                _speedRead.update { it.copy(loading = false, playing = false, finished = true) }
            }
            "speedUnsupported" -> {
                _speedRead.value = SpeedUiState(wpm = _speedRead.value.wpm)
                _state.update { it.copy(message = str(R.string.speed_unsupported)) }
            }
            "ttsBlock" -> {
                val e = BridgeJson.decodeFromString<TtsBlockEvent>(json)
                c.tts.speakBlock(e.segments.map { TtsSegment(it.mark, it.text) }, ttsLanguage(e.lang))
            }
            "ttsEnd" -> {
                c.tts.stop()
                stopTtsTicker()
            }
            "error" -> {
                val m = BridgeJson.decodeFromString<MessageEvent>(json).message
                if (_state.value.ready == null) _state.update { it.copy(error = m, loadingMessage = null) }
                else _state.update { it.copy(message = m) }
            }
        }
    }

    private fun onRelocate(e: RelocateEvent) {
        val now = System.currentTimeMillis()
        val prev = lastLocation
        if (!sessionOpen) startSession(e)
        markActivity()
        if (prev != null) {
            val delta = e.fraction - prev.fraction
            val rawPageMs = now - pageStart
            // A page that sat open until the timer paused says nothing about how long it takes to read.
            val idlePage = clock.wasIdle()
            val pageMs = rawPageMs.coerceIn(0, 180_000)
            val forward = delta > 0 && delta < 0.02
            val listening = c.tts.state.value.status != TtsStatus.IDLE
            val speedReading = _speedRead.value.active
            if (forward && !listening && !speedReading) {
                clock.pageTurned(rawPageMs)
                pagesTurned++
                bytesRead += delta * (_state.value.ready?.totalBytes ?: 0L)
            }
            if (pageMs > 1500 && !idlePage && c.tts.state.value.status != TtsStatus.PLAYING && !speedReading) {
                val finished = e.sectionIndex == prev.sectionIndex + 1 && forward
                viewModelScope.launch {
                    c.stats.addSectionTime(bookId, prev.sectionIndex, prev.tocLabel, pageMs / 1000, if (forward) 1 else 0, finished)
                }
            }
        }
        pageStart = now
        lastLocation = e
        _state.update { it.copy(location = e) }
        if (e.tocLabel.isNotBlank()) c.tts.updateChapter(e.tocLabel)
        scheduleProgressSave(e)
    }

    /** True while an offer to jump to a Kobo's (or KOReader's) further position is open: nothing is saved or sent meanwhile. */
    private var holdProgress = false

    private fun scheduleProgressSave(e: RelocateEvent) {
        if (holdProgress) return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(1500)
            c.reading.saveProgress(bookId, bookFileId, e.cfi, e.href, (e.fraction * 100).toFloat(), lastTtsMarkCfi)
            delay(3500)
            c.reading.pushProgress(bookId)
        }
    }

    // Sessions -----------------------------------------------------------------------------------

    private fun startSession(e: RelocateEvent) {
        sessionOpen = true
        sessionStart = System.currentTimeMillis()
        sessionStartProgress = (e.fraction * 100).toFloat()
        sessionStartCfi = e.cfi
        clock.start(sessionStart)
        startIdleWatch()
        bytesRead = 0.0
        pagesTurned = 0
        listenedMs = 0
        speedMs = 0
        speedWordsRead = 0
        pageStart = sessionStart
    }

    private fun markActivity() {
        clock.interaction(System.currentTimeMillis())
        if (_state.value.idlePaused) _state.update { it.copy(idlePaused = false) }
    }

    /** Any touch in the reader counts as reading, so scrolling, selecting and menus keep the timer going. */
    fun onTouch() {
        val now = System.currentTimeMillis()
        if (!sessionOpen || now - lastTouch < 1000) return
        lastTouch = now
        markActivity()
    }

    /** "Yes, still reading": continue without counting the idle time. */
    fun resumeFromIdle() {
        clock.resume(System.currentTimeMillis())
        _state.update { it.copy(idlePaused = false) }
    }

    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                // Listening and speed reading keep the clock going themselves.
                val busy = c.tts.state.value.status == TtsStatus.PLAYING || _speedRead.value.playing
                if (!busy && clock.tick(System.currentTimeMillis())) _state.update { it.copy(idlePaused = true) }
            }
        }
    }

    /** Called when the reader goes to the background or closes. */
    fun flushSession() {
        if (!sessionOpen) return
        clock.finish(System.currentTimeMillis())
        idleJob?.cancel()
        _state.update { it.copy(idlePaused = false) }
        sessionOpen = false
        val loc = lastLocation ?: return
        val activeMs = clock.activeMs
        val seconds = (activeMs / 1000).toInt()
        val p = (loc.fraction * 100).toFloat()
        val book = _state.value.book ?: return
        // Make sure the final position is saved (not while an offer to jump to a further position is still open).
        val hold = holdProgress
        c.appScope.launch {
            if (!hold) {
                c.reading.saveProgress(bookId, bookFileId, loc.cfi, loc.href, p, lastTtsMarkCfi)
                c.reading.pushProgress(bookId)
            }
            if (seconds >= MIN_SESSION_SECONDS) {
                c.stats.recordSession(
                    ReadingSessionEntity(
                        bookId = bookId,
                        bookTitle = book.title,
                        bookType = book.primaryFileType,
                        startTime = sessionStart,
                        endTime = System.currentTimeMillis(),
                        durationSeconds = seconds,
                        startProgress = sessionStartProgress,
                        endProgress = p,
                        startCfi = sessionStartCfi,
                        endCfi = loc.cfi,
                        // Sessions with read-aloud don't say anything about reading speed.
                        bytesRead = if (listenedMs > 0 || speedMs > 0) 0 else bytesRead.toLong(),
                        // Speed reading counts towards a "pages" goal too (about 275 words a page).
                        pagesTurned = pagesTurned + SpeedReading.pagesFor(speedWordsRead),
                        localDate = LocalDate.now().toString(),
                        listening = listenedMs > activeMs / 2,
                    )
                )
                // Mark as reading / finished on Grimmory.
                if (p >= 99.5f && book.readStatus != "READ") c.library.setStatus(bookId, "READ")
                else if (book.readStatus == null || book.readStatus == "UNREAD" || book.readStatus == "UNSET") c.library.setStatus(bookId, "READING")
                StreakWidget.refresh(c.appContext)
            }
            _speed.value = c.stats.currentSpeed(bookId)
        }
    }

    fun onResumeReading() {
        lastLocation?.let { if (!sessionOpen) startSession(it) }
    }

    // Commands ----------------------------------------------------------------------------------

    fun next() { markActivity(); js?.invoke("reader.next()") }
    fun prev() { markActivity(); js?.invoke("reader.prev()") }
    fun goTo(target: String) { js?.invoke("reader.goTo(${q(target)})") }
    fun goToFraction(f: Float) { js?.invoke("reader.goToFraction($f)") }
    fun goToChapterFraction(f: Float) { js?.invoke("reader.goToChapterFraction($f)") }
    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    fun hideChrome() = _state.update { it.copy(chromeVisible = false) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    /** Staying where we are is a choice too: from here on our position is saved and sent as usual. */
    fun dismissServerPosition() {
        holdProgress = false
        _state.update { it.copy(serverPosition = null) }
        lastLocation?.let { scheduleProgressSave(it) }
    }

    fun jumpToServerPosition() {
        val p = _state.value.serverPosition
        holdProgress = false
        if (p != null) { if (p.cfi != null) goTo(p.cfi) else goToFraction(p.percent / 100f) }
        _state.update { it.copy(serverPosition = null) }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(searchResults = emptyList(), searching = true) }
        js?.invoke("reader.search(${q(query)})")
    }

    fun clearSearch() {
        _state.update { it.copy(searchResults = emptyList(), searching = false) }
        js?.invoke("reader.clearSearch()")
    }

    // Highlights & bookmarks ---------------------------------------------------------------------

    fun highlightSelection(color: String = HighlightColors.first(), style: String = "highlight", note: String? = null) {
        val sel = _state.value.selection ?: return
        viewModelScope.launch {
            val a = c.reading.addAnnotation(bookId, sel.cfi, sel.text, color, style, note, _state.value.location?.tocLabel)
            js?.invoke("reader.addAnnotation(${BridgeJson.encodeToString(a.toJs())})")
            js?.invoke("reader.clearSelection()")
            _state.update { it.copy(selection = null, activeAnnotation = a) }
        }
    }

    fun updateAnnotation(stale: AnnotationEntity, color: String = stale.color, style: String = stale.style, note: String? = stale.note) {
        val a = annotations.value.firstOrNull { it.localId == stale.localId } ?: stale
        viewModelScope.launch {
            c.reading.updateAnnotation(a, color, style, note)
            val updated = a.copy(color = color, style = style, note = note)
            js?.invoke("reader.addAnnotation(${BridgeJson.encodeToString(updated.toJs())})")
            _state.update { it.copy(activeAnnotation = updated) }
        }
    }

    fun deleteAnnotation(a: AnnotationEntity) {
        viewModelScope.launch {
            c.reading.deleteAnnotation(a)
            js?.invoke("reader.removeAnnotation(${q(a.cfi)})")
            _state.update { it.copy(activeAnnotation = null) }
        }
    }

    fun dismissAnnotation() = _state.update { it.copy(activeAnnotation = null) }

    fun clearSelection() {
        js?.invoke("reader.clearSelection()")
        _state.update { it.copy(selection = null) }
    }

    private val _lookup = MutableStateFlow<LookupUi?>(null)
    val lookup: StateFlow<LookupUi?> = _lookup.asStateFlow()
    private var lookupJob: Job? = null

    /** Opens the dictionary on the selected text (the selection goes away: the popover takes over). */
    fun lookUpSelection() {
        val sel = _state.value.selection ?: return
        clearSelection()
        startLookup(LookupUi(sel.text.trim(), sel.context))
    }

    /** A synonym tapped in the popover: look that up next, keeping the way back. */
    fun lookUpWord(word: String) {
        val now = _lookup.value ?: return
        startLookup(LookupUi(word, now.context, trail = now.trail + now.word))
    }

    /** Back to the word before the current one. */
    fun lookupBack() {
        val now = _lookup.value ?: return
        val prev = now.trail.lastOrNull() ?: return
        startLookup(LookupUi(prev, now.context, trail = now.trail.dropLast(1)))
    }

    fun retryLookup() {
        _lookup.value?.let { startLookup(it.copy(result = LookupResult.Loading)) }
    }

    val installedPacks = c.dictionaryPacks.installed
    val packProgress = c.dictionaryPacks.progress
    val installedWikt = c.wiktionary.installed
    val wiktProgress = c.wiktionary.progress
    private val _offers = MutableStateFlow<Offers>(Offers.Idle)
    val offers: StateFlow<Offers> = _offers.asStateFlow()
    private var offersFor: String? = null

    /** Looks up which dictionaries exist for the language the popover is offering one for. */
    fun loadOffers() {
        val language = _lookup.value?.offerFor ?: return
        if (offersFor == language && _offers.value !is Offers.Unavailable) return
        offersFor = language
        _offers.value = Offers.Loading
        viewModelScope.launch {
            val wikt = com.vdelaar.mylibby.data.dict.wiktionaryPacksFor(language)
            _offers.value = try {
                val mine = com.vdelaar.mylibby.data.dict.iso3(java.util.Locale.getDefault().language)
                Offers.Ready(com.vdelaar.mylibby.data.dict.recommendPacks(c.dictionaryPacks.catalog(), com.vdelaar.mylibby.data.dict.iso3(language), mine), wikt)
            } catch (e: java.io.IOException) {
                // The list of FreeDict dictionaries needs the network; the Wiktionary ones are known.
                if (wikt.isEmpty()) Offers.Unavailable else Offers.Ready(emptyList(), wikt)
            }
        }
    }

    /** Downloads as background work: leaving the book or the app does not stop it, and a notification says when it is ready. */
    fun downloadPack(info: com.vdelaar.mylibby.data.dict.PackInfo) {
        c.dictionaryPacks.dismissFailure(info.name)
        com.vdelaar.mylibby.data.dict.DictionaryDownloads.startFreedict(c.appContext, info)
    }

    /** Same for a Wiktionary pack, which takes longer to read in than to download. */
    fun downloadWikt(info: com.vdelaar.mylibby.data.dict.WiktPackInfo) {
        c.wiktionary.dismissFailure(info.id)
        com.vdelaar.mylibby.data.dict.DictionaryDownloads.startWiktionary(c.appContext, info)
    }

    /** The dictionary to show when a word is in several (kept for the next lookups). */
    val dictionaryChoice: StateFlow<String> = settings.map { it.dictionaryChoice }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.dictionaryChoice)

    fun chooseDictionary(id: String) { updateSettings { it.copy(dictionaryChoice = id) } }

    /** The dictionary whose synonyms to show; apart from the dictionary above them, since only some have any. */
    val synonymChoice: StateFlow<String> = settings.map { it.synonymChoice }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.synonymChoice)

    fun chooseSynonyms(id: String) { updateSettings { it.copy(synonymChoice = id) } }

    fun closeLookup() {
        lookupJob?.cancel()
        _lookup.value = null
    }

    private fun startLookup(ui: LookupUi) {
        lookupJob?.cancel()
        _lookup.value = ui.copy(result = LookupResult.Loading)
        val language = com.vdelaar.mylibby.data.dictionaryLanguage(_state.value.book?.language ?: _state.value.ready?.language)
        val source = com.vdelaar.mylibby.data.dict.iso3(language)
        lookupJob = viewModelScope.launch {
            val packs = c.dictionaryPacks
            val offerFor = language.takeIf { !packs.hasSource(source) && !c.wiktionary.hasLanguage(it) }
            val local = runCatching { packs.lookup(ui.word, source, language) }.getOrDefault(emptyList()).map { it.toPackEntry() }
            // Every dictionary that knows the word: those with definitions first (in the language's own words before English ones),
            // then the translations. The reader picks which one to look at in the popover.
            val definitions = runCatching { c.wiktionary.lookup(ui.word, language) }.getOrDefault(emptyList())
                .sortedByDescending { a -> com.vdelaar.mylibby.data.dict.WIKTIONARY_PACKS.firstOrNull { it.id == a.pack.id }?.native == true }
                .mapNotNull { a ->
                    val info = com.vdelaar.mylibby.data.dict.WIKTIONARY_PACKS.firstOrNull { it.id == a.pack.id }
                    a.toPackEntry(info?.let { com.vdelaar.mylibby.data.dict.wiktLabel(c.appContext, it) } ?: a.pack.languageName)
                }
            val entry = (definitions + local).takeIf { it.isNotEmpty() }?.let { com.vdelaar.mylibby.data.WordEntry(ui.word, language, it) }
            val result = when {
                entry != null -> LookupResult.Found(entry)
                offerFor != null -> LookupResult.NoDictionary
                else -> LookupResult.NotFound
            }
            _lookup.update { if (it?.word == ui.word) it.copy(result = result, offerFor = offerFor) else it }
        }
    }

    val currentBookmark: BookmarkEntity?
        get() {
            val loc = _state.value.location ?: return null
            return bookmarks.value.firstOrNull { abs(it.percent - loc.fraction * 100) < 0.15 }
        }

    fun toggleBookmark() {
        val loc = _state.value.location ?: return
        viewModelScope.launch {
            val existing = currentBookmark
            if (existing != null) {
                c.reading.deleteBookmark(existing)
                _state.update { it.copy(message = str(R.string.bookmark_removed)) }
            } else {
                c.reading.addBookmark(bookId, loc.cfi, loc.tocLabel.ifBlank { "${(loc.fraction * 100).toInt()}%" }, (loc.fraction * 100).toFloat())
                _state.update { it.copy(message = str(R.string.page_bookmarked)) }
            }
        }
    }

    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { c.reading.deleteBookmark(b) }

    fun updateSettings(f: (com.vdelaar.mylibby.core.datastore.ReaderSettings) -> com.vdelaar.mylibby.core.datastore.ReaderSettings) =
        viewModelScope.launch { c.settings.updateReader(f) }

    // Text-to-speech -----------------------------------------------------------------------------

    fun startTts(fromSelection: Boolean = false) {
        c.tts.source = this
        c.tts.ensureEngine {
            c.tts.startSession(_state.value.book?.title.orEmpty(), _state.value.location?.tocLabel.orEmpty())
            js?.invoke("reader.ttsStart(${fromSelection})")
            startTtsTicker()
        }
        _state.update { it.copy(selection = null, chromeVisible = false) }
    }

    /** The language chosen for this book, or null for automatic. */
    val ttsLanguageOverride: String? get() = c.settings.tts.value.bookLanguages[bookId]

    /** Automatic choice: Grimmory's language first (most reliable), then the EPUB's own metadata. */
    val autoTtsLanguage: String
        get() = normalizeLang(_state.value.book?.language)
            ?: normalizeLang(_state.value.ready?.language)
            ?: "en"

    private fun ttsLanguage(fromDocument: String): String =
        ttsLanguageOverride ?: normalizeLang(_state.value.book?.language) ?: normalizeLang(fromDocument)
            ?: normalizeLang(_state.value.ready?.language) ?: "en"

    private fun normalizeLang(raw: String?): String? {
        val l = raw?.trim()?.lowercase().orEmpty()
        return when {
            l.isBlank() -> null
            l.startsWith("nl") || l == "dut" || l == "nld" || l.contains("dutch") || l.contains("nederlands") -> "nl"
            l.startsWith("en") || l == "eng" || l.contains("english") -> "en"
            else -> l.substringBefore('-').take(3)
        }
    }

    /** Switch the read-aloud language for this book (null = automatic); applies immediately. */
    fun setTtsLanguage(language: String?) {
        viewModelScope.launch {
            c.settings.updateTts {
                it.copy(bookLanguages = if (language == null) it.bookLanguages - bookId else it.bookLanguages + (bookId to language))
            }
            c.tts.clearError()
            if (c.tts.state.value.status == TtsStatus.PLAYING) {
                c.tts.pause()
                onResumeRequested()
            }
        }
    }

    fun toggleTts() {
        when (c.tts.state.value.status) {
            TtsStatus.IDLE -> startTts()
            TtsStatus.PLAYING -> onPauseRequested()
            TtsStatus.PAUSED -> onResumeRequested()
        }
    }

    fun stopTts() = onStopRequested()

    fun setSleepTimer(minutes: Int) = c.tts.setSleepTimer(minutes)

    fun setSpeechRate(rate: Float) = viewModelScope.launch { c.settings.updateTts { it.copy(speechRate = rate) } }

    private fun startTtsTicker() {
        ttsTicker?.cancel()
        ttsTicker = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (c.tts.state.value.status == TtsStatus.PLAYING) {
                    if (!sessionOpen) lastLocation?.let { startSession(it) }
                    markActivity()
                    listenedMs += 10_000
                }
            }
        }
    }

    private fun stopTtsTicker() { ttsTicker?.cancel() }

    override fun onSegmentStarted(mark: String) {
        js?.invoke("reader.ttsMark(${q(mark)})")
    }

    override fun onBlockFinished() {
        js?.invoke("reader.ttsNext()")
    }

    override fun onPauseRequested() {
        c.tts.pause()
    }

    override fun onResumeRequested() {
        c.tts.startSession(_state.value.book?.title.orEmpty(), _state.value.location?.tocLabel.orEmpty())
        js?.invoke("reader.ttsResume()")
    }

    override fun onNextRequested() {
        js?.invoke("reader.ttsNext()")
    }

    override fun onPreviousRequested() {
        js?.invoke("reader.ttsPrev()")
    }

    override fun onStopRequested() {
        c.tts.stop()
        stopTtsTicker()
        js?.invoke("reader.ttsStop()")
    }

    // Speed reading (RSVP) ------------------------------------------------------------------------

    fun startSpeedRead() {
        if (c.tts.state.value.status != TtsStatus.IDLE) onStopRequested()
        val s = c.settings.speed.value
        if (!sessionOpen) lastLocation?.let { startSession(it) }
        markActivity()
        speedMs = maxOf(speedMs, 1) // marks this session as speed reading: it says nothing about normal reading speed
        speedAutoplay = true
        speedIndex = 0
        _speedRead.value = SpeedUiState(active = true, loading = true, wpm = s.bookWpm[bookId] ?: s.wpm)
        _state.update { it.copy(chromeVisible = false, selection = null) }
        js?.invoke("reader.speedStart()")
    }

    private fun onSpeedBlock(e: SpeedBlockEvent) {
        if (!_speedRead.value.active) return
        speedWords = e.words
        speedLang = normalizeLang(_state.value.book?.language) ?: normalizeLang(e.lang) ?: normalizeLang(_state.value.ready?.language)
        speedIndex = 0
        rebuildSpeedUnits(0)
        _speedRead.update { it.copy(loading = false, finished = false, wordCount = speedWords.size) }
        if (speedAutoplay) playSpeed() else showSpeedUnit()
    }

    private fun rebuildSpeedUnits(fromWord: Int) {
        val s = c.settings.speed.value
        speedUnits = SpeedReading.plan(speedWords, s.mode, s.phraseWords, speedLang)
        speedIndex = speedUnits.indexOfFirst { it.last >= fromWord }.coerceAtLeast(0)
    }

    private fun showSpeedUnit() {
        val u = speedUnits.getOrNull(speedIndex) ?: return
        _speedRead.update { it.copy(unit = u, wordIndex = u.first) }
    }

    fun playSpeed() {
        if (speedUnits.isEmpty()) return
        speedJob?.cancel()
        speedAutoplay = true
        _speedRead.update { it.copy(playing = true, finished = false) }
        speedJob = viewModelScope.launch {
            var since = 0
            var sinceSync = 0L
            var sinceActive = 0L
            while (isActive) {
                val u = speedUnits.getOrNull(speedIndex) ?: break
                _speedRead.update { it.copy(unit = u, wordIndex = u.first) }
                val d = SpeedReading.durationMs(u, _speedRead.value.wpm, since++)
                delay(d)
                speedWordsRead += u.last - u.first + 1
                sinceSync += d
                sinceActive += d
                if (sinceActive >= 10_000) {
                    if (!sessionOpen) lastLocation?.let { startSession(it) }
                    markActivity()
                    speedMs += sinceActive
                    sinceActive = 0
                }
                if (sinceSync >= 5_000) {
                    js?.invoke("reader.speedSync(${u.last})")
                    sinceSync = 0
                }
                speedIndex++
                if (speedIndex >= speedUnits.size) {
                    // End of this stretch of text: fetch the next chapter and carry on from there.
                    js?.invoke("reader.speedSync(${u.last})")
                    _speedRead.update { it.copy(loading = true) }
                    js?.invoke("reader.speedNext()")
                    return@launch
                }
            }
        }
    }

    fun pauseSpeed() {
        speedAutoplay = false
        speedJob?.cancel()
        if (!_speedRead.value.active) return
        _speedRead.update { it.copy(playing = false) }
        speedUnits.getOrNull(speedIndex)?.let { js?.invoke("reader.speedSync(${it.first})") }
    }

    fun toggleSpeed() {
        val s = _speedRead.value
        if (s.loading || s.finished) return
        if (s.playing) pauseSpeed() else playSpeed()
    }

    /** Back to the start of this sentence (or the previous one when already at its start). */
    fun speedBackSentence() {
        val current = speedUnits.getOrNull(speedIndex)?.first ?: return
        val target = SpeedReading.sentenceStart(speedWords, current)
        speedIndex = speedUnits.indexOfFirst { it.last >= target }.coerceAtLeast(0)
        if (_speedRead.value.playing) playSpeed() else showSpeedUnit()
    }

    fun setSpeedWpm(wpm: Int, persist: Boolean = false) {
        val v = wpm.coerceIn(SpeedReading.MIN_WPM, SpeedReading.MAX_WPM)
        _speedRead.update { it.copy(wpm = v) }
        if (persist) saveSpeedWpm()
    }

    fun saveSpeedWpm() {
        val v = _speedRead.value.wpm
        viewModelScope.launch { c.settings.updateSpeed { it.copy(wpm = v, bookWpm = it.bookWpm + (bookId to v)) } }
    }

    fun updateSpeedSettings(f: (com.vdelaar.mylibby.core.datastore.SpeedReadSettings) -> com.vdelaar.mylibby.core.datastore.SpeedReadSettings) {
        viewModelScope.launch {
            c.settings.updateSpeed(f)
            // Word <-> phrase changes the units, not the position.
            if (speedWords.isNotEmpty()) {
                val word = speedUnits.getOrNull(speedIndex)?.first ?: 0
                rebuildSpeedUnits(word)
                if (_speedRead.value.playing) playSpeed() else showSpeedUnit()
            }
        }
    }

    fun exitSpeedRead() {
        speedJob?.cancel()
        speedAutoplay = false
        val word = speedUnits.getOrNull(speedIndex)?.first ?: -1
        val wasActive = _speedRead.value.active
        if (wasActive) saveSpeedWpm()
        // Land the page on the word you stopped at, then count the time as reading.
        js?.invoke("reader.speedStop($word)")
        _speedRead.value = SpeedUiState(wpm = _speedRead.value.wpm)
        speedWords = emptyList()
        speedUnits = emptyList()
        if (wasActive) markActivity()
    }

    override fun onCleared() {
        flushSession()
        if (c.tts.source === this) {
            c.tts.stop()
            c.tts.source = null
        }
        js = null
        super.onCleared()
    }

    private companion object {
        const val MIN_SESSION_SECONDS = 20
    }
}
