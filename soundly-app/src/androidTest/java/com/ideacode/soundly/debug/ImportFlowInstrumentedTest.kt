package com.ideacode.soundly.debug

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.navigation.fragment.NavHostFragment
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.matches
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.feature.importing.ImportJournal
import com.ideacode.soundly.feature.importing.ImportState
import com.ideacode.soundly.feature.importing.ImportViewModel
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly_sdk.api.model.importer.AudioImportResult
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import kotlinx.coroutines.CompletableDeferred
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
class ImportFlowInstrumentedTest {
    @Test fun appRestartRecoversCommittedImportAndOpensItsExactVersion() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val previousOnboarding = app.appContainer.appPreferences.hasCompletedOnboarding
        val journal = ImportJournal(app)
        assertNull(journal.read())
        val pcm = ByteArray(44100 * 2)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        val source = File(app.cacheDir, "i1-recovery.wav").apply { writeBytes(wav.array()) }
        val id = UUID.randomUUID().toString()
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        var projectId: String? = null
        try {
            assertTrue(journal.write(id))
            val result = (runBlocking { app.appContainer.editingGateway.importAudio(id, Uri.fromFile(source)) } as AppResult.Success).value
            projectId = result.project.id
            app.appContainer.appPreferences.hasCompletedOnboarding = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor {
                    var matched = false
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        val args = nav.currentBackStackEntry?.arguments
                        matched = nav.currentDestination?.id == R.id.audioPreviewFragment && args?.getString("projectId") == result.project.id && args.getString("variantId") == result.project.original.id
                    }
                    matched
                }
                waitFor { journal.read() == null }
                waitFor { runCatching { onView(withId(R.id.imported_source)).check(matches(withText(R.string.imported_source_unknown))) }.isSuccess }
            }
        } finally {
            journal.read()?.let(journal::clear)
            projectId?.let { runBlocking { sdk.projects.deleteProject(it) } }
            app.appContainer.appPreferences.hasCompletedOnboarding = previousOnboarding
            source.delete()
        }
    }
    @Test fun microphoneDenialImportEntryDoesNotRequireMicrophoneAccess() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousCount = app.appContainer.appPreferences.microphoneRequestCount
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        try {
            app.appContainer.appPreferences.microphoneRequestCount = 1
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun destination(): Int {
                    var id = 0
                    scenario.onActivity { id = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                    return id
                }
                scenario.onActivity { (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.homeFragment) }
                onView(withId(R.id.home_new_recording)).perform(click())
                waitFor { runCatching { onView(withId(R.id.permission_import)).perform(click()) }.isSuccess }
                waitFor { destination() == R.id.importFragment }
                waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor { destination() == R.id.homeFragment }
            }
        } finally {
            app.appContainer.appPreferences.microphoneRequestCount = previousCount
        }
    }
    @Test fun restoredPageWaitsForInProcessImportBeforeInspecting() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        val journal = ImportJournal(app)
        assertNull(journal.read())
        val gate = CompletableDeferred<Unit>()
        var inspections = 0
        val gateway = object : EditingGateway by app.appContainer.editingGateway {
            override suspend fun importAudio(operationId: String, uri: Uri): AppResult<AudioImportResult> {
                gate.await()
                return AppResult.Failure(com.ideacode.soundly.common.error.AppError("import_failed", true))
            }
            override suspend fun inspectImport(operationId: String): AppResult<ImportOperationStatus> {
                inspections++
                return AppResult.Success(ImportOperationStatus.Interrupted)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var first: ImportViewModel
        lateinit var restored: ImportViewModel
        try {
            instrumentation.runOnMainSync {
                first = ImportViewModel(SavedStateHandle(), journal, gateway)
                first.start(Uri.parse("content://test/audio"))
                restored = ImportViewModel(SavedStateHandle(), journal, gateway)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, inspections)
            assertTrue(restored.state.value is ImportState.Working)
            gate.complete(Unit)
            waitFor { restored.state.value is ImportState.Interrupted }
            assertTrue(inspections > 0)
        } finally { journal.read()?.let(journal::clear) }
    }
    @Test fun homeAndLibraryOpenOnePickerAndCancellationReturnsToEntry() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun destination(): Int {
                var id = 0
                scenario.onActivity { id = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }
                return id
            }
            scenario.onActivity { activity ->
                val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                nav.navigate(R.id.homeFragment)
            }
            onView(withId(R.id.home_import_audio)).perform(click())
            waitFor { destination() == R.id.importFragment }
            waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { destination() == R.id.homeFragment }
            // Navigation can complete before the system picker finishes its exit animation.
            waitFor { automation.windows.none { it.root?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true } }
            // Its fading surface can remain touch-occluding after it leaves the accessibility window list.
            Thread.sleep(1000)
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.libraryFragment)
            }
            onView(withId(R.id.library_import_audio)).perform(click())
            waitFor { destination() == R.id.importFragment }
            waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { destination() == R.id.libraryFragment }
        }
    }
    @Test fun importIsSingleAndRestoresFromDurableJournal() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val journal = ImportJournal(app)
        assertNull("Run on an isolated test installation", journal.read())
        val pcm = ByteArray(44100 * 2)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        val source = File(app.cacheDir, "i1-flow.wav").apply { writeBytes(wav.array()) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ImportViewModel(SavedStateHandle(), journal, app.appContainer.editingGateway)
        var completed: ImportState.Completed? = null
        try {
            instrumentation.runOnMainSync { model.start(Uri.fromFile(source)); model.start(Uri.fromFile(source)) }
            waitFor { model.state.value is ImportState.Completed }
            completed = model.state.value as ImportState.Completed
            assertEquals(completed.operationId, journal.read())
            val restored = ImportViewModel(SavedStateHandle(), journal, app.appContainer.editingGateway)
            waitFor { restored.state.value is ImportState.Completed }
            val state = restored.state.value as ImportState.Completed
            assertEquals(completed.projectId, state.projectId)
            assertEquals(completed.variantId, state.variantId)
            val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
            val project = (runBlocking { sdk.projects.getProject(state.projectId) } as SoundlyResult.Success).value
            assertArrayEquals(source.readBytes(), File(project.original.filePath).readBytes())
            assertTrue(restored.acknowledgeSuccess(state.operationId))
            assertNull(journal.read())
        } finally {
            journal.read()?.let(journal::clear)
            completed?.let { runBlocking { (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value.projects.deleteProject(it.projectId) } }
            source.delete()
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for import")
    }
}
