package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.tts.VoiceOption
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogButton(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.remind_me_at, hour, minute))
    }
    if (open) {
        val state = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onPicked(state.hour, state.minute); open = false }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state) },
        )
    }
}

/** Dutch + English voice pickers with preview, speed and pitch. Used in onboarding and settings. */
@Composable
fun VoicePickerSection() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val voices by c.tts.voices.collectAsStateWithLifecycle()
    val ttsState by c.tts.state.collectAsStateWithLifecycle()
    val settings by c.settings.tts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.tts.ensureEngine() }
    DisposableEffect(Unit) { onDispose { if (c.tts.source == null) c.tts.stop() } }

    if (ttsState.error != null) {
        Text(ttsState.error!!, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { c.tts.installVoiceData() }) { Text(stringResource(R.string.install_voices)) }
        return
    }
    if (!ttsState.ready) {
        Text(stringResource(R.string.loading_voices), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val dutch = voices.filter { it.locale.language == "nl" }
    val english = voices.filter { it.locale.language == "en" }

    VoiceGroup(
        title = stringResource(R.string.dutch_voice),
        voices = dutch,
        selected = settings.dutchVoice ?: dutch.firstOrNull()?.name,
        sample = "Er was eens, in een land hier ver vandaan, een boek dat niemand ooit had uitgelezen.",
        onSelect = { name -> scope.launch { c.settings.updateTts { it.copy(dutchVoice = name) } } },
        onInstall = { c.tts.installVoiceData() },
    )
    Spacer(Modifier.height(8.dp))
    NeuralVoiceCard(language = "nl", selected = settings.dutchVoice, sample = "Er was eens, in een land hier ver vandaan, een boek dat niemand ooit had uitgelezen.") { key ->
        scope.launch { c.settings.updateTts { it.copy(dutchVoice = key) } }
    }
    Spacer(Modifier.height(16.dp))
    VoiceGroup(
        title = stringResource(R.string.english_voice),
        voices = english,
        selected = settings.englishVoice ?: english.firstOrNull()?.name,
        sample = "It was a bright cold day in April, and the clocks were striking thirteen.",
        onSelect = { name -> scope.launch { c.settings.updateTts { it.copy(englishVoice = name) } } },
        onInstall = { c.tts.installVoiceData() },
    )
    Spacer(Modifier.height(8.dp))
    NeuralVoiceCard(language = "en", primary = false, selected = settings.englishVoice, sample = "It was a bright cold day in April, and the clocks were striking thirteen.") { key ->
        scope.launch { c.settings.updateTts { it.copy(englishVoice = key) } }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.speed_label, "%.1f".format(settings.speechRate)), style = MaterialTheme.typography.titleSmall)
    Slider(value = settings.speechRate, onValueChange = { v -> scope.launch { c.settings.updateTts { it.copy(speechRate = (v * 10).toInt() / 10f) } } }, valueRange = 0.5f..2.5f)
    Text(stringResource(R.string.pitch_label, "%.1f".format(settings.pitch)), style = MaterialTheme.typography.titleSmall)
    Slider(value = settings.pitch, onValueChange = { v -> scope.launch { c.settings.updateTts { it.copy(pitch = (v * 10).toInt() / 10f) } } }, valueRange = 0.5f..2.0f)
    TextButton(onClick = { c.tts.openSystemTtsSettings() }) {
        Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.more_voices))
    }
}

@Composable
private fun VoiceGroup(
    title: String,
    voices: List<VoiceOption>,
    selected: String?,
    sample: String,
    onSelect: (String) -> Unit,
    onInstall: () -> Unit,
) {
    val c = appContainer()
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (voices.isEmpty()) {
                Text(stringResource(R.string.no_voices_language), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onInstall, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.install_voice_data)) }
                return@Column
            }
            val shown = if (expanded) voices else voices.sortedByDescending { it.name == selected }.take(4)
            shown.forEach { v ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(v.name) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = v.name == selected, onClick = { onSelect(v.name) })
                    Text(v.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { c.tts.preview(v.name, sample) }) { Icon(Icons.Rounded.PlayCircle, stringResource(R.string.preview)) }
                }
            }
            if (voices.size > 4) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (expanded) stringResource(R.string.show_fewer) else pluralStringResource(R.plurals.show_all_voices, voices.size, voices.size))
                }
            }
        }
    }
}

/** Fires [onHold] once when the finger stays down for [millis]; a normal tap or scroll is untouched. */
fun Modifier.onHold(millis: Long, onHold: () -> Unit): Modifier = this.pointerInput(millis) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // The block returns normally when the finger lifts or the gesture is taken over (a scroll); only a timeout gives null.
        val finished = withTimeoutOrNull(millis) { waitForUpOrCancellation(); true }
        if (finished == null) {
            onHold()
            waitForUpOrCancellation()
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** A settings row with a switch; the whole row toggles (Material guidance, bigger target). */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun CheckMark(visible: Boolean) {
    if (visible) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
}
