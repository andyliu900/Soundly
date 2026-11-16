package com.ideacode.soundly.debug

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingProtectionInstrumentedTest {
    @Test fun maintenanceRejectsConcurrentPublicExport() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val operation = async(kotlinx.coroutines.Dispatchers.Default) {
            sdk.exporter.export(com.ideacode.soundly_sdk.api.model.export.ExportRequest(
                "missing-project", "missing-version", "s1", com.ideacode.soundly_sdk.api.model.domain.AudioFormat.WAV,
                com.ideacode.soundly_sdk.api.model.export.ExportDestination.Downloads)) {
                entered.countDown(); release.await()
            }
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val result = sdk.storage.clearCache()
            assertTrue(result is SoundlyResult.Failure)
            assertEquals("invalid_state", (result as SoundlyResult.Failure).error.code)
        } finally { release.countDown(); operation.await() }
    }
    @Test fun exitConfirmationAndBackgroundPausePreserveTheSession() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.RECORD_AUDIO}").close()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val recorder = sdk.recorder
        assertTrue(recorder.state.value is RecordingState.Idle)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.homeFragment)
                }
                assertTrue(runBlocking { recorder.prepare(RecordingConfig(enableBackgroundNotification = false)) } is SoundlyResult.Success)
                val session = (runBlocking { recorder.start() } as SoundlyResult.Success).value
                scenario.onActivity { activity ->
                    (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.recordingFragment)
                }
                waitFor { runCatching { onView(withId(R.id.recording_pause_resume)).check(matches(isDisplayed())) }.isSuccess }
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.recording_pause_resume)).check(matches(isDisplayed())) }.isSuccess }
                Thread.sleep(900)
                assertTrue("Activity recreation must not be treated as background", recorder.state.value is RecordingState.Recording)
                pressBack()
                waitFor { recorder.state.value is RecordingState.Paused && runCatching { onView(withId(R.id.discard_return)).check(matches(isDisplayed())) }.isSuccess }
                onView(withText(R.string.h4_save_instead)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                val screenshotDir = java.io.File(requireNotNull(app.getExternalFilesDir(null)), "s1-screenshots").apply { mkdirs() }
                java.io.File(screenshotDir, "F11.png").outputStream().use {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertEquals(session.id, (recorder.state.value as RecordingState.Paused).sessionId)
                scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES }
                waitFor {
                    var night = false
                    scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                    night && runCatching { onView(withId(R.id.discard_return)).check(matches(isDisplayed())) }.isSuccess
                }
                java.io.File(screenshotDir, "F11-dark.png").outputStream().use {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.discard_return)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.discard_return)).perform(click())
                assertTrue(recorder.state.value is RecordingState.Paused)
                onView(withId(R.id.recording_pause_resume)).perform(scrollTo(), click())
                waitFor { recorder.state.value is RecordingState.Recording }
                scenario.moveToState(Lifecycle.State.CREATED)
                waitFor { recorder.state.value is RecordingState.Paused }
                assertEquals(session.id, (recorder.state.value as RecordingState.Paused).sessionId)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.storageManagementFragment)
                }
                waitFor { runCatching { onView(withId(R.id.storage_recording_warning)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.storage_delete_all)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                pressBack()
                waitFor { runCatching { onView(withId(R.id.recording_pause_resume)).check(matches(isDisplayed())) }.isSuccess }
                pressBack()
                waitFor { runCatching { onView(withId(R.id.discard_confirm)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.discard_confirm)).perform(click())
                waitFor { recorder.state.value is RecordingState.Idle }
                assertTrue(runBlocking { sdk.projects.getProject(session.projectId) } is SoundlyResult.Failure)
            }
        } finally {
            if (recorder.state.value !is RecordingState.Idle) runBlocking { recorder.discard() }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for recording protection")
    }
}
