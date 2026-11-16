package com.ideacode.soundly.debug

import android.net.Uri
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.export.*
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
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
class LibraryManagementInstrumentedTest {
 @Test fun searchFilterPreviewAndDeleteKeepExportedAudio() {
  val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
  assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
  val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
  val id = UUID.randomUUID().toString().take(8)
  val pcm = ByteArray(44100 * 2)
  val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
  val file = File(app.cacheDir, "s23-$id.wav").apply { writeBytes(bytes) }
  val project = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(file))) } as SoundlyResult.Success).value.project
  var output: Uri? = null
  try {
   val result = runBlocking { sdk.exporter.export(ExportRequest(project.id, project.original.id, "s23-$id", AudioFormat.WAV, ExportDestination.Downloads, FileConflictStrategy.RENAME)) } as SoundlyResult.Success
   output = Uri.parse(result.value.destinationUri)
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; nav.navigate(R.id.homeFragment); nav.navigate(R.id.libraryFragment) }
    onView(withId(R.id.library_search)).perform(replaceText(project.title), closeSoftKeyboard())
    waitFor { runCatching { onView(withId(R.id.project_title)).check(matches(withText(project.title))) }.isSuccess }
    val dir = File(requireNotNull(app.getExternalFilesDir(null)), "s23-screenshots").apply { mkdirs() }
    File(dir, "F27-light.png").outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    scenario.onActivity { val scroll = it.findViewById<android.widget.HorizontalScrollView>(R.id.library_filters_scroll); scroll.scrollTo(it.findViewById<android.view.View>(R.id.library_filter_recorded).left, 0) }
    onView(withId(R.id.library_filter_recorded)).perform(click())
    waitFor { runCatching { onView(withId(R.id.library_empty)).check(matches(withText(R.string.s2_no_results))) }.isSuccess }
    scenario.onActivity { val scroll = it.findViewById<android.widget.HorizontalScrollView>(R.id.library_filters_scroll); scroll.scrollTo(scroll.getChildAt(0).width, 0) }
    onView(withId(R.id.library_filter_imported)).perform(click())
    waitFor { runCatching { onView(withId(R.id.library_item_play)).check(matches(isDisplayed())) }.isSuccess }
    onView(withId(R.id.library_item_play)).perform(click())
    waitFor { sdk.playback.state.value is com.ideacode.soundly_sdk.api.model.playback.PlaybackState.Playing }
    onView(withId(R.id.project_title)).perform(click())
    waitFor { runCatching { onView(withId(R.id.project_title)).check(matches(withText(project.title))) }.isSuccess }
    openActionBarOverflowOrOptionsMenu(app); onView(withText(R.string.project_delete)).perform(click()); onView(withId(R.id.delete_keep)).perform(scrollTo(), click())
    assertTrue(runBlocking { sdk.projects.getProject(project.id) } is SoundlyResult.Success)
    openActionBarOverflowOrOptionsMenu(app); onView(withText(R.string.project_delete)).perform(click()); onView(withId(R.id.delete_confirm)).perform(scrollTo(), click())
    scenario.recreate()
    waitFor { runBlocking { sdk.projects.getProject(project.id) } is SoundlyResult.Failure }
    waitFor { var dest = 0; scenario.onActivity { dest = (it.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }; dest == R.id.libraryFragment }
    val actual = if (output?.scheme == "file") File(requireNotNull(output?.path)).readBytes() else app.contentResolver.openInputStream(requireNotNull(output))!!.use { it.readBytes() }
    assertArrayEquals(bytes, actual)
   }
  } finally {
   output?.let { if (it.scheme == "file") File(requireNotNull(it.path)).delete() else app.contentResolver.delete(it, null, null) }
   runBlocking { sdk.projects.deleteProject(project.id) }; file.delete()
  }
 }
 private fun waitFor(condition: () -> Boolean) { val end = System.currentTimeMillis()+20000; while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(100) }; fail("Timed out waiting for library") }
}
