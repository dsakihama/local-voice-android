package dev.dean.voice.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Extension property: single DataStore instance per application. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_settings")

/**
 * Typed settings repository backed by DataStore.
 * All reads are Flows — collect them in a ViewModel.
 */
class AppSettingsRepository(private val context: Context) {

    // ── Keys ────────────────────────────────────────────────────────

    private object Keys {
        val AUTO_STOP_SILENCE_MS  = intPreferencesKey("auto_stop_silence_ms")
        val MAX_DICTATION_SEC     = intPreferencesKey("max_dictation_sec")
        val SELECTED_LANGUAGE     = stringPreferencesKey("selected_language")
        val ENABLE_ACCESSIBILITY  = booleanPreferencesKey("enable_accessibility")
        val ENABLE_IN_CONTEXT     = booleanPreferencesKey("enable_in_context_learning")
        val ENABLE_LOCAL_ANALYTICS = booleanPreferencesKey("enable_local_analytics")
        val CLEANUP_MODEL         = stringPreferencesKey("cleanup_model")
    }

    // ── Defaults ────────────────────────────────────────────────────

    val autoStopSilenceMs: Flow<Int> = context.dataStore.data.map {
        it[Keys.AUTO_STOP_SILENCE_MS] ?: 2_000
    }

    val maxDictationSec: Flow<Int> = context.dataStore.data.map {
        it[Keys.MAX_DICTATION_SEC] ?: 60
    }

    val selectedLanguage: Flow<String> = context.dataStore.data.map {
        it[Keys.SELECTED_LANGUAGE] ?: "en-US"
    }

    val accessibilityEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENABLE_ACCESSIBILITY] ?: false
    }

    val inContextLearningEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENABLE_IN_CONTEXT] ?: true
    }

    val localAnalyticsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENABLE_LOCAL_ANALYTICS] ?: false
    }

    /** Active cleanup model, stored as the CleanupModel enum name. Default: E2B. */
    val cleanupModel: Flow<String> = context.dataStore.data.map {
        it[Keys.CLEANUP_MODEL] ?: "E2B"
    }

    // ── Writers ────────────────────────────────────────────────────

    suspend fun setAutoStopSilenceMs(ms: Int) = context.dataStore.edit {
        it[Keys.AUTO_STOP_SILENCE_MS] = ms
    }

    suspend fun setMaxDictationSec(sec: Int) = context.dataStore.edit {
        it[Keys.MAX_DICTATION_SEC] = sec
    }

    suspend fun setSelectedLanguage(language: String) = context.dataStore.edit {
        it[Keys.SELECTED_LANGUAGE] = language
    }

    suspend fun setAccessibilityEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.ENABLE_ACCESSIBILITY] = enabled
    }

    suspend fun setInContextLearningEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.ENABLE_IN_CONTEXT] = enabled
    }

    suspend fun setLocalAnalyticsEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.ENABLE_LOCAL_ANALYTICS] = enabled
    }

    suspend fun setCleanupModel(name: String) = context.dataStore.edit {
        it[Keys.CLEANUP_MODEL] = name
    }
}
