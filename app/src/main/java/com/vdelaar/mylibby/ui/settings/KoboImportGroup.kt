package com.vdelaar.mylibby.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.KoboImportResult
import com.vdelaar.mylibby.data.KoboPreview
import com.vdelaar.mylibby.data.NotKoboDatabaseException
import com.vdelaar.mylibby.data.formatShortDuration
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Experimental ways to bring reading sessions in: from a Kobo e-reader, and from Grimmory. */
@Composable
fun KoboImportGroup(onMessage: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    var help by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<KoboPreview?>(null) }
    var result by remember { mutableStateOf<KoboImportResult?>(null) }
    var pulling by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val notFound = stringResource(R.string.kobo_not_found)
    val readFailed = stringResource(R.string.kobo_read_failed)

    fun load(block: suspend () -> KoboPreview) {
        help = false
        scope.launch {
            working = true
            runCatching { block() }
                .onSuccess { preview = it }
                .onFailure { onMessage(if (it is NotKoboDatabaseException) notFound else readFailed) }
            working = false
        }
    }
    val drive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) load { c.koboImport.previewDrive(uri) }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) load { c.koboImport.preview(uri) }
    }

    SettingsGroup(stringResource(R.string.experimental_group)) {
        SettingsRow(stringResource(R.string.kobo_import), stringResource(R.string.kobo_import_body), Icons.Rounded.TabletAndroid, onClick = { if (!working) help = true })
        if (!app.noServer) {
            val p = pulling
            SettingsRow(
                stringResource(R.string.sessions_pull),
                if (p != null) stringResource(R.string.sessions_pull_progress, p.first, p.second) else stringResource(R.string.sessions_pull_body),
                Icons.Rounded.CloudDownload,
                onClick = {
                    if (pulling != null) return@SettingsRow
                    scope.launch {
                        pulling = 0 to 0
                        val r = runCatching { c.stats.pullAllSessions { done, total -> pulling = done to total } }.getOrNull()
                        pulling = null
                        onMessage(
                            when {
                                r == null -> readFailed
                                r.failed > 0 -> com.vdelaar.mylibby.core.str(R.string.sessions_pull_failed, r.added, r.failed)
                                else -> com.vdelaar.mylibby.core.str(R.string.sessions_pull_done, r.added, r.books)
                            }
                        )
                    }
                },
            )
        }
    }

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.kobo_help_title)) },
            text = { Text(stringResource(R.string.kobo_help_body), modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { drive.launch(null) }) { Text(stringResource(R.string.kobo_choose_drive)) } },
            dismissButton = { TextButton(onClick = { file.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.kobo_choose_file)) } },
        )
    }

    preview?.let { p ->
        var send by remember(p) { mutableStateOf(!app.noServer) }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.kobo_preview_title)) },
            text = { KoboPreviewBody(p, canSend = !app.noServer, send = send, onSend = { send = it }) },
            confirmButton = {
                if (p.newSessions > 0) TextButton(onClick = {
                    preview = null
                    scope.launch {
                        working = true
                        runCatching { c.koboImport.import(p, send) }.onSuccess { result = it }.onFailure { onMessage(readFailed) }
                        working = false
                    }
                }) { Text(stringResource(R.string.kobo_do_import)) }
                else TextButton(onClick = { preview = null }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { if (p.newSessions > 0) TextButton(onClick = { preview = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.kobo_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pluralStringResource(R.plurals.kobo_done_body, r.added, r.added))
                    if (r.sentToGrimmory) Text(stringResource(R.string.kobo_done_sent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.queued) Text(stringResource(R.string.kobo_done_queued), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun KoboPreviewBody(p: KoboPreview, canSend: Boolean, send: Boolean, onSend: (Boolean) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    fun fmt(epochSeconds: Long?) = epochSeconds?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(date) }.orEmpty()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (p.newSessions == 0) {
            Text(stringResource(R.string.kobo_preview_none))
        } else {
            Text(stringResource(R.string.kobo_preview_counts, p.newSessions, p.matches.size, formatShortDuration(p.newSeconds), fmt(p.firstAt), fmt(p.lastAt)))
            p.matches.take(6).forEach { m ->
                Text(
                    pluralStringResource(R.plurals.kobo_book_line, m.sessions.size, m.book.title, m.sessions.size, formatShortDuration(m.seconds)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (p.matches.size > 6) Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.alreadyHere > 0) Text(pluralStringResource(R.plurals.kobo_preview_already, p.alreadyHere, p.alreadyHere), style = MaterialTheme.typography.bodySmall)
        if (p.notFound.isNotEmpty()) {
            Text(
                stringResource(R.string.kobo_preview_missing, p.notFound.size, p.notFound.take(5).joinToString(", ") + if (p.notFound.size > 5) "…" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canSend && p.newSessions > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.kobo_send_switch), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.kobo_send_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = send, onCheckedChange = onSend)
            }
        }
    }
}
