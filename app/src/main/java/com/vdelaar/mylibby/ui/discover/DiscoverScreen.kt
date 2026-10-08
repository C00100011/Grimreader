package com.vdelaar.mylibby.ui.discover

import androidx.compose.foundation.clickable
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.core.network.ShelfmarkBook
import com.vdelaar.mylibby.data.DownloadOutcome
import com.vdelaar.mylibby.data.Release
import com.vdelaar.mylibby.data.ShelfmarkActivity
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.components.GeneratedCover
import com.vdelaar.mylibby.ui.home.BookRow
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DiscoverState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<ShelfmarkBook> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
    val selected: ShelfmarkBook? = null,
    val releases: List<Release>? = null,
    val releasesError: String? = null,
    val activity: List<ShelfmarkActivity> = emptyList(),
    val message: String? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    /** Result of looking up the server's login method (step 2 of connecting). */
    val probe: com.vdelaar.mylibby.data.ShelfmarkProbe? = null,
    /** Show the in-app SSO sign-in for this server URL. */
    val oidcUrl: String? = null,
    val oidcLabel: String? = null,
    val sessionExpired: Boolean = false,
    /** Release search options, like Shelfmark's own release dialog. */
    val sources: List<com.vdelaar.mylibby.core.network.ShelfmarkSource> = emptyList(),
    val releaseSource: String? = null,
    val releaseLanguage: String? = null,
    val manualQuery: String = "",
)

/** New search text. An empty text ends the search: no results, no error, back to the Discover overview. */
internal fun DiscoverState.withQuery(q: String): DiscoverState =
    if (q.isBlank()) copy(query = q, searching = false, searched = false, results = emptyList(), error = null, selected = null, releases = null, releasesError = null)
    else copy(query = q)

