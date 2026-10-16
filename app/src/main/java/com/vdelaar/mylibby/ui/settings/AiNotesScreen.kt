package com.vdelaar.mylibby.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.ai.AiStatus
import com.vdelaar.mylibby.core.database.BookNotesRow
import com.vdelaar.mylibby.core.database.NoteRow
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import com.vdelaar.mylibby.ui.reader.NoOverscroll
import com.vdelaar.mylibby.ui.reader.sheetBodyHeight
import kotlinx.coroutines.launch

/** Everything the on-device AI keeps: the model status, and the notes made per book, which can be read and deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiNotesScreen(navigator: AppNavigator) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by c.db.bookChunks().observeStats().collectAsStateWithLifecycle(emptyList())
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val gone = stringResource(R.string.ai_notes_book_gone)
    LaunchedEffect(rows.map { it.bookId }, gone) {
        // A book removed from this phone (or from the library) can leave notes behind; they stay listed so they can be deleted.
        titles = rows.associate { r ->
            r.bookId to (c.db.books().get(r.bookId)?.title ?: c.db.localBooks().get(r.bookId)?.title ?: gone)
        }
    }
    // On a phone that cannot run the model there is nothing to explain or fill in; only the status block is useful.
    val canChat by produceState(true) { value = c.localAi.status() != AiStatus.UNAVAILABLE }
    var reading by remember { mutableStateOf<BookNotesRow?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_notes)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp), modifier = Modifier.navigationBarsPadding()) {
            item { OnDeviceAiGroup(c) }
            if (canChat || rows.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.ai_notes_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rows.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pluralStringResource(R.plurals.ai_notes_summary, rows.size, rows.size, Formatter.formatShortFileSize(context, rows.sumOf { it.bytes })),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.ai_notes_delete_all)) }
                        }
                    }
                }
            }
            if (rows.isEmpty() && canChat) item {
                Text(stringResource(R.string.ai_notes_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            items(rows, key = { it.bookId }) { r ->
                Row(Modifier.fillMaxWidth().clickable { reading = r }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(titles[r.bookId] ?: "…", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(
                            stringResource(R.string.ai_notes_row, r.summarised, r.total, Formatter.formatShortFileSize(context, r.bytes)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { scope.launch { c.bookChat.clear(r.bookId) } }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.ai_notes_delete_for, titles[r.bookId] ?: ""))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    reading?.let { r ->
        NotesSheet(
            title = titles[r.bookId].orEmpty(), row = r,
            load = { c.db.bookChunks().notes(r.bookId) },
            onDelete = { scope.launch { c.bookChat.clear(r.bookId) }; reading = null },
            onDismiss = { reading = null },
        )
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text(stringResource(R.string.ai_notes_delete_all)) },
        text = { Text(stringResource(R.string.ai_notes_delete_all_body)) },
        confirmButton = { TextButton(onClick = { scope.launch { c.bookChat.clearAll() }; confirmAll = false }) { Text(stringResource(R.string.ai_notes_delete_all)) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The notes the model wrote for one book, section by section, exactly as it will use them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(title: String, row: BookNotesRow, load: suspend () -> List<NoteRow>, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var notes by remember { mutableStateOf<List<NoteRow>?>(null) }
    LaunchedEffect(row.bookId) { notes = load() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.sheetBodyHeight(.9f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                stringResource(R.string.ai_notes_sheet_sub, row.summarised, row.total),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.ai_notes_delete_book)) }
            val list = notes
            NoOverscroll {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (list != null && list.isEmpty()) item { Text(stringResource(R.string.ai_notes_sheet_empty), style = MaterialTheme.typography.bodyMedium) }
                    items(list.orEmpty(), key = { it.idx }) { n ->
                        Column {
                            Text(n.section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(n.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
