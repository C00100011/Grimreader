package com.vdelaar.mylibby.ui.onboarding

import android.Manifest
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.core.datastore.GoalType
import kotlinx.coroutines.launch
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.ProfileAvatar
import com.vdelaar.mylibby.ui.components.rememberPhotoPicker
import com.vdelaar.mylibby.ui.settings.TimePickerDialogButton
import com.vdelaar.mylibby.ui.settings.VoicePickerSection

val AvatarChoices = listOf("📚", "🦉", "🐱", "🦊", "🐻", "🌙", "☕", "🌿", "🚀", "🎧", "🐉", "🌻")

@Composable
fun OnboardingScreen(connectOnly: Boolean = false, onFinished: () -> Unit, onCancel: () -> Unit = {}) {
    val vm = appViewModel(key = "onboarding-$connectOnly") { OnboardingViewModel(it, connectOnly) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    val canGoBack = state.history.isNotEmpty() || connectOnly
    val goBack = { if (state.history.isNotEmpty()) vm.back() else onCancel() }

    // Onboarding is always light; the app follows the device afterwards.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.vdelaar.mylibby.ThemeOverride.forceLight.value = true
        onDispose { com.vdelaar.mylibby.ThemeOverride.forceLight.value = false }
    }

    BackHandler(enabled = canGoBack) { goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), MaterialTheme.colorScheme.surface)
                )
            )
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            if (canGoBack) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                    if (!connectOnly) LinearProgressIndicator(
                        progress = { state.step.slot / OnboardingStep.SLOTS.toFloat() },
                        modifier = Modifier.weight(1f).padding(end = 24.dp),
                    )
                }
            }
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState.slot >= initialState.slot
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "onboarding",
                modifier = Modifier.weight(1f),
            ) { step ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    when (step) {
                        OnboardingStep.WELCOME -> Welcome(onStart = { vm.goTo(OnboardingStep.SOURCE) })
                        OnboardingStep.SOURCE -> SourceStep(state, vm)
                        OnboardingStep.SYNC -> Connect(state, vm)
                        OnboardingStep.CATALOG -> CatalogStep(vm)
                        OnboardingStep.LOCAL -> LocalStep(vm)
                        OnboardingStep.TRENDS -> TrendsStep(vm)
                        OnboardingStep.PROFILE -> ProfileStep(state, vm)
                        OnboardingStep.GOAL -> GoalStep(state, vm)
                        OnboardingStep.VOICES -> VoicesStep { vm.goTo(OnboardingStep.THANKS) }
                        OnboardingStep.THANKS -> ThanksStep { vm.finish(onFinished) }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Welcome(onStart: () -> Unit) {
    Spacer(Modifier.height(56.dp))
    Text("📖", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(24.dp))
    Text(
        stringResource(R.string.welcome_title),
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.ob_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(36.dp))
    Feature("📚", stringResource(R.string.ob_feature_books), stringResource(R.string.ob_feature_books_body))
    Feature("☁️", stringResource(R.string.ob_feature_sync), stringResource(R.string.ob_feature_sync_body))
    Feature("🎧", stringResource(R.string.ob_feature_voice), stringResource(R.string.ob_feature_voice_body))
    Feature("🔥", stringResource(R.string.ob_feature_habit), stringResource(R.string.ob_feature_habit_body))
    Spacer(Modifier.height(36.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.get_started), fontSize = 16.sp) }
}

/** How do you want to read? Three neutral routes, plus the sample books. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SourceStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.ob_source_title), stringResource(R.string.ob_source_body))
    ChoiceCard("☁️", stringResource(R.string.ob_src_sync_title), stringResource(R.string.ob_src_sync_body), onClick = vm::chooseSync)
    Spacer(Modifier.height(12.dp))
    ChoiceCard("🌐", stringResource(R.string.ob_src_catalog_title), stringResource(R.string.ob_src_catalog_body), onClick = vm::chooseCatalog)
    Spacer(Modifier.height(12.dp))
    ChoiceCard("📱", stringResource(R.string.ob_src_local_title), stringResource(R.string.ob_src_local_body), onClick = vm::chooseLocal)
    Spacer(Modifier.height(16.dp))
    state.error?.let {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
        }
    }
    TextButton(onClick = vm::startDemo, enabled = !state.connecting, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.demo_try))
    }
    Text(
        stringResource(R.string.demo_try_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.ob_source_later),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
}

@Composable
private fun ChoiceCard(emoji: String, title: String, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Feature(emoji: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Connect(state: OnboardingState, vm: OnboardingViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val submit = { keyboard?.hide(); focus.clearFocus(); vm.connect() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val noBrowser = stringResource(R.string.err_no_browser)
    androidx.compose.runtime.LaunchedEffect(state.ssoUrl) {
        state.ssoUrl?.let { url ->
            vm.ssoLaunched()
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                .onFailure { vm.update { it.copy(error = noBrowser) } }
        }
    }
    StepTitle(stringResource(R.string.ob_sync_title), stringResource(R.string.ob_sync_body))
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = { v -> vm.update { it.copy(serverUrl = v, error = null) } },
        label = { Text(stringResource(R.string.server_address)) },
        placeholder = { Text("https://books.example.com") },
        leadingIcon = { Icon(Icons.Rounded.Dns, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = { v -> vm.update { it.copy(username = v, error = null) } },
        label = { Text(stringResource(R.string.username)) },
        leadingIcon = { Icon(Icons.Rounded.Person, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = { v -> vm.update { it.copy(password = v, error = null) } },
        label = { Text(stringResource(R.string.password)) },
        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.show_password))
            }
        },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.error != null) {
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { submit() },
        enabled = !state.connecting,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (state.connecting) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ButtonDefaults.buttonColors().contentColor)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.connecting))
        } else Text(stringResource(R.string.sign_in), fontSize = 16.sp)
    }
    Spacer(Modifier.height(12.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = { keyboard?.hide(); focus.clearFocus(); vm.connectWithSso() },
        enabled = !state.connecting,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.sign_in_sso), fontSize = 16.sp) }
    Text(
        stringResource(R.string.sign_in_sso_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.profile_title), stringResource(R.string.profile_body))
    val appState by appContainer().settings.app.collectAsStateWithLifecycle()
    val pickPhoto = rememberPhotoPicker()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(appState.profile.copy(avatar = state.avatar), 96.dp, Modifier.clickable(onClick = pickPhoto))
        Row {
            TextButton(onClick = pickPhoto) { Text(stringResource(R.string.choose_photo)) }
            if (appState.profile.photoVersion > 0) TextButton(onClick = vm::removePhoto) { Text(stringResource(R.string.remove_photo)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AvatarChoices.forEach { emoji ->
            val selected = emoji == state.avatar
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp).clip(CircleShape).clickable { vm.update { it.copy(avatar = emoji) } },
            ) { Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 22.sp) } }
        }
    }
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = state.name,
        onValueChange = { v -> vm.update { it.copy(name = v, error = null) } },
        label = { Text(stringResource(R.string.your_name)) },
        singleLine = true,
        isError = state.error != null,
        supportingText = state.error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.yearly_goal_label, state.yearlyGoal), style = MaterialTheme.typography.titleMedium)
    Slider(
        value = state.yearlyGoal.toFloat(),
        onValueChange = { v -> vm.update { it.copy(yearlyGoal = v.toInt()) } },
        valueRange = 1f..100f,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = vm::saveProfileAndContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.action_continue), fontSize = 16.sp) }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.GoalStep(state: OnboardingState, vm: OnboardingViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.update { it.copy(reminderEnabled = false) }
    }
    StepTitle(stringResource(R.string.goal_title), stringResource(R.string.goal_body))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        GoalType.entries.forEachIndexed { i, type ->
            SegmentedButton(
                selected = state.goalType == type,
                onClick = { vm.update { it.copy(goalType = type, dailyTarget = if (type == GoalType.MINUTES) 20 else 15) } },
                shape = SegmentedButtonDefaults.itemShape(i, GoalType.entries.size),
            ) { Text(stringResource(if (type == GoalType.MINUTES) R.string.minutes else R.string.pages)) }
        }
    }
    Spacer(Modifier.height(28.dp))
    Text(
        "${state.dailyTarget}",
        style = MaterialTheme.typography.displayLarge,
        fontFamily = FontFamily.Serif,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        stringResource(if (state.goalType == GoalType.MINUTES) R.string.minutes_a_day else R.string.pages_a_day),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Slider(
        value = state.dailyTarget.toFloat(),
        onValueChange = { v -> vm.update { it.copy(dailyTarget = v.toInt()) } },
        valueRange = if (state.goalType == GoalType.MINUTES) 5f..120f else 5f..100f,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val presets = if (state.goalType == GoalType.MINUTES) listOf(10, 20, 30, 60) else listOf(10, 20, 30, 50)
        presets.forEach { p ->
            FilterChip(selected = state.dailyTarget == p, onClick = { vm.update { it.copy(dailyTarget = p) } }, label = { Text("$p") })
        }
    }
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.daily_reminder), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.daily_reminder_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = state.reminderEnabled, onCheckedChange = { v ->
            vm.update { it.copy(reminderEnabled = v) }
            if (v && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
    }
    if (state.reminderEnabled) {
        TimePickerDialogButton(state.reminderHour, state.reminderMinute) { h, m -> vm.update { it.copy(reminderHour = h, reminderMinute = m) } }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            if (state.reminderEnabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            vm.saveGoalAndContinue()
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.action_continue), fontSize = 16.sp) }
}

@Composable
private fun VoicesStep(onFinish: () -> Unit) {
    StepTitle(stringResource(R.string.voices_title), stringResource(R.string.ob_voices_body))
    VoicePickerSection()
    Spacer(Modifier.height(24.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.action_continue), fontSize = 16.sp) }
    TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.skip_for_now)) }
}

/** Pick EPUB files from the device and start reading; optional. */
@Composable
private fun LocalStep(vm: OnboardingViewModel) {
    val c = appContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var added by rememberSaveable { mutableStateOf(0) }
    var failed by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            for (uri in uris) {
                runCatching { c.localBooks.import(uri) }.onSuccess { added++ }.onFailure { failed = it.message }
            }
        }
    }
    StepTitle(stringResource(R.string.ob_local_title), stringResource(R.string.ob_local_body))
    androidx.compose.material3.FilledTonalButton(onClick = { picker.launch(com.vdelaar.mylibby.ui.components.ImportMimeTypes) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(stringResource(R.string.ob_local_pick), fontSize = 16.sp)
    }
    if (added > 0) {
        Spacer(Modifier.height(12.dp))
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.ob_local_added, added, added), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
    failed?.let { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.import_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(24.dp))
    Button(onClick = vm::afterSetup, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.action_continue), fontSize = 16.sp) }
}

