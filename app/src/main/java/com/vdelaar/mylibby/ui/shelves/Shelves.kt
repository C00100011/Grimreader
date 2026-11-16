package com.vdelaar.mylibby.ui.shelves

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.FAVOURITES_SHELF
import com.vdelaar.mylibby.data.MAX_SHELF_NAME
import com.vdelaar.mylibby.data.ShelfCard
import com.vdelaar.mylibby.data.ShelfNameError
import com.vdelaar.mylibby.data.ShelfNameException
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.data.checkShelfName
import com.vdelaar.mylibby.data.cleanShelfName
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.components.BookCover
import com.vdelaar.mylibby.ui.components.EmptyState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val FavouriteRed = Color(0xFFE5484D)

/** The reader's shelves for the Shelves tab and the "Add to shelves" sheet; null while they load. */
class ShelvesViewModel(private val c: AppContainer) : ViewModel() {
    val cards: StateFlow<List<ShelfCard>?> =
        c.shelves.observeCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Makes a shelf; the reason when the name is not allowed. */
    suspend fun create(name: String): ShelfNameError? = try { c.shelves.create(name); null } catch (e: ShelfNameException) { e.error }

    suspend fun rename(id: Long, name: String): ShelfNameError? = try { c.shelves.rename(id, name); null } catch (e: ShelfNameException) { e.error }

    fun delete(id: Long) { viewModelScope.launch { c.shelves.delete(id) } }
}

@Composable
fun shelfErrorText(error: ShelfNameError): String = when (error) {
    ShelfNameError.BLANK -> stringResource(R.string.shelf_error_blank)
    ShelfNameError.TOO_LONG -> stringResource(R.string.shelf_error_long, MAX_SHELF_NAME)
    ShelfNameError.RESERVED -> stringResource(R.string.shelf_error_reserved)
    ShelfNameError.DUPLICATE -> stringResource(R.string.shelf_error_duplicate)
}

/** Asks for a shelf name (new shelf or rename). [existing] are the other shelves' names; [onConfirm] returns why the name was refused, if it was. */
@Composable
fun ShelfNameDialog(
    title: String,
    confirm: String,
    initial: String,
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> ShelfNameError?,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var refused by remember { mutableStateOf<ShelfNameError?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val problem = checkShelfName(text, existing)
    // "Blank" is only worth saying once the reader has typed something.
    val shown = refused ?: problem?.takeIf { touched && (it != ShelfNameError.BLANK || text.isNotEmpty()) }
    fun submit() {
        if (problem != null) { touched = true; return }
        scope.launch { val err = onConfirm(text); if (err == null) onDismiss() else refused = err }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; touched = true; refused = null },
                label = { Text(stringResource(R.string.shelf_name_label)) },
                singleLine = true,
                isError = shown != null,
                supportingText = { shown?.let { Text(shelfErrorText(it)) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = problem == null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Delete this shelf?": says whether it goes from Grimmory too. */
@Composable
fun DeleteShelfDialog(name: String, synced: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelf_delete_q)) },
        text = { Text(stringResource(if (synced) R.string.shelf_delete_body_synced else R.string.shelf_delete_body, name)) },
        confirmButton = { TextButton(onClick = { onDelete(); onDismiss() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// The Shelves tab ------------------------------------------------------------------------------

/**
 * The shelves as cards: Favourites first, then the reader's own (and public ones from Grimmory). [query] narrows them by name.
 * [selectedId] marks the open shelf where a shelf and its books are side by side.
 */
@Composable
fun ShelvesTab(
    vm: ShelvesViewModel,
    query: String,
    selectedId: Long?,
    compactColumns: Boolean,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val all = cards ?: return
    var renaming by remember { mutableStateOf<ShelfCard?>(null) }
    var deleting by remember { mutableStateOf<ShelfCard?>(null) }
    val own = all.filter { !it.favourites }
    val favouritesName = stringResource(R.string.favorites)
    val q = query.trim()
    val shown = all.filter { q.isEmpty() || (if (it.favourites) favouritesName else it.name).contains(q, ignoreCase = true) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compactColumns) 148.dp else 164.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shown, key = { it.id }) { card ->
            ShelfCardView(
                card = card,
                name = if (card.favourites) favouritesName else card.name,
                selected = selectedId == card.id,
                onClick = { onOpen(card.id) },
                onRename = if (card.favourites || card.shared) null else ({ renaming = card }),
                onDelete = if (card.favourites || card.shared) null else ({ deleting = card }),
            )
        }
        if (q.isNotEmpty() && shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState("🔎", stringResource(R.string.shelves_none_found), stringResource(R.string.no_match_for, q))
        }
        if (own.isEmpty() && q.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(
                "🔖", stringResource(R.string.shelves_empty_title), stringResource(R.string.shelves_empty_body),
                action = stringResource(R.string.shelf_new), onAction = onNew,
            )
        }
    }

    renaming?.let { card ->
        ShelfNameDialog(
            title = stringResource(R.string.shelf_rename),
            confirm = stringResource(R.string.shelf_rename_action),
            initial = card.name,
            existing = own.filter { it.id != card.id && !it.shared }.map { it.name },
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.rename(card.id, name).also { if (it == null) onMessage(str(R.string.shelf_renamed, cleanShelfName(name))) } },
        )
    }
    deleting?.let { card ->
        DeleteShelfDialog(card.name, card.synced, onDismiss = { deleting = null }, onDelete = {
            vm.delete(card.id)
            onMessage(str(R.string.shelf_deleted, card.name))
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCardView(
    card: ShelfCard,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onRename != null && onDelete != null
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).combinedClickable(
            onClickLabel = name,
            onLongClickLabel = if (hasMenu) stringResource(R.string.shelf_options) else null,
            role = Role.Button,
            onLongClick = if (hasMenu) ({ menu = true }) else null,
            onClick = onClick,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            ShelfCovers(card)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluralStringResource(R.plurals.books_count, card.count, card.count),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        if (card.shared) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Group, stringResource(R.string.shelf_shared), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (card.synced && !card.favourites) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Cloud, stringResource(R.string.shelf_synced), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (hasMenu) Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.shelf_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.shelf_rename)) }, onClick = { menu = false; onRename?.invoke() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.shelf_delete)) }, onClick = { menu = false; onDelete?.invoke() })
                    }
                }
            }
        }
    }
}

