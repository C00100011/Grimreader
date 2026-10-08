package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.tts.neural.NeuralModelState
import com.vdelaar.mylibby.tts.neural.NeuralVoice
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch

/** Optional natural voice that runs on the phone after a one-time model download. */
@Composable
fun NeuralVoiceCard(language: String, selected: String?, sample: String, primary: Boolean = true, onSelect: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val store = c.tts.neuralStore
    val state by store.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // The download controls live in the first card only; the second one just offers the voices once installed.
    if (!primary && state != NeuralModelState.Installed) return

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.neural_title), style = MaterialTheme.typography.titleMedium)
            }
            when (val s = state) {
                NeuralModelState.NotInstalled, is NeuralModelState.Failed -> {
                    Text(stringResource(R.string.neural_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s is NeuralModelState.Failed) {
                        Text(stringResource(R.string.neural_download_failed, s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { confirm = true }) {
                        Text(stringResource(if (s is NeuralModelState.Failed) R.string.neural_retry else R.string.neural_download))
                    }
                }
                is NeuralModelState.Downloading -> {
                    val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                    Text(stringResource(R.string.neural_downloading, (fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = store::cancelDownload) { Text(stringResource(R.string.action_cancel)) }
                }
                NeuralModelState.Installed -> {
                    val shown = if (expanded) NeuralVoice.all else NeuralVoice.all.sortedByDescending { it.key == selected }.take(3)
                    shown.forEach { v ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(v.key) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == v.key, onClick = { onSelect(v.key) })
                            Text(
                                stringResource(if (v.female) R.string.neural_voice_female else R.string.neural_voice_male, v.number),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { c.tts.preview(v.key, sample, language) }) { Icon(Icons.Rounded.PlayCircle, stringResource(R.string.preview)) }
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) stringResource(R.string.show_fewer) else stringResource(R.string.neural_show_all, NeuralVoice.all.size))
                    }
                    if (primary) TextButton(onClick = { scope.launch { store.delete() } }) { Text(stringResource(R.string.neural_delete)) }
                }
            }
            if (primary) Text(stringResource(R.string.neural_license), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.neural_confirm_title)) },
            text = { Text(stringResource(R.string.neural_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; store.download() }) { Text(stringResource(R.string.neural_download)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
