package com.vdelaar.mylibby.ui.settings

import android.Manifest
import androidx.compose.material.icons.rounded.Translate
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.core.datastore.ThemeMode
import com.vdelaar.mylibby.notifications.ReminderScheduler
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navigator: AppNavigator, initialPage: String? = null) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app by c.settings.app.collectAsStateWithLifecycle()
    var secretOpen by rememberSaveable { mutableStateOf(false) }
    var pageName by rememberSaveable { mutableStateOf(initialPage) }
    val page = pageName?.let { n -> SettingsPage.entries.firstOrNull { it.name == n } }
    // Opened straight on a page (e.g. from Profile → Integrations): back leaves Settings instead of showing the overview.
    val backToOverview = page != null && initialPage == null
    androidx.activity.compose.BackHandler(enabled = backToOverview) { pageName = null }
    val reader by c.settings.reader.collectAsStateWithLifecycle()
    val goal by c.settings.goal.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var storage by remember { mutableLongStateOf(0L) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) { storage = c.downloads.totalBytes() }

    fun updateGoal(f: (com.vdelaar.mylibby.core.datastore.GoalSettings) -> com.vdelaar.mylibby.core.datastore.GoalSettings) = scope.launch {
        c.settings.updateGoal(f)
        ReminderScheduler.schedule(context, c.settings.goal.value)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page?.title ?: R.string.settings)) },
                navigationIcon = { IconButton(onClick = { if (backToOverview) pageName = null else navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = pageName ?: "home") {
                Column(Modifier.widthIn(max = 720.dp)) {
                    when (page) {
                        null -> SettingsHome(app, goal, reader) { pageName = it.name }
                        SettingsPage.APPEARANCE -> {
                    SettingsGroup(stringResource(R.string.appearance)) {
                        SettingsRow(stringResource(R.string.theme), icon = Icons.Rounded.Brightness6)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                            ThemeMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = app.themeMode == m,
                                    onClick = { scope.launch { c.settings.updateApp { it.copy(themeMode = m) } } },
                                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                ) { Text(stringResource(when (m) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })) }
                            }
                        }
                        SettingsSwitchRow(stringResource(R.string.material_you), stringResource(R.string.material_you_body), Icons.Rounded.Palette, checked = app.dynamicColor, onCheckedChange = { v -> scope.launch { c.settings.updateApp { it.copy(dynamicColor = v) } } })
                        // App language (English / Nederlands / system)
                        val activity = context as? android.app.Activity
                        val currentLang = remember(app.language) { com.vdelaar.mylibby.core.AppLanguage.current(context, app.language) }
                        SettingsRow(stringResource(R.string.language), icon = Icons.Rounded.Translate)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                            com.vdelaar.mylibby.core.AppLanguage.options.forEachIndexed { i, tag ->
                                SegmentedButton(
                                    selected = currentLang == tag,
                                    onClick = {
                                        scope.launch {
                                            c.settings.updateApp { it.copy(language = tag) }
                                            com.vdelaar.mylibby.core.AppLanguage.apply(activity, context.applicationContext, tag)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(i, com.vdelaar.mylibby.core.AppLanguage.options.size),
                                ) { Text(when (tag) { "en" -> "English"; "nl" -> "Nederlands"; else -> stringResource(R.string.theme_system) }) }
                            }
                        }
                    }

                        }
                        SettingsPage.READING -> {
                    SettingsGroup(stringResource(R.string.reading)) {
                        SettingsSwitchRow(stringResource(R.string.dark_reader_night), stringResource(R.string.dark_reader_night_body), Icons.Rounded.LightMode, checked = reader.followSystemDark, onCheckedChange = { v -> scope.launch { c.settings.updateReader { it.copy(followSystemDark = v) } } })
                        SettingsSwitchRow(stringResource(R.string.tap_edges), icon = Icons.Rounded.TouchApp, checked = reader.tapToTurn, onCheckedChange = { v -> scope.launch { c.settings.updateReader { it.copy(tapToTurn = v) } } })
                        SettingsSwitchRow(stringResource(R.string.volume_keys), icon = Icons.AutoMirrored.Rounded.VolumeUp, checked = reader.volumeKeysTurn, onCheckedChange = { v -> scope.launch { c.settings.updateReader { it.copy(volumeKeysTurn = v) } } })
                        SettingsSwitchRow(stringResource(R.string.keep_screen_on), checked = reader.keepScreenOn, onCheckedChange = { v -> scope.launch { c.settings.updateReader { it.copy(keepScreenOn = v) } } })
                        Text(
                            stringResource(R.string.reader_settings_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    PaceReferenceGroup()

                        }
                        SettingsPage.READ_ALOUD -> {
                    SettingsGroup(stringResource(R.string.read_aloud)) {
                        Column(Modifier.padding(16.dp)) { VoicePickerSection() }
                    }

                        }
                        SettingsPage.GOALS -> {
                    SettingsGroup(stringResource(R.string.goal_streak)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                            GoalType.entries.forEachIndexed { i, t ->
                                SegmentedButton(
                                    selected = goal.type == t,
                                    onClick = { updateGoal { it.copy(type = t, dailyTarget = if (t == GoalType.MINUTES) 20 else 15) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, GoalType.entries.size),
                                ) { Text(stringResource(if (t == GoalType.MINUTES) R.string.minutes else R.string.pages)) }
                            }
                        }
                        Text(
                            stringResource(if (goal.type == GoalType.MINUTES) R.string.minutes_per_day_n else R.string.pages_per_day_n, goal.dailyTarget),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Slider(
                            value = goal.dailyTarget.toFloat(),
                            onValueChange = { v -> updateGoal { it.copy(dailyTarget = v.toInt()) } },
                            valueRange = 5f..120f,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        SettingsRow(stringResource(R.string.freezes), stringResource(R.string.freezes_body)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { updateGoal { it.copy(freezesPerWeek = (it.freezesPerWeek - 1).coerceAtLeast(0)) } }) { Text("–") }
                                Text("${goal.freezesPerWeek}")
                                TextButton(onClick = { updateGoal { it.copy(freezesPerWeek = (it.freezesPerWeek + 1).coerceAtMost(3)) } }) { Text("+") }
                            }
                        }
                        SettingsSwitchRow(stringResource(R.string.daily_reminder), stringResource(R.string.reminder_body_settings), checked = goal.reminderEnabled, onCheckedChange = { v ->
                                if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                updateGoal { it.copy(reminderEnabled = v) }
                            })
                        if (goal.reminderEnabled) {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                TimePickerDialogButton(goal.reminderHour, goal.reminderMinute) { h, m -> updateGoal { it.copy(reminderHour = h, reminderMinute = m) } }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                        }
                        SettingsPage.INTEGRATIONS -> IntegrationsPage(navigator) { m -> scope.launch { snackbar.showSnackbar(m) } }
                        SettingsPage.DATA -> {
                    PersonalDataGroup(onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } })

                    SettingsGroup(stringResource(R.string.storage)) {
                        val startedMsg = stringResource(R.string.download_all_started)
                        val clearedMsg = stringResource(R.string.cache_cleared)
                        if (!app.noServer) SettingsSwitchRow(stringResource(R.string.download_all),
                            stringResource(R.string.download_all_body),
                            Icons.Rounded.CloudDownload, checked = app.autoDownloadAll, onCheckedChange = { v ->
                                scope.launch {
                                    c.settings.updateApp { it.copy(autoDownloadAll = v, autoDownloadExcluded = emptySet()) }
                                    com.vdelaar.mylibby.data.AutoDownload.apply(context, c.settings.app.value)
                                    if (v) snackbar.showSnackbar(startedMsg)
                                }
                            })
                        if (app.autoDownloadAll && !app.noServer) {
                            SettingsSwitchRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_body), checked = app.autoDownloadWifiOnly, onCheckedChange = { v ->
                                    scope.launch {
                                        c.settings.updateApp { it.copy(autoDownloadWifiOnly = v) }
                                        com.vdelaar.mylibby.data.AutoDownload.apply(context, c.settings.app.value)
                                    }
                                })
                        }
                        SettingsRow(stringResource(R.string.downloaded_books), Formatter.formatShortFileSize(context, storage), Icons.Rounded.Storage, onClick = navigator::downloads)
                        SettingsRow(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_body), Icons.Rounded.Delete, onClick = {
                            scope.launch { c.downloads.clearStreamCache(); snackbar.showSnackbar(clearedMsg) }
                        })
                    }

                        }
                        SettingsPage.ABOUT -> {
                    SettingsGroup(stringResource(R.string.about)) {
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        Box(Modifier.onHold(10_000) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); secretOpen = true }) {
                            SettingsRow("Grimreader", stringResource(R.string.version_line, BuildConfig.VERSION_NAME), Icons.Rounded.Info)
                        }
                        SettingsRow(stringResource(R.string.libraries), stringResource(R.string.libraries_sub), Icons.Rounded.Code, onClick = navigator::libraries)
                        Text(
                            stringResource(R.string.credits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                        }
                    }
                }
            }
        }
    }

    if (secretOpen) SecretMenu(onDismiss = { secretOpen = false })
}
/** The pages of the settings, each a category on the home list. */
enum class SettingsPage(@androidx.annotation.StringRes val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    APPEARANCE(R.string.appearance, Icons.Rounded.Palette),
    READING(R.string.reading, Icons.Rounded.AutoStories),
    READ_ALOUD(R.string.read_aloud, Icons.Rounded.RecordVoiceOver),
    GOALS(R.string.goal_streak, Icons.Rounded.LocalFireDepartment),
    INTEGRATIONS(R.string.settings_integrations, Icons.Rounded.Hub),
    DATA(R.string.settings_data, Icons.Rounded.Storage),
    ABOUT(R.string.about, Icons.Rounded.Info),
}

