package com.vdelaar.mylibby.ui.opds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.network.OpdsEntry
import com.vdelaar.mylibby.core.network.OpdsFeed
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.data.OpdsException
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.components.ErrorState
import com.vdelaar.mylibby.ui.components.GeneratedCover
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpdsState(
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val entries: List<OpdsEntry> = emptyList(),
    val nextUrl: String? = null,
    val loadingMore: Boolean = false,
    val searchable: Boolean = false,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<OpdsEntry>? = null,
    val selected: OpdsEntry? = null,
    val downloading: Set<String> = emptySet(),
    /** Books added this session: entry id -> local book id. */
    val added: Map<String, Long> = emptyMap(),
    val message: String? = null,
    val openBookId: Long? = null,
)

class OpdsViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    val state = MutableStateFlow(OpdsState())
    private var feed: OpdsFeed? = null
    private var root: OpdsFeed? = null
    private var searchJob: Job? = null

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val r = c.opds.root().also { root = it }
                if (url.isBlank()) r else c.opds.fetch(url)
            }.onSuccess { f ->
                feed = f
                state.update { it.copy(loading = false, title = f.title, entries = f.entries, nextUrl = f.nextUrl, searchable = root?.let { r -> r.searchTemplate != null || r.searchDescriptionUrl != null } == true) }
            }.onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun loadMore() {
        val next = state.value.nextUrl ?: return
        if (state.value.loadingMore) return
        state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { c.opds.fetch(next) }
                .onSuccess { f -> state.update { s -> s.copy(loadingMore = false, entries = (s.entries + f.entries).distinctBy { e -> e.id + e.navigationUrl }, nextUrl = f.nextUrl) } }
                .onFailure { state.update { s -> s.copy(loadingMore = false, nextUrl = null) } }
        }
    }

    fun onQuery(q: String) = state.update { it.copy(searchQuery = q) }

    fun search() {
        val q = state.value.searchQuery.trim()
        if (q.isBlank()) return clearSearch()
        searchJob?.cancel()
        state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            runCatching {
                val r = root ?: c.opds.root().also { root = it }
                val u = c.opds.searchUrl(r, q) ?: throw OpdsException("This catalog has no search")
                c.opds.fetch(u)
            }.onSuccess { f -> state.update { it.copy(searching = false, searchResults = f.entries, nextUrl = f.nextUrl) } }
                .onFailure { e -> state.update { it.copy(searching = false, searchResults = emptyList(), message = e.message) } }
        }
    }

    fun clearSearch() = state.update { it.copy(searchQuery = "", searchResults = null, nextUrl = feed?.nextUrl) }

    fun select(e: OpdsEntry?) = state.update { it.copy(selected = e) }

    fun add(entry: OpdsEntry, format: com.vdelaar.mylibby.core.network.OpdsAcquisition) {
        if (entry.id in state.value.downloading) return
        state.update { it.copy(downloading = it.downloading + entry.id) }
        viewModelScope.launch {
            runCatching { c.opds.download(entry, format) }
                .onSuccess { b -> state.update { it.copy(downloading = it.downloading - entry.id, added = it.added + (entry.id to b.id), message = c.appContext.getString(R.string.opds_added, b.title)) } }
                .onFailure { e -> state.update { it.copy(downloading = it.downloading - entry.id, message = e.message ?: c.appContext.getString(R.string.something_wrong)) } }
        }
    }

    fun consumeMessage() = state.update { it.copy(message = null) }
}

/** Browse and search an OPDS catalog; books are added to the library on this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(url: String, navigator: AppNavigator) {
    val vm = appViewModel(key = "opds-$url") { OpdsViewModel(it, url) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { focus.requestFocus() } }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.entries.size, state.searchResults) { if (nearEnd && state.nextUrl != null) vm.loadMore() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) TextField(
                        value = state.searchQuery,
                        onValueChange = vm::onQuery,
                        placeholder = { Text(stringResource(R.string.opds_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                        modifier = Modifier.androidx_focus(focus),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    ) else Text(state.title.ifBlank { stringResource(R.string.opds_catalog) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (state.searchable) IconButton(onClick = { if (searchOpen) { searchOpen = false; vm.clearSearch() } else searchOpen = true }) {
                        Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, stringResource(R.string.opds_search))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val shown = state.searchResults ?: state.entries
            when {
                state.loading || state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.entries.isEmpty() -> ErrorState(state.error!!, onRetry = vm::load)
                shown.isEmpty() -> EmptyState("📚", stringResource(R.string.opds_empty), stringResource(R.string.opds_empty_body))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id + "|" + it.navigationUrl + "|" + it.title }) { e ->
                        if (e.isBook) BookRow(e, added = e.id in state.added) { vm.select(e) }
                        else FolderRow(e) { e.navigationUrl?.let(navigator::opds) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                    if (state.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                }
            }
        }
    }

    state.selected?.let { e ->
        OpdsBookSheet(
            entry = e,
            busy = e.id in state.downloading,
            addedBookId = state.added[e.id],
            onAdd = { acq -> vm.add(e, acq) },
            onOpen = { id -> vm.select(null); navigator.openBook(id) },
            onDismiss = { vm.select(null) },
        )
    }
}

@Composable
private fun FolderRow(e: OpdsEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun BookRow(e: OpdsEntry, added: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OpdsCover(e, Modifier.width(56.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.authors.isNotEmpty()) Text(e.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val formats = e.acquisitions.mapNotNull { it.format }.distinct()
            Text(
                if (added) stringResource(R.string.opds_in_library) else formats.joinToString(" · ").ifEmpty { stringResource(R.string.opds_no_readable) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OpdsCover(e: OpdsEntry, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Surface(modifier.aspectRatio(2f / 3f), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 3.dp) {
        val url = e.thumbnailUrl ?: e.coverUrl
        if (url == null) GeneratedCover(e.title, e.authors.joinToString(", "), Modifier.fillMaxSize())
        else SubcomposeAsyncImage(
            model = url, contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            loading = { GeneratedCover(e.title, e.authors.joinToString(", "), Modifier.fillMaxSize()) },
            error = { GeneratedCover(e.title, e.authors.joinToString(", "), Modifier.fillMaxSize()) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpdsBookSheet(entry: OpdsEntry, busy: Boolean, addedBookId: Long?, onAdd: (com.vdelaar.mylibby.core.network.OpdsAcquisition) -> Unit, onOpen: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OpdsCover(entry.copy(thumbnailUrl = entry.coverUrl ?: entry.thumbnailUrl), Modifier.width(110.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                    if (entry.authors.isNotEmpty()) Text(entry.authors.joinToString(", "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    entry.language?.let { Text(it.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            entry.summary?.let { s ->
                val text = HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
            when {
                addedBookId != null -> Button(onClick = { onOpen(addedBookId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.opds_open_book)) }
                entry.readable.isEmpty() -> Text(stringResource(R.string.opds_no_readable_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                busy -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.opds_downloading))
                }
                else -> entry.readable.distinctBy { it.format }.forEach { acq ->
                    Button(onClick = { onAdd(acq) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text(stringResource(R.string.opds_add_format, acq.format.orEmpty())) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun Modifier.androidx_focus(r: androidx.compose.ui.focus.FocusRequester) = this.then(Modifier.focusRequester(r))
