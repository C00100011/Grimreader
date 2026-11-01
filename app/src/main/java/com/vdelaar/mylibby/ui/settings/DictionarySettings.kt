package com.vdelaar.mylibby.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.dict.DictionaryDownloads
import com.vdelaar.mylibby.data.dict.InstalledPack
import com.vdelaar.mylibby.notifications.Notifications
import com.vdelaar.mylibby.data.dict.PackInfo
import com.vdelaar.mylibby.data.dict.WIKTIONARY_PACKS
import com.vdelaar.mylibby.data.dict.WiktPackInfo
import com.vdelaar.mylibby.data.dict.PackProgress
import com.vdelaar.mylibby.data.dict.iso3
import com.vdelaar.mylibby.data.dict.packLabel
import com.vdelaar.mylibby.data.dict.packSize
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.reader.PackRow
import com.vdelaar.mylibby.ui.reader.WiktPackRow
import com.vdelaar.mylibby.ui.reader.wiktTitle
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings → Reading → Dictionaries: what is on the device, adding and removing, and the online extras. */
@Composable
fun DictionaryGroup() {
    val c = appContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installed by c.dictionaryPacks.installed.collectAsStateWithLifecycle()
    val progress by c.dictionaryPacks.progress.collectAsStateWithLifecycle()
    val wikt by c.wiktionary.installed.collectAsStateWithLifecycle()
    val wiktProgress by c.wiktionary.progress.collectAsStateWithLifecycle()
    val reader by c.settings.reader.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.dict_manage_title)) {
        SettingsSwitchRow(
            stringResource(R.string.lookup_on_hold), stringResource(R.string.lookup_on_hold_body), Icons.AutoMirrored.Rounded.MenuBook,
            checked = reader.lookupOnHold, onCheckedChange = { v -> scope.launch { c.settings.updateReader { it.copy(lookupOnHold = v) } } },
        )
        Text(
            stringResource(R.string.dict_manage_body),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (installed.isEmpty() && wikt.isEmpty() && progress.isEmpty() && wiktProgress.isEmpty()) Text(stringResource(R.string.dict_none_installed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        wikt.forEach { p ->
            val info = WIKTIONARY_PACKS.firstOrNull { it.id == p.id }
            SettingsRow(
                info?.let { wiktTitle(it) } ?: p.languageName,
                stringResource(R.string.dict_installed_size, "%,d".format(p.entries), packSize(p.bytes)),
                trailing = { TextButton(onClick = { c.wiktionary.remove(p.id) }) { Text(stringResource(R.string.dict_remove)) } },
            )
        }
        wiktProgress.filterKeys { k -> wikt.none { it.id == k } }.forEach { (id, state) ->
            val info = WIKTIONARY_PACKS.firstOrNull { it.id == id } ?: return@forEach
            Column(Modifier.padding(horizontal = 16.dp)) {
                WiktPackRow(info, null, state, onDownload = {}, onRemove = {})
                if (state is PackProgress.Failed) TextButton(onClick = { c.wiktionary.dismissFailure(id) }) { Text(stringResource(R.string.action_close)) }
            }
        }
        installed.forEach { p ->
            SettingsRow(
                packLabel(p.source, p.target),
                stringResource(R.string.dict_installed_size, "%,d".format(p.headwords), packSize(p.bytes)),
                trailing = { TextButton(onClick = { c.dictionaryPacks.remove(p.name) }) { Text(stringResource(R.string.dict_remove)) } },
            )
        }
        // A download that is running (or has failed) but is not installed yet, so it stays visible after the picker is closed.
        progress.filterKeys { k -> installed.none { it.name == k } }.forEach { (name, state) ->
            val (src, dst) = name.split('-').let { it.getOrElse(0) { name } to it.getOrElse(1) { "" } }
            Column(Modifier.padding(horizontal = 16.dp)) {
                PackRow(PackInfo(name, src, dst, 0, "", "", 0, ""), null, state, onDownload = {}, onRemove = {})
                if (state is PackProgress.Failed) TextButton(onClick = { c.dictionaryPacks.dismissFailure(name) }) { Text(stringResource(R.string.action_close)) }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Rounded.Add, null)
            Text(stringResource(R.string.dict_add), Modifier.padding(start = 8.dp))
        }
    }
    if (adding) DictionaryPicker(onDismiss = { adding = false })
}

/** Every dictionary FreeDict has, narrowed by language; those that involve the device's own language come first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryPicker(onDismiss: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    // A dictionary announces when it is ready; ask once for the right to show that.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun download(start: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        start()
    }
    val installed by c.dictionaryPacks.installed.collectAsStateWithLifecycle()
    val progress by c.dictionaryPacks.progress.collectAsStateWithLifecycle()
    val wiktInstalled by c.wiktionary.installed.collectAsStateWithLifecycle()
    val wiktProgress by c.wiktionary.progress.collectAsStateWithLifecycle()
    var catalog by androidx.compose.runtime.remember { mutableStateOf<List<PackInfo>?>(null) }
    var failed by androidx.compose.runtime.remember { mutableStateOf(false) }
    var attempt by androidx.compose.runtime.remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(attempt) {
        failed = false
        try { catalog = c.dictionaryPacks.catalog() } catch (e: java.io.IOException) { failed = true }
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val mine = iso3(locale.language)
    val shown = visiblePacks(catalog, query, mine, locale)
    val shownWikt = visibleWikt(query, locale)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 640.dp) {
        Column(Modifier.padding(horizontal = 20.dp).heightIn(min = 320.dp)) {
            Text(stringResource(R.string.dict_add), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.dict_find_language)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            when {
                failed -> Column {
                    Text(stringResource(R.string.dict_catalog_unavailable), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                }
                catalog == null && shownWikt.isEmpty() -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (shownWikt.isNotEmpty()) {
                        item(key = "wikt-heading") { Text(stringResource(R.string.dict_wikt_heading), style = MaterialTheme.typography.labelLarge) }
                        items(shownWikt, key = { "wikt-" + it.id }) { p ->
                            WiktPackRow(
                                p, wiktInstalled.firstOrNull { it.id == p.id }, wiktProgress[p.id],
                                onDownload = { download { c.wiktionary.dismissFailure(p.id); DictionaryDownloads.startWiktionary(context, p) } },
                                onRemove = { c.wiktionary.remove(p.id) },
                            )
                        }
                    }
                    if (shown.isNotEmpty()) item(key = "freedict-heading") { Text(stringResource(R.string.dict_translations_heading), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
                    items(shown, key = { it.name }) { p ->
                        PackRow(
                            p, installed.firstOrNull { it.name == p.name }, progress[p.name],
                            onDownload = { download { c.dictionaryPacks.dismissFailure(p.name); DictionaryDownloads.startFreedict(context, p) } },
                            onRemove = { c.dictionaryPacks.remove(p.name) },
                        )
                    }
                }
            }
        }
    }
}

/** The catalog narrowed to [query] (a language, in the device's language or as FreeDict's code); with no query, only the device's language. */
@Composable
private fun visiblePacks(catalog: List<PackInfo>?, query: String, mine: String, locale: Locale): List<PackInfo> =
    androidx.compose.runtime.remember(catalog, query, mine, locale) {
        val all = catalog.orEmpty()
        val q = query.trim().lowercase()
        fun names(p: PackInfo) = listOf(p.source, p.target).flatMap { listOf(it, Locale.forLanguageTag(it).getDisplayLanguage(locale).lowercase(), Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase()) }
        if (q.isEmpty()) all.filter { it.source == mine || it.target == mine }.ifEmpty { all.filter { it.source == "eng" || it.target == "eng" } }
        else all.filter { p -> names(p).any { it.contains(q) } }
    }

/** The Wiktionary packs for [query] (a language, in the device's language, in English or as a code); with no query, those in the device's language and English. */
@Composable
private fun visibleWikt(query: String, locale: Locale): List<WiktPackInfo> =
    androidx.compose.runtime.remember(query, locale) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) WIKTIONARY_PACKS.filter { it.language == locale.language || it.language == "en" }
        else WIKTIONARY_PACKS.filter { p ->
            val l = Locale.forLanguageTag(p.language)
            listOf(p.language, l.getDisplayLanguage(locale), l.getDisplayLanguage(Locale.ENGLISH), p.languageName).any { it.lowercase().contains(q) }
        }
    }
