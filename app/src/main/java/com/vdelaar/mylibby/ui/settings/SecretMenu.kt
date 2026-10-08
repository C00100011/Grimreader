package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.profile.BadgeDef
import kotlinx.coroutines.launch

/** Hidden menu (hold the About row for 10 seconds): force profile achievements on or off. Cheating, on purpose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretMenu(onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()

    fun set(key: String, value: Boolean?) = scope.launch {
        c.settings.updateApp { s ->
            s.copy(badgeOverrides = if (value == null) s.badgeOverrides - key else s.badgeOverrides + (key to value))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🤫 " + stringResource(R.string.secret_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.secret_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BadgeDef.all.forEach { d ->
                val current = app.badgeOverrides[d.key]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${d.emoji}  ${stringResource(d.title)}", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = current == null, onClick = { set(d.key, null) }, label = { Text(stringResource(R.string.secret_auto)) })
                        FilterChip(selected = current == true, onClick = { set(d.key, true) }, label = { Text(stringResource(R.string.secret_on)) })
                        FilterChip(selected = current == false, onClick = { set(d.key, false) }, label = { Text(stringResource(R.string.secret_off)) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { scope.launch { c.settings.updateApp { it.copy(badgeOverrides = emptyMap()) } } }) { Text(stringResource(R.string.secret_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}
