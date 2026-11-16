package com.ideacode.soundly.debug

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ImportedPreviewInstrumentedTest {
    @Test fun importedVersionShowsRealWaveformAndActionsKeepItsIdentity() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val pcm = ByteBuffer.allocate(44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100 * 3) { pcm.putShort((sin(it * .04) * 12000).toInt().toShort()) }
        val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
        val file = File(app.cacheDir, "i2-imported-source.wav").apply { writeBytes(wav.array()) }
        val imported = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(file))) } as SoundlyResult.Success).value.project
        val project = imported.copy(title = "采访素材", sourceDisplayName = "source-interview.wav")
        assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to project.id, "variantId" to project.original.id))
                }
                waitFor { runCatching { onView(withId(R.id.imported_name)).check(matches(withText("采访素材"))) }.isSuccess }
                onView(withId(R.id.imported_source)).check(matches(withText("来源文件：source-interview.wav")))
                waitFor {
                    val result = runBlocking { sdk.recorder.loadStaticWaveform(project.id, project.original.id) }
                    result is SoundlyResult.Success && result.value.amplitudes.any { it > .1f }
                }
                assertTrue(runBlocking { sdk.recorder.getOrGenerateStaticWaveform(project.id, "missing-version") } is SoundlyResult.Failure)
                val cached = runBlocking { sdk.recorder.getOrGenerateStaticWaveform(project.id, project.original.id) }
                assertTrue(cached is SoundlyResult.Success && cached.value.amplitudes.any { it > .1f })
                val waveformCache = File(app.filesDir, "soundly/projects/${project.id}/waveform/${project.original.id}.bin")
                assertTrue(waveformCache.isFile)
                waveformCache.writeText("corrupt")
                val repaired = runBlocking { sdk.recorder.getOrGenerateStaticWaveform(project.id, project.original.id) }
                assertTrue("A damaged cache must be regenerated from real audio", repaired is SoundlyResult.Success && repaired.value.amplitudes.any { it > .1f })
                capture(app, "F30-light")
                onView(withId(R.id.player_toggle)).perform(scrollTo(), click())
                waitFor { sdk.playback.state.value is PlaybackState.Playing }
                onView(withId(R.id.imported_rename)).perform(scrollTo(), click())
                onView(withId(R.id.rename_input)).perform(replaceText("新的项目名称"), closeSoftKeyboard())
                onView(withText(R.string.save)).perform(click())
                waitFor { runBlocking { (sdk.projects.getProject(project.id) as? SoundlyResult.Success)?.value?.title == "新的项目名称" } }
                onView(withId(R.id.imported_source)).check(matches(withText("来源文件：source-interview.wav")))
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.imported_name)).check(matches(withText("新的项目名称"))) }.isSuccess }
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                waitFor {
                    var night = false
                    scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                    night
                }
                waitFor { runCatching { onView(withId(R.id.imported_name)).check(matches(isDisplayed())) }.isSuccess }
                capture(app, "F30-dark")
                onView(withId(R.id.imported_trim)).perform(scrollTo(), click())
                assertDestination(scenario, R.id.trimFragment, project.id, project.original.id)
                scenario.onActivity { (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.popBackStack() }
                onView(withId(R.id.imported_enhance)).perform(scrollTo(), click())
                assertDestination(scenario, R.id.enhancementSetupFragment, project.id, project.original.id)
                scenario.onActivity { (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.popBackStack() }
                onView(withId(R.id.imported_export)).perform(scrollTo(), click())
                assertDestination(scenario, R.id.exportFragment, project.id, project.original.id)
                scenario.onActivity { (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.popBackStack() }
                onView(withId(R.id.imported_library)).perform(scrollTo(), click())
                waitFor {
                    var destination = 0
                    scenario.onActivity { destination = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                    destination == R.id.libraryFragment
                }
            }
        } finally { runBlocking { sdk.projects.deleteProject(project.id) }; file.delete() }
    }

    private fun assertDestination(scenario: ActivityScenario<MainActivity>, destination: Int, project: String, variant: String) {
        scenario.onActivity { activity ->
            val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
            assertEquals(destination, nav.currentDestination?.id)
            assertEquals(project, nav.currentBackStackEntry?.arguments?.getString("projectId"))
            assertEquals(variant, nav.currentBackStackEntry?.arguments?.getString("variantId"))
        }
    }
    private fun capture(app: SoundlyApplication, name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(200, 3000)
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "i2-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { ui.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for imported preview")
    }
}
