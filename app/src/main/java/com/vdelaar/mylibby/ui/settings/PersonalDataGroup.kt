package com.vdelaar.mylibby.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.ImportPreview
import com.vdelaar.mylibby.data.ImportResult
import com.vdelaar.mylibby.data.InvalidExportException
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Export and import of the reader's own data (stats, read list, favourites, highlights, bookmarks). */
@Composable
fun PersonalDataGroup(onMessage: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var working by remember { mutableStateOf(false) }

    val exported = stringResource(R.string.export_done)
    val exportFailed = stringResource(R.string.export_failed)
    val invalid = stringResource(R.string.import_invalid)
    val importFailed = stringResource(R.string.data_import_failed)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            runCatching {
                val text = c.personalData.export()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }.onSuccess { onMessage(exported) }.onFailure { onMessage(exportFailed) }
            working = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }
                c.personalData.preview(text)
            }.onSuccess { preview = it }.onFailure { onMessage(if (it is InvalidExportException) invalid else importFailed) }
            working = false
        }
    }

    SettingsGroup(stringResource(R.string.my_data)) {
        SettingsRow(stringResource(R.string.export_data), stringResource(R.string.export_data_body), Icons.Rounded.FileUpload, onClick = {
            if (!working) exportLauncher.launch("grimreader-${LocalDate.now()}.json")
        })
        SettingsRow(stringResource(R.string.import_data), stringResource(R.string.import_data_body), Icons.Rounded.FileDownload, onClick = {
            if (!working) importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        })
    }

    preview?.let { p ->
        fun apply(sync: Boolean) {
            preview = null
            scope.launch {
                working = true
                runCatching { c.personalData.import(p, sync) }
                    .onSuccess { result = it }
                    .onFailure { onMessage(importFailed) }
                working = false
            }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.import_counts, p.sessions, p.books, p.favourites, p.highlights, p.bookmarks))
                    if (!app.noServer) {
                        Text(stringResource(R.string.import_sync_q), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.import_sync_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { apply(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_sync_yes)) }
                        TextButton(onClick = { apply(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_sync_no)) }
                    }
                }
            },
            confirmButton = { if (app.noServer) TextButton(onClick = { apply(false) }) { Text(stringResource(R.string.import_apply)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.import_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.import_done_body, r.sessionsAdded, r.favouritesAdded, r.highlightsAdded, r.bookmarksAdded))
                    if (r.booksNotFound > 0) Text(stringResource(R.string.import_not_found, r.booksNotFound), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.queuedForSync) Text(stringResource(R.string.import_queued), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}
