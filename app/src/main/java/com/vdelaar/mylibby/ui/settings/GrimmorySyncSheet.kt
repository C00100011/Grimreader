package com.vdelaar.mylibby.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.SyncSettings
import com.vdelaar.mylibby.data.SyncProgress
import com.vdelaar.mylibby.data.SyncStep
import com.vdelaar.mylibby.ui.appContainer

/** "Last synchronised 5 minutes ago", or a hint that nothing has been synchronised yet. */
@Composable
fun syncSummary(status: SyncSettings): String {
    if (status.lastAt == 0L) return stringResource(R.string.sync_row_never)
    return stringResource(R.string.sync_row_last, relativeTime(status.lastAt))
}

@Composable
private fun relativeTime(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Everything about the Grimmory synchronisation in one place, with a button to run it right now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrimmorySyncSheet(onDismiss: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val progress by c.librarySync.progress.collectAsStateWithLifecycle()
    val status by c.settings.syncStatus.collectAsStateWithLifecycle()
    val server by c.settings.server.collectAsStateWithLifecycle()
    val pending by produceState(0, progress.running) { value = runCatching { c.auth.unsyncedChanges() }.getOrDefault(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sync_sheet_title), style = MaterialTheme.typography.titleLarge)

            Fact(stringResource(R.string.sync_server), server.grimmoryUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'))
            if (server.username.isNotBlank()) Fact(stringResource(R.string.sync_user), server.username)
            Fact(
                stringResource(R.string.sync_last),
                if (status.lastAt == 0L) stringResource(R.string.sync_never)
                else DateUtils.formatDateTime(context, status.lastAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) + " · " + relativeTime(status.lastAt),
            )
            if (status.lastAt != 0L) {
                Text(
                    when {
                        status.lastOk -> stringResource(R.string.sync_result_ok)
                        status.lastError != null -> stringResource(R.string.sync_result_failed, status.lastError!!)
                        else -> stringResource(R.string.sync_result_partial)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.lastOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                if (pending == 0) stringResource(R.string.sync_all_sent) else pluralStringResource(R.plurals.sync_pending, pending, pending),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(stringResource(R.string.sync_what_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            listOf(
                R.string.sync_what_progress, R.string.sync_what_highlights, R.string.sync_what_bookmarks, R.string.sync_what_sessions,
                R.string.sync_what_favourites, R.string.sync_what_status, R.string.sync_what_library,
            ).forEach { res ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
            if (status.books > 0) {
                Text(
                    stringResource(R.string.sync_cache, status.books, status.covers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            ProgressLine(progress)
            Button(onClick = { c.librarySync.launchFull(forced = true) }, enabled = !progress.running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                Text(stringResource(R.string.sync_force), modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.sync_force_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProgressLine(p: SyncProgress) {
    if (!p.running) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when (p.step) {
                SyncStep.SENDING, null -> stringResource(R.string.sync_step_sending)
                SyncStep.BOOKS -> if (p.total > 0) stringResource(R.string.sync_step_books, p.done, p.total) else stringResource(R.string.sync_step_books_unknown)
                SyncStep.COVERS -> stringResource(R.string.sync_step_covers, p.done, p.total)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (p.total > 0 && p.step != SyncStep.SENDING) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
