package com.vdelaar.mylibby.ui.reader

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.R
import kotlin.math.roundToInt
import com.vdelaar.mylibby.ai.AiStatus
import com.vdelaar.mylibby.ai.BookChatState
import com.vdelaar.mylibby.ai.ChatTurn
import com.vdelaar.mylibby.ai.DownloadUi

/**
 * The height of a sheet's body: [fraction] of the room the sheet is given, but never so tall that the sheet's top edge reaches the
 * status bar (it stops [SHEET_TOP_MARGIN] below it).
 *
 * Material's sheet adds the status bar's height to itself once its top edge slips under the bar, which makes the sheet taller, which moves
 * its top edge up, and so on. A body that is only "a fraction of the room" can end up at exactly that edge when the room is small (a keyboard
 * up, a short window, a thick cutout), and the sheet then fights itself while it is pulled up. With the second limit the sheet's height
 * stops depending on that padding: whatever it adds to the sheet comes off the body again.
 */
@Composable
internal fun Modifier.sheetBodyHeight(fraction: Float): Modifier {
    val density = LocalDensity.current
    val clear = WindowInsets.safeDrawing.getTop(density) + with(density) { SHEET_TOP_MARGIN.roundToPx() }
    return layout { measurable, constraints ->
        val room = constraints.maxHeight
        val height = minOf((room * fraction).roundToInt(), room - clear).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private val SHEET_TOP_MARGIN = 16.dp

/**
 * Lists inside a bottom sheet do not stretch at their ends: a pull past the top of the sheet would otherwise be taken by the
 * list's stretch effect while the sheet itself is also reacting to the same drag.
 */
@Composable
internal fun NoOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)
}

/** Ask the book: questions answered on the phone by the local model, using only what has been read so far. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskBookSheet(
    chat: BookChatState,
    chapter: String,
    percent: Int,
    onAsk: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onClearConversation: () -> Unit,
    onDeleteIndex: () -> Unit,
    onDismiss: () -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    var menu by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = chat.messages.size + if (chat.answering) 1 else 0
    LaunchedEffect(rows, chat.partial) { if (rows > 0) listState.animateScrollToItem(rows - 1) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.sheetBodyHeight(.92f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stringResource(R.string.ask_about_book), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.ask_book_scope, percent),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ask_clear_conversation)) }, onClick = { menu = false; onClearConversation() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ask_rebuild_notes)) }, onClick = { menu = false; onDeleteIndex() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (chat.status) {
                AiStatus.DOWNLOADABLE, AiStatus.DOWNLOADING -> DownloadPrompt(chat.download ?: DownloadUi(null).takeIf { chat.status == AiStatus.DOWNLOADING }, chat.error, onDownload)
                AiStatus.UNAVAILABLE -> Text(stringResource(R.string.ask_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                AiStatus.AVAILABLE -> {
                    IndexStatus(chat, onRetry)
                    NoOverscroll {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (chat.messages.isEmpty() && !chat.answering) item { Intro(chapter) }
                            items(chat.messages) { Bubble(it) }
                            if (chat.answering) item { Bubble(ChatTurn(false, chat.partial.ifBlank { stringResource(R.string.ask_thinking) }), dim = chat.partial.isBlank()) }
                        }
                    }
                    if (chat.nothingRead) Text(stringResource(R.string.ask_nothing_read), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    chat.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text(stringResource(R.string.ask_placeholder)) },
                        enabled = !chat.answering,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank()) { onAsk(question); question = "" } }),
                        trailingIcon = {
                            IconButton(onClick = { onAsk(question); question = "" }, enabled = question.isNotBlank() && !chat.answering) {
                                Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.ask_send))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Intro(chapter: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.ask_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ask_example_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ask_example_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chapter.isNotBlank()) Text(stringResource(R.string.ask_you_are_in, chapter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IndexStatus(chat: BookChatState, onRetry: () -> Unit) {
    if (chat.indexing && chat.indexTotal > 0 && chat.indexed < chat.indexTotal) {
        Text(stringResource(R.string.ask_preparing, chat.indexed, chat.indexTotal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { chat.indexed.toFloat() / chat.indexTotal }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    } else if (chat.paused != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ask_paused), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
        }
    }
}

@Composable
private fun DownloadPrompt(download: DownloadUi?, error: String?, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.ask_download_explain), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        if (download == null) {
            Button(onClick = onDownload) { Text(stringResource(R.string.ask_download)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        } else {
            val f = download.fraction
            if (f != null && f > 0f) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // the system has not said how far it is
            val label = when {
                f != null && f > 0f -> stringResource(R.string.ask_downloading, (f * 100).toInt())
                download.bytes > 0 -> stringResource(R.string.ask_downloading_bytes, android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, download.bytes))
                else -> stringResource(R.string.ask_downloading_unknown)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.ask_downloading_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn, dim: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (turn.fromReader) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (turn.fromReader) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                turn.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) .6f else 1f),
            )
        }
    }
}
