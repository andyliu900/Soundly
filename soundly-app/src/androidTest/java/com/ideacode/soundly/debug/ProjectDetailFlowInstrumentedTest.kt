package com.ideacode.soundly.debug

import android.net.Uri
import android.widget.RadioGroup
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
class ProjectDetailFlowInstrumentedTest {
 @Test fun detailRenameTrimAndUnknownEnhancementReturnToSelectedVersion() {
  val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
  assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
  val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
  val pcm = ByteArray(44100 * 2 * 4)
  val data = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
  data.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
  val file = File(app.cacheDir, "s23-detail.wav").apply { writeBytes(data.array()) }
  val project = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(file))) } as SoundlyResult.Success).value.project
  try {
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; nav.navigate(R.id.homeFragment); nav.navigate(R.id.libraryFragment); nav.navigate(R.id.projectDetailFragment, bundleOf("projectId" to project.id)) }
    waitFor { runCatching { onView(withId(R.id.project_rename)).check(matches(isDisplayed())) }.isSuccess }
    scenario.onActivity { a -> val group = a.findViewById<RadioGroup>(R.id.project_versions); assertEquals(group.width, group.getChildAt(0).width) }
    onView(withId(R.id.project_rename)).perform(click())
    onView(withId(R.id.rename_input)).perform(replaceText("S23 修改名称"), closeSoftKeyboard())
    onView(withText(R.string.save)).perform(click())
    waitFor { runCatching { onView(withId(R.id.project_title)).check(matches(withText("S23 修改名称"))) }.isSuccess }
    capture(app, "F28-light")
    onView(withId(R.id.project_trim)).perform(scrollTo(), click())
    waitFor { runCatching { onView(withId(R.id.trim_start)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
    onView(withId(R.id.trim_start)).perform(scrollTo(), click()); onView(withId(R.id.trim_time_input)).perform(replaceText("00:01"), closeSoftKeyboard()); onView(withText(android.R.string.ok)).perform(click())
    onView(withId(R.id.trim_end)).perform(scrollTo(), click()); onView(withId(R.id.trim_time_input)).perform(replaceText("00:03"), closeSoftKeyboard()); onView(withText(android.R.string.ok)).perform(click())
    onView(withId(R.id.trim_apply)).perform(scrollTo(), click())
    val trimmed = waitForTrim(sdk, project.id)
    waitFor { var ok = false; scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; ok = nav.currentDestination?.id == R.id.projectDetailFragment && nav.currentBackStackEntry?.savedStateHandle?.get<String>("current_variant_id") == trimmed }; ok }
    scenario.recreate()
    waitFor { var ok = false; scenario.onActivity { a -> val group = a.findViewById<RadioGroup>(R.id.project_versions); ok = group != null && group.indexOfChild(group.findViewById(group.checkedRadioButtonId)) == 1 }; ok }
    onView(withId(R.id.project_export)).perform(scrollTo(), click())
    scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; assertEquals(trimmed, nav.currentBackStackEntry?.arguments?.getString("variantId")); nav.popBackStack() }
    onView(withId(R.id.project_enhance)).perform(scrollTo(), click())
    scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; assertEquals(trimmed, nav.currentBackStackEntry?.arguments?.getString("variantId")); nav.navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to "s23-unknown", "projectId" to project.id, "sourceVariantId" to trimmed, "sourceDestination" to "project_detail")) }
    waitFor { runCatching { onView(withId(R.id.progress_original)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
    onView(withId(R.id.progress_original)).perform(scrollTo(), click())
    waitFor { var ok = false; scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; ok = nav.currentDestination?.id == R.id.projectDetailFragment && nav.currentBackStackEntry?.savedStateHandle?.get<String>("current_variant_id") == trimmed }; ok }
   }
  } finally { runBlocking { sdk.projects.deleteProject(project.id) }; file.delete() }
 }
 private fun waitForTrim(sdk: com.ideacode.soundly_sdk.api.SoundlySdk, id: String): String {
  var found: String? = null
  waitFor { val p = runBlocking { sdk.projects.getProject(id) }; found = (p as? SoundlyResult.Success)?.value?.variants?.firstOrNull()?.id; found != null }
  return requireNotNull(found)
 }
 private fun capture(app: SoundlyApplication, name: String) {
  val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  automation.waitForIdle(200, 3000)
  val dir = File(requireNotNull(app.getExternalFilesDir(null)), "s23-screenshots").apply { mkdirs() }
  File(dir, "$name.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
 }
 private fun waitFor(condition: () -> Boolean) { val end = System.currentTimeMillis() + 25000; while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }; fail("Timed out waiting for project detail") }
}
