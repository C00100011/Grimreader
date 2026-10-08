package com.vdelaar.mylibby.ui.profile

import androidx.compose.foundation.background
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.flow.MutableStateFlow
import com.vdelaar.mylibby.data.pushPendingChanges
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.datastore.Profile
import com.vdelaar.mylibby.data.StreakInfo
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.ProfileAvatar
import com.vdelaar.mylibby.ui.components.ProgressRing
import com.vdelaar.mylibby.ui.components.rememberPhotoPicker
import com.vdelaar.mylibby.ui.components.StreakFlame
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import com.vdelaar.mylibby.ui.onboarding.AvatarChoices
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    val profile = c.settings.app.map { it.profile }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.profile)
    val username = c.settings.server.value.username
    val server = c.settings.server.value.grimmoryUrl
    val streak = c.stats.streak.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo())
    val totalSeconds = c.stats.totalSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val finished = c.db.books().observeFinishedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val reading = c.db.books().observeReadingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val favourites = c.library.observeFavouriteIds().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val highlights = c.db.annotations().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateProfile(f: (Profile) -> Profile) = viewModelScope.launch { c.settings.updateApp { it.copy(profile = f(it.profile)) } }

    val demo = c.settings.app.map { it.demo }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.demo)

    /** How many changes are still waiting to reach Grimmory (null while checking). */
    val unsynced = MutableStateFlow<Int?>(null)
    val syncing = MutableStateFlow(false)

    fun checkUnsynced() = viewModelScope.launch {
        unsynced.value = if (c.settings.app.value.demo) 0 else c.auth.unsyncedChanges()
    }

    /** Try to push everything now, then re-count. */
    fun syncNow() = viewModelScope.launch {
        syncing.value = true
        runCatching { pushPendingChanges(c) }
        unsynced.value = c.auth.unsyncedChanges()
        syncing.value = false
    }

    val opds = c.settings.app.map { it.opdsActive && it.localOnly }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.let { it.opdsActive && it.localOnly })
    /** Grimmory is switched off (local books and/or an OPDS catalog only). */
    val localOnly = c.settings.app.map { it.localOnly }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.app.value.localOnly)

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        if (c.settings.app.value.demo) c.demo.exit() else c.auth.logout()
        onDone()
    }
}