class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(DiscoverState())
    val suggestions = MutableStateFlow<com.vdelaar.mylibby.data.Suggestions?>(null)
    val favIds = c.library.observeFavouriteIds().map { l -> l.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val dlIds = c.downloads.observeAll().map { l -> l.filter { it.state == com.vdelaar.mylibby.core.database.DownloadState.DONE }.map { it.bookId }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun loadSuggestions() {
        viewModelScope.launch {
            suggestions.value = runCatching { c.library.suggestions() }.getOrDefault(com.vdelaar.mylibby.data.Suggestions(emptyList(), emptyList()))
        }
    }

    val configured = c.settings.server.map { it.shelfmarkUrl.isNotBlank() }.stateIn(viewModelScope, SharingStarted.Eagerly, c.shelfmark.configured)
    private var poll: Job? = null

    fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                if (configured.value && !state.value.sessionExpired) {
                    runCatching { c.shelfmark.activity() }
                        .onSuccess { a -> state.update { it.copy(activity = a) } }
                        .onFailure { e -> if (e is com.vdelaar.mylibby.data.ShelfmarkSessionExpired) state.update { it.copy(sessionExpired = true) } }
                }
                delay(5000)
            }
        }
    }

    fun stopPolling() { poll?.cancel() }

    /** Step 1: find the server and how it wants us to sign in. */
    fun probe(url: String) {
        viewModelScope.launch {
            state.update { it.copy(connecting = true, connectError = null) }
            c.shelfmark.probe(url)
                .onSuccess { p ->
                    if (p.authMode == "none" || p.authenticated) {
                        finish(c.shelfmark.connectOpen(p))
                    } else {
                        state.update { it.copy(connecting = false, probe = p) }
                    }
                }
                .onFailure { e -> state.update { it.copy(connecting = false, connectError = e.message) } }
        }
    }

    fun resetProbe() = state.update { it.copy(probe = null, connectError = null) }

    fun connectPassword(user: String, pass: String) {
        val p = state.value.probe ?: return
        viewModelScope.launch {
            state.update { it.copy(connecting = true, connectError = null) }
            finish(c.shelfmark.connect(p.url, user, pass))
        }
    }

    fun connectApiKey(key: String) {
        val p = state.value.probe ?: return
        viewModelScope.launch {
            state.update { it.copy(connecting = true, connectError = null) }
            finish(c.shelfmark.connectWithApiKey(p.url, key))
        }
    }

    fun startOidc() {
        val url = state.value.probe?.url ?: c.settings.server.value.shelfmarkUrl
        val label = state.value.probe?.oidcLabel
        state.update { it.copy(oidcUrl = url, oidcLabel = label, connectError = null) }
    }

    fun cancelOidc() = state.update { it.copy(oidcUrl = null) }

    /** Expired session: SSO users sign in again in place; others go back through connecting. */
    fun signInAgain() {
        if (c.settings.server.value.shelfmarkAuth == "oidc") return startOidc()
        viewModelScope.launch {
            c.shelfmark.disconnect()
            state.update { DiscoverState() }
        }
    }

    fun oidcSignedIn(cookies: String) {
        val url = state.value.oidcUrl ?: return
        viewModelScope.launch {
            state.update { it.copy(oidcUrl = null, connecting = true) }
            finish(c.shelfmark.completeOidc(url, cookies))
        }
    }

    fun oidcFailed(message: String) = state.update { it.copy(oidcUrl = null, connectError = message) }

    private fun finish(error: String?) {
        state.update {
            it.copy(
                connecting = false,
                connectError = error,
                probe = if (error == null) null else it.probe,
                sessionExpired = if (error == null) false else it.sessionExpired,
                message = if (error == null && it.sessionExpired) str(R.string.sm_signed_in_again) else it.message,
            )
        }
        if (error == null) startPolling()
    }

    private fun handle(e: Throwable): String {
        if (e is com.vdelaar.mylibby.data.ShelfmarkSessionExpired) state.update { it.copy(sessionExpired = true) }
        return e.message ?: str(R.string.something_wrong)
    }

    private var searchJob: Job? = null

    /** Typing updates the text; emptying it leaves the search and brings the recommendations back. */
    fun onQuery(q: String) {
        if (q.isBlank()) searchJob?.cancel()
        state.update { it.withQuery(q) }
    }

    fun search() {
        val q = state.value.query.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            state.update { it.copy(searching = true, error = null) }
            runCatching { c.shelfmark.search(q) }
                .onSuccess { (books, _) -> state.update { it.copy(searching = false, results = books, searched = true) } }
                .onFailure { e -> val m = handle(e); state.update { it.copy(searching = false, error = m, searched = true) } }
        }
    }

    private var releasesJob: Job? = null

    fun select(book: ShelfmarkBook?) {
        state.update { it.copy(selected = book, releases = null, releasesError = null, releaseSource = null, manualQuery = "") }
        if (book == null) return
        if (state.value.sources.isEmpty()) {
            viewModelScope.launch { runCatching { c.shelfmark.sources() }.onSuccess { src -> state.update { it.copy(sources = src) } } }
        }
        loadReleases()
    }

    fun setReleaseSource(source: String?) {
        state.update { it.copy(releaseSource = source) }
        loadReleases()
    }

    fun setReleaseLanguage(language: String?) {
        state.update { it.copy(releaseLanguage = language) }
        loadReleases()
    }

    fun onManualQuery(q: String) = state.update { it.copy(manualQuery = q) }

    fun runManualQuery() = loadReleases()

    private fun loadReleases() {
        val s = state.value
        val book = s.selected ?: return
        releasesJob?.cancel()
        state.update { it.copy(releases = null, releasesError = null) }
        releasesJob = viewModelScope.launch {
            runCatching { c.shelfmark.releases(book, s.releaseSource, s.releaseLanguage, s.manualQuery) }
                .onSuccess { r -> state.update { it.copy(releases = r.sortedByDescending { rel -> rel.format.equals("epub", true) }) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val m = handle(e); state.update { it.copy(releases = emptyList(), releasesError = m) }
                }
        }
    }

    fun download(release: Release) {
        val book = state.value.selected ?: return
        viewModelScope.launch {
            val msg = when (val r = c.shelfmark.download(book, release)) {
                DownloadOutcome.Queued -> str(R.string.sm_queued, book.title)
                DownloadOutcome.Requested -> str(R.string.sm_requested_approval, book.title)
                is DownloadOutcome.Failed -> r.message
            }
            state.update { it.copy(message = msg, selected = null) }
            runCatching { c.shelfmark.activity() }.onSuccess { a -> state.update { it.copy(activity = a) } }
        }
    }

    fun request() {
        val book = state.value.selected ?: return
        viewModelScope.launch {
            val msg = when (val r = c.shelfmark.requestBook(book)) {
                is DownloadOutcome.Failed -> r.message
                else -> str(R.string.sm_requested, book.title)
            }
            state.update { it.copy(message = msg, selected = null) }
        }
    }

    fun consumeMessage() = state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(navigator: AppNavigator) {
    val vm = appViewModel { DiscoverViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val app by com.vdelaar.mylibby.ui.appContainer().settings.app.collectAsStateWithLifecycle()
    val searchOn = app.effectiveBookSearch == com.vdelaar.mylibby.core.datastore.BookSearchSource.SHELFMARK
    val hardcover = appViewModel(key = "hardcover") { com.vdelaar.mylibby.ui.hardcover.HardcoverViewModel(it) }
    val hardcoverLibrary by hardcover.library.collectAsStateWithLifecycle()
    var hardcoverBook by remember { mutableStateOf<com.vdelaar.mylibby.data.HardcoverBook?>(null) }
    val favIds by vm.favIds.collectAsStateWithLifecycle()
    val dlIds by vm.dlIds.collectAsStateWithLifecycle()
    var showConnect by rememberSaveable { mutableStateOf(false) }
    val newBooks = appViewModel(key = "newbooks") { com.vdelaar.mylibby.ui.newbooks.NewBooksViewModel(it) }
    val newBooksState by newBooks.state.collectAsStateWithLifecycle()
    val wantedNow by newBooks.wanted.collectAsStateWithLifecycle()
    var idea by remember { mutableStateOf<com.vdelaar.mylibby.data.BookIdea?>(null) }
    LaunchedEffect(newBooksState.message) { newBooksState.message?.let { snackbar.showSnackbar(it); newBooks.consumeMessage() } }
    // A book asked for from a want list: search for it with the book search.
    val request by com.vdelaar.mylibby.ui.UiRequests.discoverQuery.collectAsStateWithLifecycle()
    LaunchedEffect(request, configured, searchOn) {
        val q = request ?: return@LaunchedEffect
        if (configured && searchOn) { vm.onQuery(q); vm.search() }
        com.vdelaar.mylibby.ui.UiRequests.discoverQuery.value = null
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.loadSuggestions()
        onPauseOrDispose { }
    }

    androidx.compose.runtime.DisposableEffect(configured) {
        if (configured) vm.startPolling()
        onDispose { vm.stopPolling() }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Box(Modifier.fillMaxSize()) {
        if (searchOn && !configured && showConnect) {
            ConnectShelfmark(state, vm)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.tab_discover), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        if (configured && searchOn) TextField(
                            value = state.query,
                            onValueChange = vm::onQuery,
                            placeholder = { Text(stringResource(R.string.discover_search_hint)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) } },
                            singleLine = true,
                            shape = CircleShape,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (configured && searchOn) {
                    if (state.sessionExpired) item { SessionExpiredCard(onSignIn = vm::signInAgain) }
                    if (state.activity.isNotEmpty()) item { ActivityCard(state.activity.take(6)) }
                }
                val searching = configured && searchOn && (state.searching || state.searched || state.error != null || state.sessionExpired)
                if (!searching) {
                    if (app.recommendations != com.vdelaar.mylibby.core.datastore.RecommendationSource.OPEN_LIBRARY) {
                        item { com.vdelaar.mylibby.ui.newbooks.SwipeEntry(wantedCount = wantedNow.size, onSwipe = navigator::swipe, onWanted = navigator::wanted) }
                    }
                    // Library discovery first: unread books in your favourite genres.
                    val s = suggestions
                    if (app.recommendations == com.vdelaar.mylibby.core.datastore.RecommendationSource.LOCAL) when {
                        s == null -> Unit
                        s.books.isNotEmpty() -> item {
                            val coverWidth = 120.dp
                            Column(Modifier.fillMaxWidth()) {
                                BookRow(
                                    stringResource(R.string.next_reads), s.books.take(12), coverWidth, favIds, dlIds, navigator,
                                    action = stringResource(R.string.action_see_all), onAction = navigator::suggestions,
                                    subtitle = stringResource(R.string.next_read_because, s.genres.joinToString(", ")),
                                )
                            }
                        }
                        else -> item { EmptyState("🔮", stringResource(R.string.next_read_empty), stringResource(R.string.discover_library_empty_body)) }
                    }
                    // Trending and suggestions from Hardcover (needs the user's own API key; shows a setup card without one).
                    else if (app.recommendations == com.vdelaar.mylibby.core.datastore.RecommendationSource.HARDCOVER) item { com.vdelaar.mylibby.ui.hardcover.HardcoverBlock(hardcover, onBook = { hardcoverBook = it }, onSetup = navigator::integrations) }
                    // New books in your genres and languages (Open Library), plus free classics.
                    else item { com.vdelaar.mylibby.ui.newbooks.NewBooksBlock(newBooks, navigator, onIdea = { idea = it }) }
                }
                if (searchOn && !configured) {
                    item {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("🔭", style = MaterialTheme.typography.displaySmall)
                                Text(stringResource(R.string.discover_find_new), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.discover_connect_cta), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                androidx.compose.material3.Button(onClick = { showConnect = true }) { Text(stringResource(R.string.discover_connect_button)) }
                            }
                        }
                    }
                } else if (searchOn) {
                    when {
                        state.searching -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                        state.error != null && !state.sessionExpired -> item { EmptyState("😕", stringResource(R.string.search_failed), state.error ?: "") }
                        state.sessionExpired -> Unit
                        state.searched && state.results.isEmpty() -> item { EmptyState("🔍", stringResource(R.string.nothing_found), stringResource(R.string.nothing_found_body)) }
                        !state.searched -> item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(stringResource(R.string.discover_find_new), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.discover_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(state.results, key = { it.provider + it.provider_id }) { b ->
                        ResultRow(b) { vm.select(b) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.oidcUrl?.let { url ->
        OidcLoginDialog(
            baseUrl = url,
            providerLabel = state.oidcLabel,
            onSignedIn = vm::oidcSignedIn,
            onError = vm::oidcFailed,
            onDismiss = vm::cancelOidc,
        )
    }

    state.selected?.let { book ->
        ModalBottomSheet(onDismissRequest = { vm.select(null) }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ReleaseSheet(book, state, vm)
        }
    }

    idea?.let { i -> com.vdelaar.mylibby.ui.newbooks.IdeaSheet(i, onOpenLibrary = { idea = null; navigator.openBook(it.id) }, onDismiss = { idea = null }) }

    hardcoverBook?.let { hb ->
        com.vdelaar.mylibby.ui.hardcover.HardcoverBookSheet(
            book = hb,
            library = hardcoverLibrary,
            canFind = configured && searchOn,
            onFind = {
                hardcoverBook = null
                vm.onQuery(listOfNotNull(hb.title, hb.authors.firstOrNull()).joinToString(" "))
                vm.search()
            },
            onOpenLibrary = { hardcoverBook = null; navigator.openBook(it.id) },
            onDismiss = { hardcoverBook = null },
        )
    }
}

/** The Shelfmark sign-in. [compact] = embedded in Settings (no title, no full-screen layout). */
@Composable
internal fun ConnectShelfmark(state: DiscoverState, vm: DiscoverViewModel, compact: Boolean = false) {
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    val probe = state.probe
    Column(
        if (compact) Modifier.fillMaxWidth().padding(16.dp) else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp)) {
            if (!compact) {
            Text(stringResource(R.string.tab_discover), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text("🔭", style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.connect_shelfmark), style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                stringResource(R.string.connect_shelfmark_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            }

            if (probe == null) {
                // Step 1: address
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(stringResource(R.string.shelfmark_address)) },
                    placeholder = { Text("https://shelfmark.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) vm.probe(url) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.probe(url) }, enabled = url.isNotBlank() && !state.connecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_continue))
                }
            } else {
                // Step 2: sign in the way this server wants
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(probe.url.removePrefix("https://").removePrefix("http://").trimEnd('/'), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.TextButton(onClick = vm::resetProbe) { Text(stringResource(R.string.change)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (probe.usesOidc) {
                    Button(onClick = vm::startOidc, enabled = !state.connecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.sign_in_with, probe.oidcLabel ?: stringResource(R.string.single_sign_on)))
                    }
                    Text(
                        stringResource(R.string.sso_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (probe.allowsPassword) {
                    if (probe.usesOidc) {
                        Text(stringResource(R.string.or), modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.connectPassword(user, pass) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    val primary = !probe.usesOidc
                    val onClick = { vm.connectPassword(user, pass) }
                    val enabled = user.isNotBlank() && pass.isNotBlank() && !state.connecting
                    if (primary) Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.sign_in)) }
                    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.sign_in_password)) }
                }
                if (probe.authMode == "proxy") {
                    Text(
                        stringResource(R.string.proxy_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }

                // Fallback for any setup: API key
                Spacer(Modifier.height(16.dp))
                if (showApiKey || probe.authMode == "proxy") {
                    OutlinedTextField(
                        apiKey, { apiKey = it }, label = { Text(stringResource(R.string.api_key)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text(stringResource(R.string.api_key_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.connectApiKey(apiKey) }, enabled = apiKey.isNotBlank() && !state.connecting, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connect_api_key))
                    }
                } else {
                    androidx.compose.material3.TextButton(onClick = { showApiKey = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.use_api_key))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionExpiredCard(onSignIn: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.session_expired_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.session_expired_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Button(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
        }
    }
}

@Composable
private fun Cover(url: String?, title: String, author: String, modifier: Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { GeneratedCover(title, author, Modifier.fillMaxSize()) },
            error = { GeneratedCover(title, author, Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun ResultRow(b: ShelfmarkBook, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().widthIn(max = 900.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(b.cover_url, b.title, b.authors.joinToString(", "), Modifier.width(60.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val meta = listOfNotNull(b.publish_year?.toString(), b.series_name?.let { s -> b.series_position?.let { "$s #${it.toInt()}" } ?: s }, b.language?.uppercase()).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Icon(Icons.Rounded.CloudDownload, stringResource(R.string.get), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActivityCard(items: List<ShelfmarkActivity>) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.downloads), style = MaterialTheme.typography.titleMedium)
            items.forEach { a ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.task.title ?: stringResource(R.string.book), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(statusLabel(a.status)) + (a.task.status_message?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (a.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (a.status == "downloading") {
                    LinearProgressIndicator(progress = { ((a.task.progress ?: 0f) / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
                } else if (a.status in setOf("queued", "resolving", "locating")) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

private fun statusLabel(s: String): Int = when (s) {
    "queued" -> R.string.sm_status_queued
    "resolving", "locating" -> R.string.sm_status_finding
    "downloading" -> R.string.sm_status_downloading
    "complete" -> R.string.sm_status_complete
    "error" -> R.string.sm_status_failed
    "cancelled" -> R.string.sm_status_cancelled
    else -> R.string.sm_status_queued
}

/** Languages offered in the release filter (ISO 639-1, as Shelfmark expects). */
private val releaseLanguages = listOf(
    null to "", "all" to "", "nl" to "Nederlands", "en" to "English",
    "de" to "Deutsch", "fr" to "Français", "es" to "Español",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReleaseSheet(book: ShelfmarkBook, state: DiscoverState, vm: DiscoverViewModel) {
    val releases = state.releases
    val error = state.releasesError
    val onDownload: (Release) -> Unit = vm::download
    val onRequest: () -> Unit = vm::request
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row {
            Cover(book.cover_url, book.title, book.authors.joinToString(", "), Modifier.width(96.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text(book.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                book.publish_year?.let { Text("$it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        book.description?.let {
            Spacer(Modifier.height(12.dp))
            Text(androidx.core.text.HtmlCompat.fromHtml(it, 0).toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.available_downloads), style = MaterialTheme.typography.titleMedium)

        // Source tabs (e.g. All / Prowlarr / Direct download)
        if (state.sources.size > 1) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    androidx.compose.material3.FilterChip(selected = state.releaseSource == null, onClick = { vm.setReleaseSource(null) }, label = { Text(stringResource(R.string.all_sources)) })
                }
                items(state.sources.size) { i ->
                    val src = state.sources[i]
                    androidx.compose.material3.FilterChip(
                        selected = state.releaseSource == src.name,
                        onClick = { vm.setReleaseSource(src.name) },
                        label = { Text(src.display_name ?: src.name) },
                    )
                }
            }
        }
        // Language filter
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(releaseLanguages.size) { i ->
                val (code, label) = releaseLanguages[i]
                androidx.compose.material3.FilterChip(
                    selected = state.releaseLanguage == code,
                    onClick = { vm.setReleaseLanguage(code) },
                    label = { Text(when (code) { null -> stringResource(R.string.lang_default); "all" -> stringResource(R.string.lang_all); else -> label }) },
                    leadingIcon = if (i == 0) ({ Icon(Icons.Rounded.Translate, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
        // Manual query override
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = vm::onManualQuery,
            label = { Text(stringResource(R.string.manual_search)) },
            placeholder = { Text(stringResource(R.string.manual_search_example, "${book.title} ${book.authors.firstOrNull().orEmpty()}")) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = vm::runManualQuery) { Icon(Icons.Rounded.Search, stringResource(R.string.search_releases)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runManualQuery() }),
            supportingText = { Text(stringResource(R.string.manual_search_help)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        when {
            releases == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.sm_searching_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            releases.isEmpty() -> {
                Text(error ?: stringResource(R.string.no_downloads_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequest) { Text(stringResource(R.string.request_book)) }
            }
            else -> releases.take(25).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            listOfNotNull(r.format?.uppercase(), r.language?.uppercase(), r.size, r.indexer ?: r.source).forEach {
                                AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onDownload(r) }) { Text(stringResource(R.string.get)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
