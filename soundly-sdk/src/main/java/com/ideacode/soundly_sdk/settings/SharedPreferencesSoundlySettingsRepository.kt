package com.ideacode.soundly_sdk.settings

import android.content.Context
import android.content.SharedPreferences
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettingsRepository
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SharedPreferencesSoundlySettingsRepository(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    ),
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : SoundlySettingsRepository {

    private val mutex = Mutex()
    private val state = MutableStateFlow(readSettings())

    override fun observe(): Flow<SoundlySettings> = state.asStateFlow()

    override suspend fun get(): SoundlyResult<SoundlySettings> =
        SoundlyResult.Success(state.value)

    override suspend fun update(settings: SoundlySettings): SoundlyResult<Unit> =
        mutex.withLock {
            val validation = validate(settings)
            if (validation != null) return@withLock SoundlyResult.Failure(validation)
            persist(settings)
        }

    override suspend fun reset(): SoundlyResult<Unit> = mutex.withLock {
        persist(SoundlySettings())
    }

    private suspend fun persist(settings: SoundlySettings): SoundlyResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val stored = preferences.edit()
                    .putInt(KEY_SCHEMA_VERSION, SETTINGS_SCHEMA_VERSION)
                    .putString(KEY_DEFAULT_SCENE, settings.defaultScene.name)
                    .putString(KEY_RECORDING_QUALITY, settings.recordingQuality.name)
                    .putString(KEY_OUTPUT_FORMAT, settings.outputFormat.name)
                    .putBoolean(KEY_RECORD_IN_BACKGROUND, settings.recordInBackground)
                    .putString(KEY_RECORDING_FORMAT, settings.recordingFormat.name)
                    .putString(KEY_NAMING_STRATEGY, settings.namingStrategy.name)
                    .commit()
                if (!stored) {
                    SoundlyResult.Failure(SoundlyError.SettingsPersistenceFailed())
                } else {
                    state.value = settings
                    SoundlyResult.Success(Unit)
                }
            } catch (error: Exception) {
                SoundlyResult.Failure(SoundlyError.SettingsPersistenceFailed(error))
            }
        }

    private fun readSettings(): SoundlySettings {
        val defaults = SoundlySettings()
        return SoundlySettings(
            defaultScene = preferences.enumValue(KEY_DEFAULT_SCENE, defaults.defaultScene),
            recordingQuality = preferences.enumValue(
                KEY_RECORDING_QUALITY,
                defaults.recordingQuality
            ),
            outputFormat = preferences.enumValue(KEY_OUTPUT_FORMAT, defaults.outputFormat),
            recordInBackground = preferences.getBoolean(
                KEY_RECORD_IN_BACKGROUND,
                defaults.recordInBackground
            ),
            recordingFormat = preferences.enumValue(
                KEY_RECORDING_FORMAT,
                defaults.recordingFormat
            ),
            namingStrategy = preferences.enumValue(
                KEY_NAMING_STRATEGY,
                defaults.namingStrategy
            )
        )
    }

    private fun validate(settings: SoundlySettings): SoundlyError.InvalidRequest? = when {
        settings.outputFormat == AudioFormat.UNKNOWN ->
            SoundlyError.InvalidRequest("outputFormat")

        settings.recordingFormat !in SUPPORTED_RECORDING_FORMATS ->
            SoundlyError.InvalidRequest("recordingFormat")

        else -> null
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(
        key: String,
        default: T
    ): T = getString(key, null)
        ?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
        ?: default

    private companion object {
        const val PREFERENCES_NAME = "soundly_settings"
        const val SETTINGS_SCHEMA_VERSION = 2
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_DEFAULT_SCENE = "default_scene"
        const val KEY_RECORDING_QUALITY = "recording_quality"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_RECORD_IN_BACKGROUND = "record_in_background"
        const val KEY_RECORDING_FORMAT = "recording_format"
        const val KEY_NAMING_STRATEGY = "naming_strategy"
        val SUPPORTED_RECORDING_FORMATS = setOf(AudioFormat.M4A, AudioFormat.AAC)
    }
}