/** Connect an OPDS catalog. */
@Composable
private fun CatalogStep(vm: OnboardingViewModel) {
    val app by appContainer().settings.app.collectAsStateWithLifecycle()
    StepTitle(stringResource(R.string.ob_catalog_title), stringResource(R.string.ob_catalog_body))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        com.vdelaar.mylibby.ui.settings.OpdsForm(onSaved = {})
    }
    if (app.opdsActive && app.opdsUrl.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.int_opds_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = vm::afterSetup, enabled = app.opdsActive && app.opdsUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(stringResource(R.string.action_continue), fontSize = 16.sp)
    }
}

/** Optional: trending books and suggestions. Guides the user into one of the recommendation sources, or skips. */
@Composable
private fun TrendsStep(vm: OnboardingViewModel) {
    val c = appContainer()
    val hardcoverOn by c.tokens.hardcoverConnected.collectAsStateWithLifecycle()
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    StepTitle(stringResource(R.string.ob_trends_title), stringResource(R.string.ob_trends_body))
    TrendOption(
        selected = choice == "hardcover", emoji = "📈",
        title = stringResource(R.string.ob_trends_hardcover_title), text = stringResource(R.string.ob_trends_hardcover_body),
        onSelect = { choice = "hardcover" },
    ) { com.vdelaar.mylibby.ui.settings.HardcoverKeySection() }
    Spacer(Modifier.height(12.dp))
    TrendOption(
        selected = choice == "openlibrary", emoji = "🆕",
        title = stringResource(R.string.ob_trends_ol_title), text = stringResource(R.string.ob_trends_ol_body),
        onSelect = { choice = "openlibrary" },
    )
    Spacer(Modifier.height(12.dp))
    TrendOption(
        selected = choice == "library", emoji = "💡",
        title = stringResource(R.string.int_recs_local), text = stringResource(R.string.int_recs_local_body),
        onSelect = { choice = "library" },
    )
    Spacer(Modifier.height(24.dp))
    val ready = choice == "library" || choice == "openlibrary" || (choice == "hardcover" && hardcoverOn)
    Button(
        enabled = ready,
        onClick = {
            vm.setRecommendations(
                when (choice) {
                    "hardcover" -> com.vdelaar.mylibby.core.datastore.RecommendationSource.HARDCOVER
                    "openlibrary" -> com.vdelaar.mylibby.core.datastore.RecommendationSource.OPEN_LIBRARY
                    else -> com.vdelaar.mylibby.core.datastore.RecommendationSource.LOCAL
                }
            )
            vm.goTo(OnboardingStep.PROFILE)
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.action_continue), fontSize = 16.sp) }
    TextButton(onClick = { vm.goTo(OnboardingStep.PROFILE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.skip_for_now)) }
}

@Composable
private fun TrendOption(selected: Boolean, emoji: String, title: String, text: String, onSelect: () -> Unit, content: @Composable () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onSelect).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(emoji, fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) content()
        }
    }
}

/** The last step: a thank-you and the way into the app. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ThanksStep(onReady: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text("💛", fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.ob_thanks_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.ob_thanks_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.ob_thanks_sign), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(40.dp))
    Button(onClick = onReady, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.ob_ready), fontSize = 16.sp) }
}
