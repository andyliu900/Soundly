package com.ideacode.soundly.debug

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnhancementSetupInstrumentedTest {
    @Test fun selectionSurvivesRecreationAndStartsTheSelectedPreset() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString()
        val file = File(app.filesDir, "setup-$id.wav")
        val pcm = ByteArray(44100 * 2 * 2)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        file.writeBytes(wav.array())
        val variant = AudioVariant(file.absolutePath, PresetOption.RAW, true, 44100, 1, 2000, id = "$id-v", projectId = id, format = AudioFormat.WAV, fileSizeBytes = file.length())
        val project = AudioProject(id, "Setup test", AudioSourceType.RECORDED, RecordingScene.MEETING, variant, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.enhancementSetupFragment, bundleOf("projectId" to id, "variantId" to variant.id))
                }
                waitFor { runCatching { onView(withId(R.id.setup_mode_auto)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.setup_mode_auto)).check(matches(isChecked()))
                scenario.onActivity { activity ->
                    val seek = activity.findViewById<android.widget.SeekBar>(R.id.setup_seek)
                    val arguments = android.os.Bundle().apply {
                        putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f)
                    }
                    assertTrue(seek.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
                }
                waitFor { (sdk.playback.state.value as? PlaybackState.Paused)?.positionMs == 1000L }
                onView(withId(R.id.setup_seek)).perform(swipeRight())
                waitFor { ((sdk.playback.state.value as? PlaybackState.Paused)?.positionMs ?: 0L) >= 1600L }
                onView(withId(R.id.setup_play)).perform(scrollTo(), click())
                waitFor { sdk.playback.state.value is PlaybackState.Playing || sdk.playback.state.value is PlaybackState.Completed }
                for (mode in listOf(R.id.setup_mode_speech, R.id.setup_mode_noise, R.id.setup_mode_natural)) {
                    onView(withId(mode)).perform(scrollTo(), click())
                    onView(withId(mode)).check(matches(isChecked()))
                }
                onView(withId(R.id.setup_mode_natural)).check(matches(isChecked()))
                onView(withId(R.id.setup_mode_auto)).check(matches(org.hamcrest.Matchers.not(isChecked())))
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.setup_mode_natural)).check(matches(isChecked())) }.isSuccess }
                onView(withId(R.id.enhancement_start)).perform(click())
                waitFor { sdk.enhancer.tasks.value.values.any { it.task.projectId == id && it.task.preset == PresetOption.NATURAL_SMOOTH } }
                val submitted = sdk.enhancer.tasks.value.values.first { it.task.projectId == id }
                assertEquals(variant.id, submitted.task.sourceVariantId)
                assertEquals(PresetOption.NATURAL_SMOOTH, submitted.task.preset)
                runBlocking { sdk.enhancer.await(submitted.task.taskId) }
            }
        } finally { runBlocking { sdk.projects.deleteProject(id) }; file.delete() }
    }

    @Test fun setupRespectsDayAndNightThemes() {
        for (night in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.delegate.localNightMode = if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO }
            waitFor {
                var correct = false
                scenario.onActivity { correct = (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == night }
                correct
            }
            scenario.onActivity { activity ->
                val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                nav.navigate(R.id.homeFragment)
                nav.navigate(R.id.enhancementSetupFragment, bundleOf("projectId" to "missing-setup", "variantId" to "missing"))
            }
            waitFor { runCatching { onView(withId(R.id.setup_error)).check(matches(isDisplayed())) }.isSuccess }
            onView(withId(R.id.setup_footer)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.View>(R.id.setup_root)
                assertEquals(activity.getColor(R.color.preview_background), (root.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(!night, WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for setup state")
    }
}
