package com.vdelaar.mylibby.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** All user preferences, each group stored as a JSON blob and exposed as a hot StateFlow. */
class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val store = context.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val initial: Preferences = runBlocking { store.data.first() }

    val reader: StateFlow<ReaderSettings> = flowOf(KEY_READER, ReaderSettings.serializer(), ReaderSettings(), scope)
    val tts: StateFlow<TtsSettings> = flowOf(KEY_TTS, TtsSettings.serializer(), TtsSettings(), scope)
    val goal: StateFlow<GoalSettings> = flowOf(KEY_GOAL, GoalSettings.serializer(), GoalSettings(), scope)
    val server: StateFlow<ServerSettings> = flowOf(KEY_SERVER, ServerSettings.serializer(), ServerSettings(), scope)
    val speed: StateFlow<SpeedReadSettings> = flowOf(KEY_SPEED, SpeedReadSettings.serializer(), SpeedReadSettings(), scope)
    val app: StateFlow<AppState> = flowOf(KEY_APP, AppState.serializer(), AppState(), scope)
    val syncStatus: StateFlow<SyncSettings> = flowOf(KEY_SYNC, SyncSettings.serializer(), SyncSettings(), scope)

    suspend fun updateReader(f: (ReaderSettings) -> ReaderSettings) = update(KEY_READER, ReaderSettings.serializer(), reader.value, f)
    suspend fun updateTts(f: (TtsSettings) -> TtsSettings) = update(KEY_TTS, TtsSettings.serializer(), tts.value, f)
    suspend fun updateGoal(f: (GoalSettings) -> GoalSettings) = update(KEY_GOAL, GoalSettings.serializer(), goal.value, f)
    suspend fun updateServer(f: (ServerSettings) -> ServerSettings) = update(KEY_SERVER, ServerSettings.serializer(), server.value, f)
    suspend fun updateSpeed(f: (SpeedReadSettings) -> SpeedReadSettings) = update(KEY_SPEED, SpeedReadSettings.serializer(), speed.value, f)
    suspend fun updateApp(f: (AppState) -> AppState) = update(KEY_APP, AppState.serializer(), app.value, f)
    /** One-time upgrade of the library settings (see [migratedLibraryModel]); cheap to call on every start. */
    suspend fun migrate() {
        if (app.value.libraryModel < 2) updateApp { it.migratedLibraryModel() }
    }

    suspend fun updateSyncStatus(f: (SyncSettings) -> SyncSettings) = update(KEY_SYNC, SyncSettings.serializer(), syncStatus.value, f)

    private fun <T> decode(prefs: Preferences, key: Preferences.Key<String>, s: KSerializer<T>, default: T): T =
        prefs[key]?.let { runCatching { json.decodeFromString(s, it) }.getOrNull() } ?: default

    private fun <T> flowOf(
        key: Preferences.Key<String>,
        s: KSerializer<T>,
        default: T,
        scope: CoroutineScope,
    ): StateFlow<T> = store.data
        .map { decode(it, key, s, default) }
        .stateIn(scope, SharingStarted.Eagerly, decode(initial, key, s, default))

    private suspend fun <T> update(key: Preferences.Key<String>, s: KSerializer<T>, current: T, f: (T) -> T) {
        store.edit { prefs ->
            val base = decode(prefs, key, s, current)
            prefs[key] = json.encodeToString(s, f(base))
        }
    }

    private companion object {
        val KEY_READER = stringPreferencesKey("reader")
        val KEY_TTS = stringPreferencesKey("tts")
        val KEY_GOAL = stringPreferencesKey("goal")
        val KEY_SERVER = stringPreferencesKey("server")
        val KEY_SPEED = stringPreferencesKey("speed")
        val KEY_APP = stringPreferencesKey("app")
        val KEY_SYNC = stringPreferencesKey("sync_status")
    }
}