/** Reader levels based on total hours read. */
private val levels = listOf(0 to R.string.level_curious, 5 to R.string.level_page_turner, 20 to R.string.level_bookworm, 50 to R.string.level_story_seeker, 100 to R.string.level_bibliophile, 250 to R.string.level_legend)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(navigator: AppNavigator, onOpenActivity: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val seconds by vm.totalSeconds.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val demo by vm.demo.collectAsStateWithLifecycle()
    val localOnly by vm.localOnly.collectAsStateWithLifecycle()
    val opds by vm.opds.collectAsStateWithLifecycle()

    val hours = seconds / 3600.0
    val levelIndex = levels.indexOfLast { hours >= it.first }.coerceAtLeast(0)
    val level = levels[levelIndex]
    val next = levels.getOrNull(levelIndex + 1)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface))
            ).statusBarsPadding().padding(top = 16.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileAvatar(profile, 104.dp, Modifier.clickable { editing = true })
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name.ifBlank { stringResource(R.string.reader_fallback_name) }, style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit_profile)) }
                }
                Text(
                    stringResource(R.string.member_since, stringResource(level.second), Instant.ofEpochMilli(profile.memberSince).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM yyyy", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (next != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { ((hours - level.first) / (next.first - level.first)).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.width(200.dp).height(6.dp),
                        drawStopIndicator = {},
                    )
                    Text(stringResource(R.string.hours_to_level, "%.1f".format(next.first - hours), stringResource(next.second)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Column(Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Status overview
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpenActivity)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing((finished.toFloat() / profile.yearlyBookGoal.coerceAtLeast(1)), Modifier.size(84.dp), stroke = 9.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$finished", style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.of_n, profile.yearlyBookGoal), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.reading_challenge), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (finished >= profile.yearlyBookGoal) stringResource(R.string.challenge_done) else pluralStringResource(R.plurals.books_to_go_year, profile.yearlyBookGoal - finished, profile.yearlyBookGoal - finished),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StreakFlame(streak.current > 0, Modifier.size(32.dp, 40.dp))
                        Text("${streak.current}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MiniStat(com.vdelaar.mylibby.data.formatShortDuration(seconds), stringResource(R.string.stat_read), Modifier.weight(1f))
                MiniStat("$reading", stringResource(R.string.stat_reading), Modifier.weight(1f))
                MiniStat("$favourites", stringResource(R.string.stat_favorites), Modifier.weight(1f))
                MiniStat("$highlights", stringResource(R.string.stat_highlights), Modifier.weight(1f))
            }

            Text(stringResource(R.string.achievements), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            val appState by appContainer().settings.app.collectAsStateWithLifecycle()
            val auto = mapOf(
                "first" to (seconds > 0),
                "fire" to (streak.longest >= 7),
                "habit" to (streak.longest >= 30),
                "ten" to (hours >= 10),
                "finisher" to (finished >= 5),
                "century" to (hours >= 100),
                "annotator" to (highlights >= 25),
                "challenger" to (finished >= profile.yearlyBookGoal),
            )
            val badges = BadgeDef.all.map { d ->
                Badge(d.emoji, stringResource(d.title), stringResource(d.description), appState.badgeOverrides[d.key] ?: auto[d.key] == true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 4) {
                badges.forEach { BadgeTile(it, Modifier.weight(1f)) }
            }

            Spacer(Modifier.height(4.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column {
                    NavRow(Icons.Rounded.Favorite, stringResource(R.string.want_title), navigator::wanted)
                    NavRow(Icons.Rounded.Download, stringResource(R.string.downloads_storage), navigator::downloads)
                    NavRow(Icons.Rounded.Settings, stringResource(R.string.settings), navigator::settings)
                    if (localOnly) NavRow(Icons.Rounded.Hub, stringResource(R.string.settings_integrations), navigator::integrations) else NavRow(Icons.AutoMirrored.Rounded.Logout, stringResource(if (demo) R.string.demo_exit else R.string.sign_out)) { vm.checkUnsynced(); confirmLogout = true }
                }
            }
            Text(
                if (demo) stringResource(R.string.demo_footer)
                else if (opds) stringResource(R.string.opds_mode_body)
                else if (localOnly) stringResource(R.string.local_mode_body)
                else stringResource(R.string.signed_in_as, vm.username, vm.server.removePrefix("https://").removePrefix("http://").trimEnd('/')),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
    }

    if (editing) EditProfileDialog(profile, onDismiss = { editing = false }) { p -> vm.updateProfile { p }; editing = false }
    if (confirmLogout) {
        val unsynced by vm.unsynced.collectAsStateWithLifecycle()
        val syncing by vm.syncing.collectAsStateWithLifecycle()
        val pending = unsynced ?: 0
        AlertDialog(
            onDismissRequest = { if (!syncing) confirmLogout = false },
            title = { Text(stringResource(if (demo) R.string.demo_exit_q else R.string.sign_out_q)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        demo -> Text(stringResource(R.string.demo_exit_body))
                        syncing || unsynced == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.sign_out_syncing))
                        }
                        pending == 0 -> {
                            Text(stringResource(R.string.sign_out_synced), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.sign_out_removed))
                        }
                        else -> {
                            Text(pluralStringResource(R.plurals.sign_out_unsynced, pending, pending), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.sign_out_removed))
                        }
                    }
                    if (!demo) Text(stringResource(R.string.sign_out_stays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (!demo && !syncing && unsynced != null && pending > 0) {
                    TextButton(onClick = vm::syncNow) { Text(stringResource(R.string.sign_out_sync_now)) }
                } else {
                    TextButton(enabled = !syncing && (demo || unsynced != null), onClick = { confirmLogout = false; vm.logout { navigator.toOnboarding() } }) {
                        Text(stringResource(if (demo) R.string.demo_exit else R.string.sign_out))
                    }
                }
            },
            dismissButton = {
                Row {
                    if (!demo && !syncing && unsynced != null && pending > 0) {
                        TextButton(onClick = { confirmLogout = false; vm.logout { navigator.toOnboarding() } }) {
                            Text(stringResource(R.string.sign_out_anyway), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(enabled = !syncing, onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

private data class Badge(val emoji: String, val title: String, val description: String, val earned: Boolean)

@Composable
private fun BadgeTile(b: Badge, modifier: Modifier) {
    val earnedLabel = stringResource(R.string.badge_earned)
    val notEarnedLabel = stringResource(R.string.badge_not_earned)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (b.earned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "${b.title}: ${b.description}. " + if (b.earned) earnedLabel else notEarnedLabel
        },
    ) {
        Column(
            Modifier.height(104.dp).padding(vertical = 10.dp, horizontal = 6.dp).alpha(if (b.earned) 1f else .4f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(b.emoji, fontSize = 28.sp)
            Text(b.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize), color = if (b.earned) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(b.description, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Four equal tiles: same size whatever the numbers are, and the text shrinks to fit instead of wrapping. */
@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.height(76.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                value, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
            )
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize),
            )
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var goal by remember { mutableStateOf(profile.yearlyBookGoal.toString()) }
    val current by appContainer().settings.app.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column {
                val pickPhoto = rememberPhotoPicker()
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileAvatar(current.profile.copy(avatar = avatar), 80.dp, Modifier.clickable(onClick = pickPhoto))
                    Row {
                        TextButton(onClick = pickPhoto) { Text(stringResource(R.string.choose_photo)) }
                        if (current.profile.photoVersion > 0) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val scope = rememberCoroutineScope()
                            val c = appContainer()
                            TextButton(onClick = {
                                scope.launch {
                                    com.vdelaar.mylibby.core.AvatarStore.remove(context, current.profile.photoVersion)
                                    c.settings.updateApp { it.copy(profile = it.profile.copy(photoVersion = 0)) }
                                }
                            }) { Text(stringResource(R.string.remove_photo)) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AvatarChoices.forEach { e ->
                        Surface(
                            shape = CircleShape,
                            color = if (e == avatar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { avatar = e },
                        ) { Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(goal, { goal = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.books_this_year)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(current.profile.copy(name = name.trim().ifBlank { profile.name }, avatar = avatar, yearlyBookGoal = goal.toIntOrNull()?.coerceAtLeast(1) ?: profile.yearlyBookGoal)) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
