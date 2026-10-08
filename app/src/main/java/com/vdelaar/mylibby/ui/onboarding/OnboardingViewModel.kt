package com.vdelaar.mylibby.ui.onboarding

import androidx.lifecycle.ViewModel
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.str
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.core.datastore.BookSearchSource
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.core.datastore.RecommendationSource
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.notifications.ReminderScheduler
import com.vdelaar.mylibby.tts.neural.NeuralCapability
import com.vdelaar.mylibby.tts.neural.NeuralFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The tour: WELCOME → SOURCE (how do you want to read?) → one of SYNC / CATALOG / LOCAL → TRENDS → PROFILE → GOAL
 * → VOICES (only on phones that can run them) → THANKS.
 */
enum class OnboardingStep { WELCOME, SOURCE, SYNC, CATALOG, LOCAL, TRENDS, PROFILE, GOAL, VOICES, THANKS;

    /** Position on the progress bar; the three set-up branches share one slot. */
    val slot: Int
        get() = when (this) {
            WELCOME -> 0
            SOURCE -> 1
            SYNC, CATALOG, LOCAL -> 2
            TRENDS -> 3
            PROFILE -> 4
            GOAL -> 5
            VOICES -> 6
            THANKS -> 7
        }

    companion object { const val SLOTS = 7 }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /** Steps to return to with Back, oldest first. */
    val history: List<OnboardingStep> = emptyList(),
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val avatar: String = "📚",
    val goalType: GoalType = GoalType.MINUTES,
    val dailyTarget: Int = 20,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 30,
    val yearlyGoal: Int = 12,
    /** Set when the browser should be opened for single sign-on; the screen consumes it. */
    val ssoUrl: String? = null,
    /** Opened from Settings: only the sign-in step, then straight back to the app. */
    val connectOnly: Boolean = false,
    /** Whether this phone can run the on-device natural voice; decides if the voices step is shown. */
    val neuralFit: NeuralFit = NeuralFit.OK,
    /** Connect-only mode is done: the screen leaves. */
    val finished: Boolean = false,
)

/** The step after the goal: voices only where the phone can run them. */
internal fun stepAfterGoal(fit: NeuralFit): OnboardingStep = if (fit == NeuralFit.OK) OnboardingStep.VOICES else OnboardingStep.THANKS

