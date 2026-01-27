// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "whisper_settings")

class UserPreferences(private val context: Context) {

    private val crashPrefs: SharedPreferences =
        context.getSharedPreferences("gpu_crash_detection", Context.MODE_PRIVATE)

    companion object {
        private val LISTEN_MODE_KEY = booleanPreferencesKey("listen_mode_enabled")
        private val TRANSLATE_KEY = booleanPreferencesKey("translate_to_english")
        private val MODEL_KEY = stringPreferencesKey("model")
        private val NUM_THREADS_KEY = intPreferencesKey("num_threads")
        private val GPU_ENABLED_KEY = booleanPreferencesKey("gpu_enabled")
        private val DEFAULT_LANGUAGE_KEY = stringPreferencesKey("default_language")
        private const val KEY_GPU_IN_PROGRESS = "gpu_in_progress"
        private const val KEY_TURBO_LOAD_IN_PROGRESS = "turbo_load_in_progress"
        private val TURBO_MODE_KEY = booleanPreferencesKey("turbo_mode")
        private val TURBO_MODE_SET_KEY = booleanPreferencesKey("turbo_mode_set")
        private const val MIN_RAM_GB_FOR_TURBO_AUTO = 6.0

        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_FRENCH = "fr"
        const val LANGUAGE_SPANISH = "es"
        const val LANGUAGE_GERMAN = "de"
        const val LANGUAGE_ITALIAN = "it"
        const val LANGUAGE_PORTUGUESE = "pt"
        const val LANGUAGE_DUTCH = "nl"
        const val LANGUAGE_JAPANESE = "ja"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_RUSSIAN = "ru"
        const val LANGUAGE_KOREAN = "ko"
        const val LANGUAGE_ARABIC = "ar"
        const val LANGUAGE_POLISH = "pl"
        const val LANGUAGE_TURKISH = "tr"
        const val LANGUAGE_SWEDISH = "sv"

        private fun getModelSizeRank(filename: String): Int {
            val name = filename.lowercase()
            return when {
                "tiny" in name -> 1
                "base" in name -> 2
                "small" in name -> 3
                "medium" in name -> 4
                "large" in name -> 5
                else -> 10 // Unknown models go last
            }
        }

        fun getAvailableModelNames(context: Context): List<String> {
            return try {
                context.assets.list("models")
                    ?.filter { it.endsWith(".bin") }
                    ?.filterNot { it.contains("silero", ignoreCase = true) }
                    ?.sortedBy { getModelSizeRank(it) }
                    ?: emptyList()
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError("UserPreferences", whisperError, critical = false)
                emptyList()
            }
        }

        fun getDefaultModel(context: Context): String {
            val models = getAvailableModelNames(context)
            return when {
                models.isEmpty() -> "ggml-small-q8_0.bin"
                models.size == 1 -> models[0]
                else -> models.last()
            }
        }

        fun getMaxThreads(): Int {
            return Runtime.getRuntime().availableProcessors()
        }

        fun getDefaultNumThreads(gpuEnabled: Boolean): Int {
            if (gpuEnabled) return 1
            return (getMaxThreads() - 1).coerceAtLeast(1)
        }

        fun hasEnoughCoresForTurbo(): Boolean = getMaxThreads() >= 8

        fun getTurboCpuThreads(): Int = (getMaxThreads() - 1).coerceAtMost(8)

        fun getTotalMemoryGB(context: Context): Double {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        }

        fun shouldAutoEnableTurbo(context: Context): Boolean =
            hasEnoughCoresForTurbo() && getTotalMemoryGB(context) >= MIN_RAM_GB_FOR_TURBO_AUTO

        fun getLanguageDisplayName(languageCode: String): String {
            return when (languageCode) {
                LANGUAGE_AUTO -> "Auto-detect"
                LANGUAGE_ENGLISH -> "English"
                LANGUAGE_FRENCH -> "French"
                LANGUAGE_SPANISH -> "Spanish"
                LANGUAGE_GERMAN -> "German"
                LANGUAGE_ITALIAN -> "Italian"
                LANGUAGE_PORTUGUESE -> "Portuguese"
                LANGUAGE_DUTCH -> "Dutch"
                LANGUAGE_JAPANESE -> "Japanese"
                LANGUAGE_CHINESE -> "Chinese"
                LANGUAGE_RUSSIAN -> "Russian"
                LANGUAGE_KOREAN -> "Korean"
                LANGUAGE_ARABIC -> "Arabic"
                LANGUAGE_POLISH -> "Polish"
                LANGUAGE_TURKISH -> "Turkish"
                LANGUAGE_SWEDISH -> "Swedish"
                else -> languageCode
            }
        }

        fun getLanguageListWithAbbreviations(): List<Pair<String, String>> {
            return listOf(
                LANGUAGE_AUTO to "Auto",
                LANGUAGE_ENGLISH to "EN",
                LANGUAGE_FRENCH to "FR",
                LANGUAGE_SPANISH to "ES",
                LANGUAGE_GERMAN to "DE",
                LANGUAGE_ITALIAN to "IT",
                LANGUAGE_PORTUGUESE to "PT",
                LANGUAGE_DUTCH to "NL",
                LANGUAGE_JAPANESE to "JA",
                LANGUAGE_CHINESE to "ZH",
                LANGUAGE_RUSSIAN to "RU",
                LANGUAGE_KOREAN to "KO",
                LANGUAGE_ARABIC to "AR",
                LANGUAGE_POLISH to "PL",
                LANGUAGE_TURKISH to "TR",
                LANGUAGE_SWEDISH to "SV"
            )
        }
    }

