package com.ideacode.soundly.debug

import android.Manifest
import android.net.Uri
import android.os.Build
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
import com.ideacode.soundly.feature.export.AndroidExportJournal
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the actual app navigation and SDK recording, processing and export on an isolated device. */
@RunWith(AndroidJUnit4::class)
class MainFlowInstrumentedTest {
    @Test fun firstUseRecordsEnhancesAndSavesThenReturnsToLibrary() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        val prefs = app.appContainer.appPreferences
        val previousOnboarding = prefs.hasCompletedOnboarding
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun grant(permission: String) { automation.executeShellCommand("pm grant ${app.packageName} $permission").close() }
        grant(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) {
            grant(Manifest.permission.READ_EXTERNAL_STORAGE)
            grant(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        prefs.hasCompletedOnboarding = false
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        var projectId: String? = null
        var variantId: String? = null
        var exported: Uri? = null
        var recordingStarted = false
        val name = "h8-${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun destination(): Int {
                    var id = 0
                    scenario.onActivity { id = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                    return id
                }
                waitFor("onboarding") { destination() == R.id.onboardingFragment }
                onView(withId(R.id.onboarding_continue)).perform(click())
                waitFor("home") { destination() == R.id.homeFragment }
                assertTrue(prefs.hasCompletedOnboarding)
                onView(withId(R.id.home_new_recording)).perform(click())
                waitFor("recording preparation") { destination() == R.id.prepareRecordingFragment }
                waitFor("start enabled") { runCatching { onView(withId(R.id.prepare_start)).check(matches(isEnabled())) }.isSuccess }
                onView(withId(R.id.prepare_start)).perform(scrollTo(), click())
                waitFor("recording") { destination() == R.id.recordingFragment && sdk.recorder.state.value is RecordingState.Recording }
                recordingStarted = true
                scenario.recreate()
                waitFor("recording after recreation") { destination() == R.id.recordingFragment && sdk.recorder.state.value is RecordingState.Recording }
                onView(withId(R.id.recording_marker)).perform(scrollTo(), click())
                waitFor("marker") { sdk.recorder.markers.value.isNotEmpty() }
                onView(withId(R.id.recording_pause_resume)).perform(scrollTo(), click())
                waitFor("paused") { sdk.recorder.state.value is RecordingState.Paused }
                waitFor("resume enabled") { runCatching { onView(withId(R.id.recording_pause_resume)).check(matches(isEnabled())) }.isSuccess }
                onView(withId(R.id.recording_pause_resume)).perform(scrollTo(), click())
                waitFor("resumed") { sdk.recorder.state.value is RecordingState.Recording }
                Thread.sleep(2500)
                onView(withId(R.id.recording_finish)).perform(scrollTo(), click())
                waitFor("recording preview") { destination() == R.id.audioPreviewFragment }
                recordingStarted = false
                scenario.onActivity { activity ->
                    val args = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentBackStackEntry?.arguments
                    projectId = args?.getString("projectId")
                    variantId = args?.getString("variantId")
                }
                val recordedId = requireNotNull(projectId)
                val sourceId = requireNotNull(variantId)
                val project = (runBlocking { sdk.projects.getProject(recordedId) } as SoundlyResult.Success).value
                assertEquals(sourceId, project.original.id)
                assertTrue(File(project.original.filePath).length() > 0)
                waitFor("preview ready") { runCatching { onView(withId(R.id.preview_enhance)).check(matches(isEnabled())) }.isSuccess }
                onView(withId(R.id.preview_enhance)).perform(scrollTo(), click())
                waitFor("enhancement result", 60000) { destination() == R.id.enhancementResultFragment }
                waitFor("enhanced version") { runCatching { onView(withId(R.id.version_enhanced)).check(matches(isChecked())) }.isSuccess }
                onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                waitFor("export") { destination() == R.id.exportFragment }
                scenario.onActivity { activity ->
                    val args = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentBackStackEntry?.arguments
                    variantId = args?.getString("variantId")
                }
                assertNotEquals(sourceId, variantId)
                waitFor("export configuration") { runCatching { onView(withId(R.id.export_name)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.export_name)).perform(replaceText(name), closeSoftKeyboard())
                onView(withId(R.id.export_downloads)).perform(scrollTo(), click())
                waitFor("export success", 60000) { runCatching { onView(withId(R.id.success_name)).check(matches(isDisplayed())) }.isSuccess }
                val result = runBlocking { AndroidExportJournal(app, recordedId, requireNotNull(variantId)).read() }?.result
                exported = Uri.parse(requireNotNull(result).destinationUri)
                assertTrue(result.displayName.startsWith(name))
                val player = android.media.MediaPlayer()
                try {
                    if (exported?.scheme == "file") player.setDataSource(requireNotNull(exported?.path)) else player.setDataSource(app, requireNotNull(exported))
                    player.prepare()
                    assertTrue(player.duration > 0)
                    player.start()
                    assertTrue(player.isPlaying)
                } finally { player.release() }
                scenario.recreate()
                waitFor("restored export success") { runCatching { onView(withId(R.id.success_name)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.success_library)).perform(scrollTo(), click())
                waitFor("library") { destination() == R.id.libraryFragment }
                assertTrue(runBlocking { sdk.projects.getProject(recordedId) } is SoundlyResult.Success)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("returning user home") {
                    var id = 0
                    scenario.onActivity { id = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                    id == R.id.homeFragment
                }
            }
        } finally {
            if (recordingStarted && sdk.recorder.state.value !is RecordingState.Idle) runBlocking { sdk.recorder.discard() }
            exported?.let { if (it.scheme == "file") File(requireNotNull(it.path)).delete() else app.contentResolver.delete(it, null, null) }
            projectId?.let { runBlocking { sdk.projects.deleteProject(it) } }
            prefs.hasCompletedOnboarding = previousOnboarding
        }
    }

    private fun waitFor(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for $label")
    }
}
