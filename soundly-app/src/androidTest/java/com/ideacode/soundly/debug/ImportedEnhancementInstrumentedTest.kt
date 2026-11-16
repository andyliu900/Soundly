package com.ideacode.soundly.debug

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ImportedEnhancementInstrumentedTest {
    @Test fun originalAndTrimmedInputsStayPairedThroughEnhancementAndExport() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val pcm = ByteBuffer.allocate(44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100 * 3) { pcm.putShort((kotlin.math.sin(it * .04) * 9000).toInt().toShort()) }
        val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
        val file = File(app.cacheDir, "i4-import.wav").apply { writeBytes(wav.array()) }
        val project = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(file))) } as SoundlyResult.Success).value.project
        val gateway = app.appContainer.editingGateway
        try {
            val trim = runBlocking { gateway.applyTrim(project.id, project.original.id, TrimRange(500, 2500)) } as AppResult.Success
            val before = (runBlocking { sdk.projects.getProject(project.id) } as SoundlyResult.Success).value
            val inputs = (listOf(before.original) + before.variants).associate { it.id to File(it.filePath).readBytes() }
            val sourceIds = listOf(project.original.id, trim.value.variantId)
            sourceIds.forEach { sourceId ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        nav.navigate(R.id.homeFragment)
                        nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to project.id, "variantId" to sourceId))
                    }
                    waitFor { runCatching { onView(withId(R.id.imported_enhance)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                    onView(withId(R.id.imported_enhance)).perform(scrollTo(), click())
                    waitFor { runCatching { onView(withId(R.id.setup_source_title)).check(matches(isDisplayed())) }.isSuccess }
                    if (sourceId != project.original.id) onView(withId(R.id.setup_source_duration)).check(matches(withSubstring("裁剪版本")))
                    onView(withId(R.id.setup_mode_natural)).perform(scrollTo(), click())
                    onView(withId(R.id.enhancement_start)).perform(click())
                    waitFor {
                        var result = false
                        scenario.onActivity { result = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id == R.id.enhancementResultFragment }
                        result
                    }
                    waitFor { runCatching { onView(withId(R.id.version_enhanced)).check(matches(isDisplayed())) }.isSuccess }
                    val updated = (runBlocking { sdk.projects.getProject(project.id) } as SoundlyResult.Success).value
                    (listOf(updated.original) + updated.variants.filter { it.id in inputs }).forEach { input ->
                        assertArrayEquals(inputs.getValue(input.id), File(input.filePath).readBytes())
                    }
                    val output = updated.variants.last { it.sourceVariantId == sourceId && it.variantType == com.ideacode.soundly_sdk.api.model.domain.VariantType.ENHANCED }
                    assertTrue(File(output.filePath).isFile)
                    onView(withId(R.id.player_toggle)).perform(scrollTo(), click())
                    waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing }
                    assertTrue(sdk.playback.seekTo(500) is SoundlyResult.Success)
                    onView(withId(R.id.version_original)).perform(scrollTo(), click())
                    waitFor { runCatching { onView(withId(R.id.version_original)).check(matches(isChecked())) }.isSuccess }
                    waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing }
                    assertTrue((sdk.playback.state.value as com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing).positionMs >= 400)
                    if (sourceId != project.original.id) {
                        onView(withId(R.id.version_original)).check(matches(withText("处理前")))
                        onView(withId(R.id.result_selected_info)).check(matches(withText("裁剪音频 · WAV")))
                    }
                    onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        assertEquals(sourceId, nav.currentBackStackEntry?.arguments?.getString("variantId")); nav.popBackStack()
                    }
                    onView(withId(R.id.version_enhanced)).perform(scrollTo(), click())
                    onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        assertEquals(output.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                    }
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to project.id, "variantId" to project.original.id))
                    nav.currentBackStackEntry?.savedStateHandle?.set("trim_result_variant", trim.value.variantId)
                }
                waitFor { runCatching { onView(withId(R.id.imported_version_title)).check(matches(withText(R.string.imported_trimmed_title))) }.isSuccess }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.enhancementSetupFragment, bundleOf("projectId" to project.id, "variantId" to trim.value.variantId))
                    nav.navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to "missing-i4", "projectId" to project.id, "sourceVariantId" to trim.value.variantId))
                }
                waitFor { runCatching { onView(withId(R.id.progress_original)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.progress_original)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.imported_version_title)).check(matches(withText(R.string.imported_trimmed_title))) }.isSuccess }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(R.id.audioPreviewFragment, nav.currentDestination?.id)
                    assertEquals(project.original.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                    assertEquals(trim.value.variantId, nav.currentBackStackEntry?.savedStateHandle?.get<String>("current_variant_id"))
                    assertFalse(runCatching { nav.getBackStackEntry(R.id.enhancementSetupFragment) }.isSuccess)
                }
            }
        } finally { runBlocking { sdk.projects.deleteProject(project.id) }; file.delete() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for imported enhancement")
    }
}