    fun getVadModelPath(): String? {
        return try {
            context.assets.list("models")
                ?.firstOrNull { it.contains("silero", ignoreCase = true) }
                ?.let { "models/$it" }
        } catch (e: Exception) {
            null
        }
    }

    fun getDefaultModelForContext(): String = getDefaultModel(context)

    fun getDefaultNumThreadsForContext(gpuEnabled: Boolean = true): Int = getDefaultNumThreads(gpuEnabled)

    val listenModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LISTEN_MODE_KEY] ?: false
    }

    val translateToEnglish: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TRANSLATE_KEY] ?: false
    }

    val model: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MODEL_KEY] ?: getDefaultModel(context)
    }

    val gpuEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GPU_ENABLED_KEY] ?: true
    }

    val turboModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TURBO_MODE_KEY] ?: false
    }

    val turboModeHasBeenSet: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TURBO_MODE_SET_KEY] ?: false
    }

    val numThreads: Flow<Int> = context.dataStore.data.map { preferences ->
        val isGpuEnabled = preferences[GPU_ENABLED_KEY] ?: true
        preferences[NUM_THREADS_KEY] ?: getDefaultNumThreads(isGpuEnabled)
    }

    val defaultLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_LANGUAGE_KEY] ?: LANGUAGE_AUTO
    }

    suspend fun setListenModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LISTEN_MODE_KEY] = enabled
        }
    }

    suspend fun setTranslateToEnglish(translate: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TRANSLATE_KEY] = translate
        }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_KEY] = model
        }
    }

    suspend fun setGpuEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GPU_ENABLED_KEY] = enabled
            preferences[NUM_THREADS_KEY] = getDefaultNumThreads(enabled)
            if (!enabled) {
                preferences[TURBO_MODE_KEY] = false
            }
        }
    }

    suspend fun setTurboModeEnabled(enabled: Boolean, isUserAction: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[TURBO_MODE_KEY] = enabled
            if (isUserAction) {
                preferences[TURBO_MODE_SET_KEY] = true
            }
        }
    }

    suspend fun setNumThreads(numThreads: Int) {
        context.dataStore.edit { preferences ->
            val validThreads = numThreads.coerceIn(1, getMaxThreads())
            preferences[NUM_THREADS_KEY] = validThreads
        }
    }

    suspend fun setDefaultLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_LANGUAGE_KEY] = language
        }
    }

    fun setGpuInProgress(inProgress: Boolean) {
        crashPrefs.edit().putBoolean(KEY_GPU_IN_PROGRESS, inProgress).commit()
    }

    fun isGpuInProgress(): Boolean {
        return crashPrefs.getBoolean(KEY_GPU_IN_PROGRESS, false)
    }

    fun setTurboLoadInProgress(inProgress: Boolean) {
        crashPrefs.edit().putBoolean(KEY_TURBO_LOAD_IN_PROGRESS, inProgress).commit()
    }

    fun isTurboLoadInProgress(): Boolean {
        return crashPrefs.getBoolean(KEY_TURBO_LOAD_IN_PROGRESS, false)
    }

    fun shouldAutoEnableTurboForDevice(): Boolean = shouldAutoEnableTurbo(context)
}
