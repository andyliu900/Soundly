package com.ideacode.soundly_sdk.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundlySettingsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(
        "soundly-settings-test",
        Context.MODE_PRIVATE
    )

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun updateSurvivesRepositoryRecreationAndReset() = runBlocking {
        val repository = SharedPreferencesSoundlySettingsRepository(context, preferences)
        val updated = SoundlySettings(
            defaultScene = RecordingScene.PODCAST,
            recordingQuality = RecordingQuality.STANDARD,
            outputFormat = AudioFormat.M4A,
            recordInBackground = false,
            recordingFormat = AudioFormat.AAC,
            namingStrategy = RecordingNamingStrategy.TIMESTAMP
        )

        assertTrue(repository.update(updated) is SoundlyResult.Success)
        assertEquals(updated, repository.observe().first())
        val recreated = SharedPreferencesSoundlySettingsRepository(context, preferences)
        assertEquals(updated, (recreated.get() as SoundlyResult.Success).value)

        assertTrue(recreated.reset() is SoundlyResult.Success)
        assertEquals(SoundlySettings(), recreated.observe().first())
    }

    @Test
    fun unknownOutputAndUnsupportedRecordingFormatsAreRejected() = runBlocking {
        val repository = SharedPreferencesSoundlySettingsRepository(context, preferences)

        val unknownOutput = repository.update(
            SoundlySettings(outputFormat = AudioFormat.UNKNOWN)
        )
        val wavRecording = repository.update(
            SoundlySettings(recordingFormat = AudioFormat.WAV)
        )

        assertTrue(unknownOutput is SoundlyResult.Failure)
        assertEquals(
            "outputFormat",
            ((unknownOutput as SoundlyResult.Failure).error as SoundlyError.InvalidRequest).field
        )
        assertTrue(wavRecording is SoundlyResult.Failure)
        assertEquals(
            "recordingFormat",
            ((wavRecording as SoundlyResult.Failure).error as SoundlyError.InvalidRequest).field
        )
    }

    @Test
    fun failedCommitDoesNotPublishUnpersistedSettings() = runBlocking {
        val failingPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(
                        key: String?,
                        value: String?
                    ): SharedPreferences.Editor = apply { delegate.putString(key, value) }

                    override fun putStringSet(
                        key: String?,
                        values: MutableSet<String>?
                    ): SharedPreferences.Editor = apply { delegate.putStringSet(key, values) }

                    override fun putInt(
                        key: String?,
                        value: Int
                    ): SharedPreferences.Editor = apply { delegate.putInt(key, value) }

                    override fun putLong(
                        key: String?,
                        value: Long
                    ): SharedPreferences.Editor = apply { delegate.putLong(key, value) }

                    override fun putFloat(
                        key: String?,
                        value: Float
                    ): SharedPreferences.Editor = apply { delegate.putFloat(key, value) }

                    override fun putBoolean(
                        key: String?,
                        value: Boolean
                    ): SharedPreferences.Editor = apply { delegate.putBoolean(key, value) }

                    override fun remove(key: String?): SharedPreferences.Editor =
                        apply { delegate.remove(key) }

                    override fun clear(): SharedPreferences.Editor = apply { delegate.clear() }

                    override fun commit(): Boolean = false
                }
            }
        }
        val repository = SharedPreferencesSoundlySettingsRepository(context, failingPreferences)
        val initial = repository.observe().first()

        val result = repository.update(
            initial.copy(defaultScene = RecordingScene.MEETING)
        )

        assertTrue(result is SoundlyResult.Failure)
        assertTrue(
            (result as SoundlyResult.Failure).error is
                SoundlyError.SettingsPersistenceFailed
        )
        assertEquals(initial, repository.observe().first())
        assertEquals(initial, (repository.get() as SoundlyResult.Success).value)
    }

    @Test
    fun corruptedEnumValuesFallBackToDefaultsWithoutCrashing() = runBlocking {
        preferences.edit()
            .putString("default_scene", "NOT_A_SCENE")
            .putString("recording_quality", "NOT_A_QUALITY")
            .putString("output_format", "NOT_A_FORMAT")
            .putString("recording_format", "NOT_A_FORMAT")
            .putString("naming_strategy", "NOT_A_STRATEGY")
            .commit()

        val repository = SharedPreferencesSoundlySettingsRepository(context, preferences)

        assertEquals(SoundlySettings(), (repository.get() as SoundlyResult.Success).value)
    }
}