/** The newest covers of a shelf, fanned out; an icon while it is empty. */
@Composable
private fun ShelfCovers(card: ShelfCard) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().height(124.dp).clip(shape)
            .background(if (card.favourites) FavouriteRed.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (card.covers.isEmpty()) {
            Icon(
                if (card.favourites) Icons.Rounded.Favorite else Icons.Outlined.CollectionsBookmark, null,
                Modifier.size(44.dp), tint = if (card.favourites) FavouriteRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
            )
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val n = card.covers.size
            val coverH = maxHeight - 22.dp
            val coverW = coverH * 2f / 3f
            val step = if (n > 1) minOf(coverW * .55f, (maxWidth - coverW - 24.dp) / (n - 1)) else 0.dp
            val startX = (maxWidth - (coverW + step * (n - 1))) / 2
            // Oldest first, so the newest is in front (and to the right).
            for (i in n - 1 downTo 0) {
                BookCover(card.covers[i], Modifier.height(coverH).offset(x = startX + step * (n - 1 - i), y = (maxHeight - coverH) / 2), elevation = 3.dp)
            }
            if (card.favourites) Surface(shape = CircleShape, color = Color.White.copy(alpha = .92f), shadowElevation = 2.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                Icon(Icons.Rounded.Favorite, null, tint = FavouriteRed, modifier = Modifier.padding(4.dp).size(14.dp))
            }
        }
    }
}

// Add to shelves --------------------------------------------------------------------------------

/**
 * Pick the shelves [bookIds] belong on. Every tap takes effect at once; a shelf holding only some of the books shows a
 * dash, and tapping it puts them all on it. Favourites is the first row, and "New shelf…" makes one and puts the books on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToShelvesSheet(bookIds: Set<Long>, onDismiss: () -> Unit, onMessage: (String) -> Unit = {}) {
    val c = appContainer()
    val vm = com.vdelaar.mylibby.ui.appViewModel { ShelvesViewModel(it) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val membership by remember(bookIds) { c.shelves.observeMembership(bookIds) }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val favouritesName = stringResource(R.string.favorites)

    fun toggle(card: ShelfCard) {
        val on = (membership[card.id] ?: 0) == bookIds.size
        scope.launch {
            when {
                card.favourites -> c.library.setFavourites(bookIds, !on)
                on -> c.shelves.remove(card.id, bookIds)
                else -> c.shelves.add(card.id, bookIds)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(stringResource(R.string.shelves_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                listItems(cards.orEmpty().filter { !it.shared }, key = { it.id }) { card ->
                    val have = membership[card.id] ?: 0
                    val state = when { have == 0 -> ToggleableState.Off; have >= bookIds.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                    val name = if (card.favourites) favouritesName else card.name
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClickLabel = name) { toggle(card) }.padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = state, onClick = null, modifier = Modifier.padding(12.dp))
                        Icon(
                            if (card.favourites) Icons.Rounded.Favorite else Icons.Rounded.Bookmarks, null,
                            tint = if (card.favourites) FavouriteRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(card.count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { creating = true }.padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.padding(12.dp).size(24.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) }
                        Icon(Icons.Rounded.Bookmarks, null, tint = Color.Transparent)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.shelves_new_row), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { scope.launch { sheetState.hide(); onDismiss() } }) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }

    if (creating) {
        ShelfNameDialog(
            title = stringResource(R.string.shelf_new),
            confirm = stringResource(R.string.shelf_create),
            initial = "",
            existing = cards.orEmpty().filter { !it.favourites && !it.shared }.map { it.name },
            onDismiss = { creating = false },
            onConfirm = { name ->
                try {
                    val shelf = c.shelves.create(name)
                    c.shelves.add(shelf.localId, bookIds)
                    onMessage(str(R.string.shelf_created, shelf.name))
                    null
                } catch (e: ShelfNameException) { e.error }
            },
        )
    }
}

/** The reader's shelves a book is on, as chips that open the shelf, and a chip to add it to more. Nothing while it is on none. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShelfChips(bookId: Long, navigator: com.vdelaar.mylibby.ui.navigation.AppNavigator, onAdd: () -> Unit) {
    val c = appContainer()
    val shelves by remember(bookId) { c.shelves.observeShelvesOfBook(bookId) }.collectAsStateWithLifecycle(emptyList())
    if (shelves.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.shelves_action), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        shelves.forEach { shelf ->
            androidx.compose.material3.AssistChip(
                onClick = { navigator.shelf(shelf.localId) },
                label = { Text(shelf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Bookmarks, null, Modifier.size(18.dp)) },
            )
        }
        androidx.compose.material3.AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.add_to_shelf)) },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
        )
    }
}
