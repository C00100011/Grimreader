package com.vdelaar.mylibby.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.network.HardcoverException
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.hardcover.hardcoverErrorText
import kotlinx.coroutines.launch

/** The user's own Hardcover API key (shown inside Settings → Integrations → Recommendations → Hardcover). */
@Composable
fun HardcoverKeySection() {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by c.tokens.hardcoverConnected.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HardcoverException?>(null) }
    var justConnected by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hc_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null; justConnected = false },
            label = { Text(stringResource(if (connected) R.string.hc_key_replace else R.string.hc_key_label)) },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.hc_show_key))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = key.isNotBlank() && !testing,
                onClick = {
                    scope.launch {
                        testing = true; error = null; justConnected = false
                        c.hardcover.connect(key)
                            .onSuccess { key = ""; justConnected = true }
                            .onFailure { error = it as? HardcoverException ?: HardcoverException(HardcoverException.Kind.SERVER, it.message ?: "") }
                        testing = false
                    }
                },
            ) { Text(stringResource(R.string.hc_save_test)) }
            if (testing) CircularProgressIndicator(Modifier.size(24.dp))
            if (connected) TextButton(onClick = { c.hardcover.disconnect(); justConnected = false; error = null }) { Text(stringResource(R.string.hc_remove)) }
        }
        error?.let { Text(hardcoverErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (justConnected) Text(stringResource(R.string.hc_key_ok), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/account/api"))) } }) {
            Text(stringResource(R.string.hc_open_api_page))
        }
        Text(stringResource(R.string.hc_privacy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
