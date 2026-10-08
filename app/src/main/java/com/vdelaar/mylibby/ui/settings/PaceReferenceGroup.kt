package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.PaceReference
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Settings > Reading: which reading speed the pace on the book page is compared with. */
@Composable
fun PaceReferenceGroup() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    SettingsGroup(stringResource(R.string.pace_ref_title)) {
        Text(
            stringResource(R.string.pace_ref_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        PaceReference.entries.forEach { ref ->
            val selected = app.paceReference == ref
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton) { scope.launch { c.settings.updateApp { it.copy(paceReference = ref) } } }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stringResource(ref.label), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.pace_ref_wpm, if (ref == PaceReference.CUSTOM) app.paceWpm else ref.wpm!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (app.paceReference == PaceReference.CUSTOM) {
            Slider(
                value = app.paceWpm.toFloat(),
                onValueChange = { v -> scope.launch { c.settings.updateApp { it.copy(paceCustomWpm = (v / 10).roundToInt() * 10) } } },
                valueRange = PaceReference.MIN_CUSTOM.toFloat()..PaceReference.MAX_CUSTOM.toFloat(),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
