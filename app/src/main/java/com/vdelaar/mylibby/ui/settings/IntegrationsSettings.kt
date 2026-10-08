package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.BookSearchSource
import com.vdelaar.mylibby.core.datastore.LibrarySource
import com.vdelaar.mylibby.core.datastore.RecommendationSource
import com.vdelaar.mylibby.core.datastore.canSwitchOffDevice
import com.vdelaar.mylibby.core.datastore.withDevice
import com.vdelaar.mylibby.core.datastore.withGrimmory
import com.vdelaar.mylibby.core.datastore.withOpds
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.discover.ConnectShelfmark
import com.vdelaar.mylibby.ui.discover.DiscoverViewModel
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import com.vdelaar.mylibby.ui.discover.OidcLoginDialog
import kotlinx.coroutines.launch

/**
 * Settings → Integrations: which service provides what.
 *  Library: Grimmory, an OPDS catalog, or none (this device only)
 *  Book search: Shelfmark-style lookup and download, or none
 *  Recommendations: Hardcover, or based on your own library
 */
@Composable
fun IntegrationsPage(navigator: AppNavigator, snackbar: (String) -> Unit) {
    LibraryGroup(navigator, snackbar)
    BookSearchGroup()
    RecommendationsGroup()
}

/** One choice of a group, with its own settings shown while it is the selected one. */
@Composable
private fun SourceOption(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    /** True = a checkbox (several can be on); false = a radio button (exactly one). */
    multiple: Boolean = false,
    enabled: Boolean = true,
    /** Show [content] (its own settings); by default while selected. */
    expanded: Boolean = selected,
    content: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = if (multiple) androidx.compose.ui.semantics.Role.Checkbox else androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiple) androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            else RadioButton(selected = selected, onClick = null)
            Icon(icon, null, Modifier.padding(start = 12.dp, end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun GroupIntro(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

// ---- Library ---------------------------------------------------------------------------------------

@Composable
private fun LibraryGroup(navigator: AppNavigator, snackbar: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    val server by c.settings.server.collectAsStateWithLifecycle()
    val loggedIn by c.tokens.loggedIn.collectAsStateWithLifecycle()
    val grimmoryConnected = loggedIn && server.grimmoryUrl.isNotBlank() && !app.demo
    var editOpds by rememberSaveable { mutableStateOf(false) }
    var showSync by rememberSaveable { mutableStateOf(false) }
    val syncStatus by c.settings.syncStatus.collectAsStateWithLifecycle()
    if (showSync) GrimmorySyncSheet(onDismiss = { showSync = false })

    SettingsGroup(stringResource(R.string.int_library)) {
        GroupIntro(stringResource(R.string.int_library_body))

        SourceOption(
            title = "Grimmory",
            subtitle = if (grimmoryConnected) "${server.username} · ${server.grimmoryUrl}" else stringResource(R.string.int_not_connected),
            icon = Icons.Rounded.Dns,
            selected = app.grimmoryOn,
            multiple = true,
            onSelect = {
                scope.launch {
                    if (app.grimmoryOn) {
                        c.settings.updateApp { it.withGrimmory(false) } // the sign-in is kept
                    } else if (grimmoryConnected) {
                        c.settings.updateApp { it.withGrimmory(true) }
                        c.sync.requestSync()
                    } else {
                        navigator.connectLibrary() // not signed in (yet): just the sign-in step
                    }
                }
            },
        ) {
            if (grimmoryConnected) SettingsRow(stringResource(R.string.sync_row_title), syncSummary(syncStatus), Icons.Rounded.CloudSync, onClick = { showSync = true })
            if (server.trustedCerts.isNotEmpty()) SettingsRow(stringResource(R.string.trusted_certs), stringResource(R.string.trusted_certs_body, server.trustedCerts.size), Icons.Rounded.Dns) {
                TextButton(onClick = { scope.launch { c.settings.updateServer { it.copy(trustedCerts = emptySet()) } } }) { Text(stringResource(R.string.trusted_certs_clear)) }
            }
        }

        SourceOption(
            title = stringResource(R.string.int_opds),
            subtitle = app.opdsUrl.takeIf { it.isNotBlank() }?.removePrefix("https://")?.removePrefix("http://") ?: stringResource(R.string.int_opds_none),
            icon = Icons.Rounded.Public,
            selected = app.opdsActive,
            expanded = app.opdsActive || editOpds,
            multiple = true,
            onSelect = {
                scope.launch {
                    when {
                        app.opdsActive -> { editOpds = false; c.settings.updateApp { it.withOpds(false) } }
                        app.opdsUrl.isNotBlank() -> c.settings.updateApp { it.withOpds(true) }
                        else -> editOpds = !editOpds
                    }
                }
            },
        ) { OpdsForm(onSaved = { editOpds = false; snackbar(it) }) }

        SourceOption(
            title = stringResource(R.string.int_local),
            subtitle = stringResource(if (app.canSwitchOffDevice() || !app.deviceOn) R.string.int_local_body else R.string.int_local_required),
            icon = Icons.Rounded.PhoneAndroid,
            selected = app.deviceOn,
            multiple = true,
            enabled = !app.deviceOn || app.canSwitchOffDevice(),
            onSelect = { scope.launch { c.settings.updateApp { it.withDevice(!app.deviceOn) } } },
        )
    }
}

@Composable
internal fun OpdsForm(onSaved: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    var url by rememberSaveable(app.opdsUrl) { mutableStateOf(app.opdsUrl) }
    var user by rememberSaveable(app.opdsUsername) { mutableStateOf(app.opdsUsername) }
    var pass by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val savedMsg = stringResource(R.string.int_opds_saved)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.int_opds_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            url, { url = it; error = null }, label = { Text(stringResource(R.string.int_opds_address)) },
            placeholder = { Text("https://calibre.example.com/opds") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.int_opds_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text(stringResource(if (app.opdsUsername.isNotBlank()) R.string.int_opds_password_keep else R.string.int_opds_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    scope.launch {
                        testing = true; error = null
                        // An empty password field next to an unchanged user name keeps the stored password.
                        val password = if (pass.isEmpty() && user == app.opdsUsername) c.tokens.opdsPassword.orEmpty() else pass
                        c.opds.test(url, user.trim(), password)
                            .onSuccess { tested ->
                                c.tokens.opdsPassword = password
                                c.settings.updateApp { s -> s.withOpds(true).copy(opdsUrl = tested.url, opdsUsername = user.trim(), localOnly = s.localOnly || c.tokens.accessToken == null) }
                                pass = ""
                                onSaved(savedMsg)
                            }
                            .onFailure { error = it.message }
                        testing = false
                    }
                },
            ) { Text(stringResource(R.string.int_save_test)) }
            if (testing) CircularProgressIndicator(Modifier.size(24.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- Book search -----------------------------------------------------------------------------------

@Composable
private fun BookSearchGroup() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    val server by c.settings.server.collectAsStateWithLifecycle()
    val vm = appViewModel(key = "settings-shelfmark") { DiscoverViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    val search = app.effectiveBookSearch

    SettingsGroup(stringResource(R.string.int_search)) {
        GroupIntro(stringResource(R.string.int_search_body))
        val method = when (server.shelfmarkAuth) {
            "oidc" -> stringResource(R.string.sm_auth_oidc)
            "password" -> stringResource(R.string.sm_auth_password)
            "apikey" -> stringResource(R.string.sm_auth_apikey)
            else -> stringResource(R.string.sm_auth_none)
        }
        SourceOption(
            title = "Shelfmark",
            subtitle = if (configured) "$method · ${server.shelfmarkUrl}" else stringResource(R.string.sm_not_connected),
            icon = Icons.Rounded.Explore,
            selected = search == BookSearchSource.SHELFMARK,
            onSelect = { scope.launch { c.settings.updateApp { it.copy(bookSearch = BookSearchSource.SHELFMARK) } } },
        ) {
            if (configured) SettingsRow(stringResource(R.string.sm_connected), "$method · ${server.shelfmarkUrl}") {
                TextButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.disconnect)) }
            } else ConnectShelfmark(state, vm, compact = true)
        }
        SourceOption(
            title = stringResource(R.string.int_search_none),
            subtitle = stringResource(R.string.int_search_none_body),
            icon = Icons.Rounded.SearchOff,
            selected = search == BookSearchSource.NONE,
            onSelect = { scope.launch { c.settings.updateApp { it.copy(bookSearch = BookSearchSource.NONE) } } },
        )
    }

    state.oidcUrl?.let { url ->
        OidcLoginDialog(baseUrl = url, providerLabel = state.oidcLabel, onSignedIn = vm::oidcSignedIn, onError = vm::oidcFailed, onDismiss = vm::cancelOidc)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.disconnect_sm_q)) },
            text = { Text(stringResource(R.string.disconnect_sm_body)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; scope.launch { c.shelfmark.disconnect() } }) { Text(stringResource(R.string.disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ---- Recommendations -------------------------------------------------------------------------------

@Composable
private fun RecommendationsGroup() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    val hardcoverOn by c.tokens.hardcoverConnected.collectAsStateWithLifecycle()

    SettingsGroup(stringResource(R.string.int_recs)) {
        GroupIntro(stringResource(R.string.int_recs_body))
        SourceOption(
            title = stringResource(R.string.int_recs_local),
            subtitle = stringResource(R.string.int_recs_local_body),
            icon = Icons.Rounded.Lightbulb,
            selected = app.recommendations == RecommendationSource.LOCAL,
            onSelect = { scope.launch { c.settings.updateApp { it.copy(recommendations = RecommendationSource.LOCAL) } } },
        )
        SourceOption(
            title = stringResource(R.string.int_recs_openlibrary),
            subtitle = stringResource(R.string.int_recs_openlibrary_body),
            icon = Icons.Rounded.Explore,
            selected = app.recommendations == RecommendationSource.OPEN_LIBRARY,
            onSelect = { scope.launch { c.settings.updateApp { it.copy(recommendations = RecommendationSource.OPEN_LIBRARY) } } },
        )
        SourceOption(
            title = "Hardcover",
            subtitle = stringResource(if (hardcoverOn) R.string.hc_status_on else R.string.int_recs_hardcover_body),
            icon = Icons.Rounded.AutoStories,
            selected = app.recommendations == RecommendationSource.HARDCOVER,
            onSelect = { scope.launch { c.settings.updateApp { it.copy(recommendations = RecommendationSource.HARDCOVER) } } },
        ) { HardcoverKeySection() }
    }
}
