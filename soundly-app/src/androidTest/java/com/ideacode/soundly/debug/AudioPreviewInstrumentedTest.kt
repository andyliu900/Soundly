package com.ideacode.soundly.debug

import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
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
class AudioPreviewInstrumentedTest {
    @Test fun realProjectCanBePlayedRenamedAndPassedToSetup() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString()
        val file = File(app.filesDir, "h5-test-$id.wav")
        val pcm = ByteArray(44100 * 2 * 2)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        file.writeBytes(wav.array())
        val variant = AudioVariant(file.absolutePath, PresetOption.RAW, true, 44100, 1, 2000, id = "$id-v", projectId = id, format = AudioFormat.WAV, fileSizeBytes = file.length())
        val marker = AudioMarker("$id-marker", id, 700, "试听标记", System.currentTimeMillis())
        val project = AudioProject(id, "Preview test", AudioSourceType.RECORDED, RecordingScene.MEETING, variant, markers = listOf(marker), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to id, "variantId" to variant.id))
                }
                waitFor { runCatching { onView(withText("Preview test")).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.player_toggle)).perform(scrollTo(), click())
                waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing || sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Completed }
                onView(withText("试听标记")).perform(scrollTo(), click())
                waitFor {
                    when (val state = sdk.playback.state.value) {
                        is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing -> state.positionMs >= 650
                        is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Paused -> state.positionMs >= 650
                        else -> false
                    }
                }
                onView(withId(R.id.preview_rename)).perform(scrollTo(), click())
                onView(withId(R.id.rename_input)).perform(replaceText("Renamed recording"), closeSoftKeyboard())
                onView(withText(R.string.save)).perform(click())
                waitFor { runBlocking { (sdk.projects.getProject(id) as? SoundlyResult.Success)?.value?.title == "Renamed recording" } }
                onView(withId(R.id.preview_adjust)).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(R.id.enhancementSetupFragment, nav.currentDestination?.id)
                    assertEquals(variant.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                    nav.popBackStack()
                }
                onView(withId(R.id.preview_enhance)).perform(scrollTo(), click())
                waitFor {
                    var destination = 0
                    scenario.onActivity { activity ->
                        destination = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0
                    }
                    destination == R.id.enhancementProgressFragment || destination == R.id.enhancementResultFragment
                }
            }
        } finally { runBlocking { sdk.projects.deleteProject(id) }; file.delete() }
    }
    @Test fun previewRespectsLightAndDarkThemes() {
        for (night in listOf(false, true)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.delegate.localNightMode = if (night) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO }
                waitFor {
                    var matches = false
                    scenario.onActivity { matches = (it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) == night }
                    matches
                }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to "missing-preview", "variantId" to "missing"))
                }
                waitFor { runCatching { onView(withId(R.id.preview_error)).check(matches(isDisplayed())) }.isSuccess }
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<android.view.View>(R.id.preview_root)
                    assertEquals(activity.getColor(R.color.preview_background), (preview.background as android.graphics.drawable.ColorDrawable).color)
                    assertEquals(!night, androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for preview state")
    }
}
