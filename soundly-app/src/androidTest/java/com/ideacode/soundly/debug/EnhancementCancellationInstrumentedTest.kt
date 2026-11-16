package com.ideacode.soundly.debug

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
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnhancementCancellationInstrumentedTest {
    @Test fun runningEnhancementCanContinueThenCancelAndReturnToDetail() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString(); val file = File(app.filesDir, "s6-$id.wav")
        val dataSize = 44100 * 2 * 60 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        RandomAccessFile(file, "rw").use { it.write(header); it.setLength(44L + dataSize) }
        val original = AudioVariant(file.absolutePath, PresetOption.RAW, true, 44100, 1, 3_600_000, id = "$id-v", projectId = id, format = AudioFormat.WAV, fileSizeBytes = file.length())
        val project = AudioProject(id, "S6 cancel", AudioSourceType.RECORDED, RecordingScene.MEETING, original, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        val editing = app.appContainer.editingGateway
        var taskId: String? = null
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; nav.navigate(R.id.homeFragment); nav.navigate(R.id.libraryFragment); nav.navigate(R.id.projectDetailFragment, bundleOf("projectId" to id)) }
                val task = runBlocking { editing.startEnhancement(id, original.id, PresetOption.NATURAL_SMOOTH) } as AppResult.Success
                taskId = task.value.taskId
                scenario.onActivity { activity -> val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; nav.navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to task.value.taskId, "projectId" to id, "sourceVariantId" to original.id, "sourceDestination" to "project_detail")) }
                waitFor { runCatching { onView(withId(R.id.enhancement_cancel)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.enhancement_cancel)).perform(scrollTo(), click())
                onView(withId(R.id.cancel_continue)).perform(click())
                onView(withId(R.id.enhancement_cancel)).perform(scrollTo(), click())
                onView(withId(R.id.cancel_confirm)).perform(click())
                waitFor { runBlocking { editing.observeEnhancement(task.value.taskId).first() } is EnhancementTaskState.Cancelled }
                waitFor { var destination = 0; scenario.onActivity { destination = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }; destination == R.id.projectDetailFragment }
                assertTrue(file.exists())
                assertTrue((runBlocking { sdk.projects.getProject(id) } as SoundlyResult.Success).value.variants.isEmpty())
            }
        } finally { taskId?.let { runBlocking { editing.cancelEnhancement(it) } }; runBlocking { sdk.projects.deleteProject(id) }; file.delete() }
    }
    private fun waitFor(condition: () -> Boolean) { val deadline = System.currentTimeMillis() + 30000; while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }; fail("Timed out waiting for enhancement cancellation") }
}
