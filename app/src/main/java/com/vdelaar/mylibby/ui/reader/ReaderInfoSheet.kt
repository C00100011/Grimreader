package com.vdelaar.mylibby.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.InfoItem
import com.vdelaar.mylibby.core.datastore.ProgressScope
import com.vdelaar.mylibby.core.datastore.ReaderSettings

/** Header & footer: what to show at the top and bottom of the page, and what the progress slider covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderInfoSheet(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    val theme = settings.effectiveTheme(isSystemInDarkTheme())
    val sample = InfoData(
        bookTitle = "The Time Machine",
        author = "H. G. Wells",
        location = RelocateEvent(
            fraction = 0.36, tocLabel = "Chapter II", pageInSection = 3, pagesInSection = 14,
            bookPage = 112, bookPages = 540, locationCurrent = 1234, locationTotal = 8900,
        ),
        chapterMinutes = 12.0, bookMinutes = 124.0, clock = "14:32", battery = 84,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, scrimColor = Color.Black.copy(alpha = .2f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.info_header_footer), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            // Preview in the reader's colours
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(theme.bg)).padding(vertical = 14.dp)) {
                Column {
                    AmbientInfo(settings, sample, Color(theme.fg), Modifier.fillMaxWidth().height(86.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.info_top), style = MaterialTheme.typography.titleMedium)
            SlotPicker(stringResource(R.string.info_slot_left), settings.headerLeft) { v -> update { it.copy(headerLeft = v) } }
            SlotPicker(stringResource(R.string.info_slot_center), settings.headerCenter) { v -> update { it.copy(headerCenter = v) } }
            SlotPicker(stringResource(R.string.info_slot_right), settings.headerRight) { v -> update { it.copy(headerRight = v) } }
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.info_bottom), style = MaterialTheme.typography.titleMedium)
            SlotPicker(stringResource(R.string.info_slot_left), settings.footerLeft) { v -> update { it.copy(footerLeft = v) } }
            SlotPicker(stringResource(R.string.info_slot_center), settings.footerCenter) { v -> update { it.copy(footerCenter = v) } }
            SlotPicker(stringResource(R.string.info_slot_right), settings.footerRight) { v -> update { it.copy(footerRight = v) } }
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.info_progress_bar), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.info_progress_bar_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ProgressScope.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = settings.progressScope == s,
                        onClick = { update { it.copy(progressScope = s) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ProgressScope.entries.size),
                    ) { Text(stringResource(if (s == ProgressScope.BOOK) R.string.info_scope_book else R.string.info_scope_chapter)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val d = ReaderSettings()
                update {
                    it.copy(
                        headerLeft = d.headerLeft, headerCenter = d.headerCenter, headerRight = d.headerRight,
                        footerLeft = d.footerLeft, footerCenter = d.footerCenter, footerRight = d.footerRight,
                        progressScope = d.progressScope,
                    )
                }
            }) { Text(stringResource(R.string.info_reset)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SlotPicker(label: String, value: InfoItem, onChange: (InfoItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(stringResource(value.label))
                Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                InfoItem.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.label)) },
                        onClick = { onChange(item); open = false },
                        trailingIcon = { if (item == value) Icon(Icons.Rounded.Check, null) },
                    )
                }
            }
        }
    }
}