@Composable
private fun SettingsHome(
    app: com.vdelaar.mylibby.core.datastore.AppState,
    goal: com.vdelaar.mylibby.core.datastore.GoalSettings,
    reader: com.vdelaar.mylibby.core.datastore.ReaderSettings,
    onOpen: (SettingsPage) -> Unit,
) {
    val grimmoryLabel = androidx.compose.ui.res.stringResource(R.string.int_short_grimmory)
    val opdsLabel = androidx.compose.ui.res.stringResource(R.string.int_short_opds)
    val deviceLabel = androidx.compose.ui.res.stringResource(R.string.int_short_local)
    val librariesLabel = app.librarySources.joinToString(" + ") {
        when (it) {
            com.vdelaar.mylibby.core.datastore.LibrarySource.GRIMMORY -> grimmoryLabel
            com.vdelaar.mylibby.core.datastore.LibrarySource.OPDS -> opdsLabel
            com.vdelaar.mylibby.core.datastore.LibrarySource.LOCAL -> deviceLabel
        }
    }

    @Composable
    fun subtitle(p: SettingsPage): String = when (p) {
        SettingsPage.APPEARANCE -> androidx.compose.ui.res.stringResource(R.string.settings_appearance_sub)
        SettingsPage.READING -> androidx.compose.ui.res.stringResource(R.string.settings_reading_sub)
        SettingsPage.READ_ALOUD -> androidx.compose.ui.res.stringResource(R.string.settings_read_aloud_sub)
        SettingsPage.GOALS -> androidx.compose.ui.res.stringResource(if (goal.type == GoalType.MINUTES) R.string.minutes_per_day_n else R.string.pages_per_day_n, goal.dailyTarget)
        SettingsPage.INTEGRATIONS -> androidx.compose.ui.res.stringResource(
            R.string.settings_integrations_sub,
            librariesLabel,
            androidx.compose.ui.res.stringResource(if (app.effectiveBookSearch == com.vdelaar.mylibby.core.datastore.BookSearchSource.SHELFMARK) R.string.int_short_shelfmark else R.string.int_short_none),
            androidx.compose.ui.res.stringResource(when (app.recommendations) {
                com.vdelaar.mylibby.core.datastore.RecommendationSource.HARDCOVER -> R.string.int_short_hardcover
                com.vdelaar.mylibby.core.datastore.RecommendationSource.OPEN_LIBRARY -> R.string.int_short_openlibrary
                com.vdelaar.mylibby.core.datastore.RecommendationSource.LOCAL -> R.string.int_short_mylibrary
            }),
        )
        SettingsPage.DATA -> androidx.compose.ui.res.stringResource(R.string.settings_data_sub)
        SettingsPage.ABOUT -> androidx.compose.ui.res.stringResource(R.string.version_line, BuildConfig.VERSION_NAME)
    }

    @Composable
    fun row(p: SettingsPage) = SettingsRow(androidx.compose.ui.res.stringResource(p.title), subtitle(p), p.icon, onClick = { onOpen(p) }) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SettingsGroup(androidx.compose.ui.res.stringResource(R.string.settings_group_personalise)) {
        row(SettingsPage.APPEARANCE); row(SettingsPage.READING); row(SettingsPage.READ_ALOUD); row(SettingsPage.GOALS)
    }
    SettingsGroup(androidx.compose.ui.res.stringResource(R.string.settings_group_connections)) { row(SettingsPage.INTEGRATIONS) }
    SettingsGroup(androidx.compose.ui.res.stringResource(R.string.settings_group_app)) { row(SettingsPage.DATA); row(SettingsPage.ABOUT) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(navigator: AppNavigator) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by c.downloads.observeAll().collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            com.vdelaar.mylibby.ui.components.EmptyState(
                "✈️", stringResource(R.string.no_books_device),
                stringResource(R.string.no_books_device_body),
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding) {
            item {
                val total = downloads.sumOf { it.sizeBytes }
                Text(
                    pluralStringResource(R.plurals.downloads_summary, downloads.size, downloads.size, Formatter.formatShortFileSize(context, total)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(downloads, key = { it.bookId }) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        androidx.compose.material3.TextButton(onClick = { navigator.openBook(d.bookId) }, contentPadding = PaddingValues(0.dp)) {
                            Text(d.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                        }
                        when (d.state) {
                            DownloadState.DONE -> Text("${d.fileType} · ${Formatter.formatShortFileSize(context, d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DownloadState.FAILED -> Text(stringResource(R.string.download_failed_reason, d.error ?: stringResource(R.string.unknown_error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else -> LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    IconButton(onClick = { scope.launch { c.downloads.remove(d.bookId) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.remove_x, d.title)) }
                }
            }
            item { Spacer(Modifier.width(1.dp).height(24.dp)) }
        }
    }
}