class OnboardingViewModel(private val c: AppContainer, connectOnly: Boolean = false) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingState(
            step = when {
                connectOnly -> OnboardingStep.SYNC
                c.tokens.accessToken != null -> OnboardingStep.PROFILE
                else -> OnboardingStep.WELCOME
            },
            connectOnly = connectOnly,
            serverUrl = c.settings.server.value.grimmoryUrl,
            username = c.settings.server.value.username,
            name = c.settings.app.value.profile.name,
            avatar = c.settings.app.value.profile.avatar,
            yearlyGoal = c.settings.app.value.profile.yearlyBookGoal,
            goalType = c.settings.goal.value.type,
            dailyTarget = c.settings.goal.value.dailyTarget,
            neuralFit = NeuralCapability.check(c.appContext),
        )
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        // Single sign-on finishes outside this screen (browser -> MainActivity -> AuthRepository).
        viewModelScope.launch {
            c.auth.loggedIn.collect { signedIn ->
                val s = _state.value
                if (signedIn && s.step == OnboardingStep.SYNC && !s.connecting) onConnected(s.username)
            }
        }
        viewModelScope.launch {
            c.auth.oidcError.collect { e -> if (e != null) _state.update { it.copy(connecting = false, error = e) } }
        }
    }

    private fun onConnected(fallbackName: String) {
        c.sync.schedulePeriodic()
        viewModelScope.launch {
            c.settings.updateApp { it.copy(localOnly = false, demo = false) }
            runCatching { c.library.refreshFavourites() }
        }
        val user = c.settings.server.value.username.ifBlank { fallbackName }
        _state.update { it.copy(connecting = false, password = "", username = user, name = it.name.ifBlank { user.replaceFirstChar { ch -> ch.uppercase() } }) }
        if (_state.value.connectOnly) {
            viewModelScope.launch {
                c.settings.updateApp { it.copy(onboardingDone = true) }
                _state.update { it.copy(finished = true) }
            }
        } else afterSetup()
    }

    fun connectWithSso() {
        val s = _state.value
        if (s.serverUrl.isBlank()) {
            _state.update { it.copy(error = str(R.string.err_enter_server)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            val start = c.auth.startOidc(s.serverUrl)
            _state.update { it.copy(connecting = false, error = start.error, ssoUrl = start.url) }
        }
    }

    /** Try the app without any server: the bundled free books are added as on-device books. */
    fun startDemo() {
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            runCatching { c.demo.start() }
                .onSuccess {
                    _state.update { s -> s.copy(connecting = false, name = s.name.ifBlank { str(R.string.demo_reader_name) }) }
                    goTo(OnboardingStep.PROFILE)
                }
                .onFailure { e -> _state.update { it.copy(connecting = false, error = e.message ?: str(R.string.err_login_generic, "demo")) } }
        }
    }

    /** Read only the books on this device: nothing is synced. */
    fun chooseLocal() {
        viewModelScope.launch {
            c.settings.updateApp { it.copy(localOnly = true, opdsActive = false, demo = false) }
            goTo(OnboardingStep.LOCAL)
        }
    }

    fun chooseSync() = goTo(OnboardingStep.SYNC)

    fun chooseCatalog() {
        // A catalog without a library server: Grimmory stays off, the downloads become books on this device.
        viewModelScope.launch { c.settings.updateApp { it.copy(localOnly = true, demo = false, deviceBooks = true) } }
        goTo(OnboardingStep.CATALOG)
    }

    /** The library is set up (server, catalog or this device): on to the recommendations. */
    fun afterSetup() = replaceWith(OnboardingStep.TRENDS)

    fun setRecommendations(source: RecommendationSource) {
        viewModelScope.launch { c.settings.updateApp { it.copy(recommendations = source) } }
    }

    fun ssoLaunched() = _state.update { it.copy(ssoUrl = null) }

    fun update(f: (OnboardingState) -> OnboardingState) = _state.update(f)

    fun goTo(step: OnboardingStep) = _state.update { it.copy(step = step, history = it.history + it.step, error = null) }

    /** Move on without leaving the current step in the Back history (set-up screens). */
    private fun replaceWith(step: OnboardingStep) = _state.update { it.copy(step = step, error = null) }

    fun back() = _state.update { s ->
        val prev = s.history.lastOrNull() ?: return@update s
        s.copy(step = prev, history = s.history.dropLast(1), error = null, connecting = false)
    }

    fun connect() {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = str(R.string.error_fill_login)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            val error = c.auth.login(s.serverUrl, s.username, s.password)
            if (error == null) onConnected(s.username) else _state.update { it.copy(connecting = false, error = error) }
        }
    }

    fun saveProfileAndContinue() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(error = str(R.string.error_name_needed)) }
            return
        }
        viewModelScope.launch {
            c.settings.updateApp { it.copy(profile = it.profile.copy(name = s.name.trim(), avatar = s.avatar, yearlyBookGoal = s.yearlyGoal)) }
            goTo(OnboardingStep.GOAL)
        }
    }

    fun saveGoalAndContinue() {
        val s = _state.value
        viewModelScope.launch {
            c.settings.updateGoal {
                it.copy(type = s.goalType, dailyTarget = s.dailyTarget, reminderEnabled = s.reminderEnabled, reminderHour = s.reminderHour, reminderMinute = s.reminderMinute)
            }
            ReminderScheduler.schedule(c.appContext, c.settings.goal.value)
            goTo(stepAfterGoal(s.neuralFit))
        }
    }

    fun removePhoto() {
        viewModelScope.launch {
            val old = c.settings.app.value.profile.photoVersion
            com.vdelaar.mylibby.core.AvatarStore.remove(c.appContext, old)
            c.settings.updateApp { it.copy(profile = it.profile.copy(photoVersion = 0)) }
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            // A new reader gets only what they chose: book search from a server stays off until it is set up in Integrations.
            c.settings.updateApp { it.copy(onboardingDone = true, bookSearch = it.bookSearch ?: if (_state.value.connectOnly) null else BookSearchSource.NONE) }
            onDone()
        }
    }
}
