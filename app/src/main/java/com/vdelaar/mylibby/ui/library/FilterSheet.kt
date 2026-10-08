package com.vdelaar.mylibby.ui.library

import androidx.compose.foundation.layout.Arrangement
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.core.model.FilterOptions
import com.vdelaar.mylibby.core.model.LibraryFilter
import com.vdelaar.mylibby.core.model.ReadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filter: LibraryFilter,
    options: FilterOptions,
    onApply: (LibraryFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(filter) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = LibraryFilter(search = filter.search, sort = filter.sort, dir = filter.dir) }) { Text(stringResource(R.string.reset)) }
            }
            ChipSection(stringResource(R.string.filter_status), ReadStatus.all.map { it to ReadStatus.label(it) }, draft.statuses) { draft = draft.copy(statuses = it) }
            ChipSection(stringResource(R.string.filter_genre), options.genres.map { it.first to "${it.first} (${it.second})" }, draft.genres, searchable = true) { draft = draft.copy(genres = it) }
            ChipSection(stringResource(R.string.filter_author), options.authors.map { it.first to "${it.first} (${it.second})" }, draft.authors, searchable = true) { draft = draft.copy(authors = it) }
            ChipSection(stringResource(R.string.filter_series), options.series.map { it.first to "${it.first} (${it.second})" }, draft.series, searchable = true) { draft = draft.copy(series = it) }
            ChipSection(stringResource(R.string.filter_language), options.languages.map { it.first to "${it.second} (${it.third})" }, draft.languages) { draft = draft.copy(languages = it) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
                OutlinedButton(onClick = { onClear(); onDismiss() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.clear_all)) }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onApply(draft); onDismiss() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.show_results)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    title: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    searchable: Boolean = false,
    onChange: (Set<String>) -> Unit,
) {
    if (items.isEmpty()) return
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (searchable && items.size > 12) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_in, title.lowercase())) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
    val filtered = items.filter { query.isBlank() || it.first.contains(query, ignoreCase = true) }
    // Selected first so they're always visible.
    val ordered = filtered.sortedByDescending { it.first in selected }
    val shown = if (showAll || query.isNotBlank()) ordered.take(150) else ordered.take(16)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        shown.forEach { (value, label) ->
            val isSelected = value in selected
            FilterChip(
                selected = isSelected,
                onClick = { onChange(if (isSelected) selected - value else selected + value) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    if (!showAll && query.isBlank() && filtered.size > 16) {
        TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.show_all_n, filtered.size)) }
    }
}
