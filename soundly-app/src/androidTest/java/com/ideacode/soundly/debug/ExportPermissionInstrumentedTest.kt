package com.ideacode.soundly.debug

import android.os.Build
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportPermissionInstrumentedTest {
    @Test fun deniedLegacyStorageShowsRecoveryWithoutCreatingAFile() {
        assumeTrue(Build.VERSION.SDK_INT <= 28)
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString()
        val file = File(app.filesDir, "soundly/projects/$id/original/original.wav")
        requireNotNull(file.parentFile).mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val original = AudioVariant(file.absolutePath, PresetOption.RAW, true, 44100, 1, 1000,
            id = "$id-v", projectId = id, format = AudioFormat.WAV, fileSizeBytes = file.length())
        val project = AudioProject(id, "Permission test", AudioSourceType.RECORDED, null, original,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.exportFragment, bundleOf("projectId" to id, "variantId" to original.id))
                }
                waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.export_root)).perform(swipeUp())
                onView(withId(R.id.export_downloads)).perform(scrollTo(), click())
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                waitFor {
                    val window = automation.rootInActiveWindow
                    val button = window?.findAccessibilityNodeInfosByViewId("com.android.packageinstaller:id/permission_deny_button")?.firstOrNull()
                        ?: window?.findAccessibilityNodeInfosByText("DENY")?.firstOrNull()
                        ?: window?.findAccessibilityNodeInfosByText("拒绝")?.firstOrNull()
                    button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                waitFor { runCatching { onView(withId(R.id.export_error)).check(matches(withText(R.string.h7_permission_error))) }.isSuccess }
                onView(withId(R.id.export_permission_settings)).perform(scrollTo()).check(matches(isDisplayed()))
                assertTrue(file.isFile)
                assertNull(runBlocking { com.ideacode.soundly.feature.export.AndroidExportJournal(app, id, original.id).read() })
            }
        } finally { runBlocking { sdk.projects.deleteProject(id) }; file.delete() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for permission state")
    }
}
