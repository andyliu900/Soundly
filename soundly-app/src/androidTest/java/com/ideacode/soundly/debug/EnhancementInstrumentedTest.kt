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
class EnhancementInstrumentedTest {
    @Test fun completedTaskShowsResultAndExportUsesSelectedVersion() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString(); val file = File(app.filesDir, "h6-$id.wav")
        val pcm = ByteArray(44100 * 2 * 2)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        file.writeBytes(wav.array())
        val variant = AudioVariant(file.absolutePath, PresetOption.RAW, true, 44100, 1, 2000, id = "$id-v", projectId = id, format = AudioFormat.WAV, fileSizeBytes = file.length())
        val project = AudioProject(id, "H6 test", AudioSourceType.RECORDED, RecordingScene.MEETING, variant, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            val editing = app.appContainer.editingGateway
            val task = runBlocking { editing.startEnhancement(id, variant.id, PresetOption.NATURAL_SMOOTH) } as AppResult.Success
            val result = runBlocking { editing.awaitEnhancement(task.value.taskId) }
            assertTrue("Actual enhancement must succeed: $result", result is AppResult.Success)
            val enhancedId = (result as AppResult.Success).value.variantId
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to task.value.taskId, "projectId" to id, "sourceVariantId" to variant.id))
                }
                waitFor {
                    var destination = 0
                    scenario.onActivity { activity -> destination = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                    destination == R.id.enhancementResultFragment
                }
                waitFor { runCatching { onView(withId(R.id.version_enhanced)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.version_enhanced)).check(matches(isChecked()))
                onView(withId(R.id.result_selected_info)).check(matches(withText("增强录音 · WAV")))
                onView(withId(R.id.player_toggle)).perform(scrollTo(), click())
                waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing }
                assertTrue(sdk.playback.seekTo(700) is SoundlyResult.Success)
                onView(withId(R.id.version_original)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.version_original)).check(matches(isChecked())) }.isSuccess }
                waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing }
                val switched = sdk.playback.state.value as com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing
                assertTrue("Switch should retain playback position", switched.positionMs >= 600)

                onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(R.id.exportFragment, nav.currentDestination?.id)
                    assertEquals(variant.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                    nav.popBackStack()
                }
                onView(withId(R.id.version_enhanced)).perform(scrollTo(), click())
                onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(enhancedId, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                }
            }
        } finally { runBlocking { sdk.projects.deleteProject(id) }; file.delete() }
    }
    @Test fun missingTaskRemainsRecoverableAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                nav.navigate(R.id.homeFragment)
                nav.navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to "missing-task", "projectId" to "p", "sourceVariantId" to "v"))
            }
            waitFor { runCatching { onView(withText(R.string.h6_unknown)).check(matches(isDisplayed())) }.isSuccess }
            scenario.recreate()
            waitFor { runCatching { onView(withText(R.string.h6_unknown)).check(matches(isDisplayed())) }.isSuccess }
            onView(withId(R.id.progress_original)).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                assertEquals(R.id.audioPreviewFragment, nav.currentDestination?.id)
                assertEquals("v", nav.currentBackStackEntry?.arguments?.getString("variantId"))
            }
        }
    }
    @Test fun enhancementPagesUseDesignPaletteAndRestoreNightThemeOnExit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES }
            waitFor {
                var night = false
                scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                night
            }
            for (destination in listOf(R.id.enhancementProgressFragment, R.id.enhancementResultFragment)) {
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(destination, bundleOf("taskId" to "palette-test", "projectId" to "missing",
                        "originalVariantId" to "original", "enhancedVariantId" to "enhanced"))
                }
                val message = if (destination == R.id.enhancementProgressFragment) R.string.h6_unknown else R.string.h6_source_missing
                waitFor { runCatching { onView(withText(message)).check(matches(isDisplayed())) }.isSuccess }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.View>(R.id.activity_root)
                    assertEquals(activity.getColor(R.color.enhancement_progress_background), (root.background as android.graphics.drawable.ColorDrawable).color)
                    assertTrue(androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.popBackStack()
                    assertEquals(activity.getColor(R.color.soundly_background), (root.background as android.graphics.drawable.ColorDrawable).color)
                    assertFalse(androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for enhancement state")
    }
}
