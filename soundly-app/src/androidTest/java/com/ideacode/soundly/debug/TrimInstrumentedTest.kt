package com.ideacode.soundly.debug

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
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

@RunWith(AndroidJUnit4::class)
class TrimInstrumentedTest {
    @Test fun importedWavSelectionPreviewAndApplyReturnToSamePreview() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val pcm = ByteBuffer.allocate(44100 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100 * 4) { i -> pcm.putShort(if (i < 44100 || i >= 44100 * 3) 1000 else 12000) }
        val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
        val source = File(app.cacheDir, "i3-source.wav").apply { writeBytes(wav.array()) }
        val project = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(source))) } as SoundlyResult.Success).value.project
        val originalBytes = File(project.original.filePath).readBytes()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to project.id, "variantId" to project.original.id))
                }
                waitFor { runCatching { onView(withId(R.id.imported_trim)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.imported_trim)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.trim_start)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                val from = CoordinatesProvider { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val inset = (view as com.google.android.material.slider.RangeSlider).trackSidePadding
                    floatArrayOf(location[0] + inset.toFloat(), location[1] + view.height / 2f)
                }
                val to = CoordinatesProvider { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val inset = (view as com.google.android.material.slider.RangeSlider).trackSidePadding
                    floatArrayOf(location[0] + inset + (view.width - inset * 2) * .25f, location[1] + view.height / 2f)
                }
                onView(withId(R.id.trim_range)).perform(scrollTo(), GeneralSwipeAction(Swipe.SLOW, from, to, Press.FINGER))
                waitFor { runCatching { onView(withId(R.id.trim_start)).check(matches(org.hamcrest.Matchers.not(withText("起点\n00:00")))) }.isSuccess }
                val endFrom = CoordinatesProvider { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val inset = (view as com.google.android.material.slider.RangeSlider).trackSidePadding
                    floatArrayOf(location[0] + view.width - inset.toFloat(), location[1] + view.height / 2f)
                }
                val endTo = CoordinatesProvider { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val inset = (view as com.google.android.material.slider.RangeSlider).trackSidePadding
                    floatArrayOf(location[0] + inset + (view.width - inset * 2) * .75f, location[1] + view.height / 2f)
                }
                onView(withId(R.id.trim_range)).perform(GeneralSwipeAction(Swipe.SLOW, endFrom, endTo, Press.FINGER))
                waitFor { runCatching { onView(withId(R.id.trim_end)).check(matches(org.hamcrest.Matchers.not(withText("终点\n00:04")))) }.isSuccess }
                onView(withId(R.id.trim_start)).perform(scrollTo(), click())
                onView(withId(R.id.trim_time_input)).perform(replaceText("00:01"), closeSoftKeyboard())
                onView(withText(android.R.string.ok)).perform(click())
                onView(withId(R.id.trim_end)).perform(scrollTo(), click())
                onView(withId(R.id.trim_time_input)).perform(replaceText("00:03"), closeSoftKeyboard())
                onView(withText(android.R.string.ok)).perform(click())
                onView(withId(R.id.trim_retained)).check(matches(withText("保留时长 · 00:02")))
                scenario.onActivity { activity ->
                    val slider = activity.findViewById<com.google.android.material.slider.RangeSlider>(R.id.trim_range)
                    assertEquals(activity.getString(R.string.i3_range_description), slider.contentDescription.toString())
                    val node = slider.accessibilityNodeProvider?.createAccessibilityNodeInfo(android.view.View.NO_ID)
                    assertNotNull("Both slider handles should expose accessibility nodes", node)
                    assertTrue("Both slider handles should be reachable", requireNotNull(node).childCount >= 2)
                    assertTrue(activity.findViewById<android.view.View>(R.id.trim_start).isClickable)
                    assertTrue(activity.findViewById<android.view.View>(R.id.trim_end).isClickable)
                }
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.trim_retained)).check(matches(withText("保留时长 · 00:02"))) }.isSuccess }
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    landscape && runCatching { onView(withId(R.id.trim_retained)).check(matches(withText("保留时长 · 00:02"))) }.isSuccess
                }
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitFor {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    portrait && runCatching { onView(withId(R.id.trim_retained)).check(matches(withText("保留时长 · 00:02"))) }.isSuccess
                }
                capture(app, "F17-light")
                onView(withId(R.id.trim_play)).perform(scrollTo(), click())
                waitFor { sdk.playback.state.value is PlaybackState.Playing }
                waitFor { (sdk.playback.state.value as? PlaybackState.Paused)?.positionMs?.let { it in 900L..1100L } == true }
                val stopped = sdk.playback.state.value as PlaybackState.Paused
                assertTrue("Selection preview must return to its start", stopped.positionMs in 900..1100)
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                waitFor {
                    var night = false
                    scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                    night
                }
                waitFor { runCatching { onView(withId(R.id.trim_retained)).check(matches(withText("保留时长 · 00:02"))) }.isSuccess }
                capture(app, "F17-dark")
                onView(withId(R.id.trim_apply)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.imported_version_title)).check(matches(withText(R.string.imported_trimmed_title))) }.isSuccess }
                val updated = (runBlocking { sdk.projects.getProject(project.id) } as SoundlyResult.Success).value
                assertEquals(1, updated.variants.size)
                val trimmed = updated.variants.single()
                assertEquals(project.original.id, trimmed.sourceVariantId)
                assertEquals(1000L, trimmed.trimStartMs); assertEquals(3000L, trimmed.trimEndMs)
                assertTrue(trimmed.durationMs in 1950..2050)
                assertArrayEquals(originalBytes, File(updated.original.filePath).readBytes())
                assertArrayEquals(originalBytes.copyOfRange(44 + 88200, 44 + 88200 * 3), File(trimmed.filePath).readBytes().copyOfRange(44, 44 + 88200 * 2))
                val player = android.media.MediaPlayer()
                try { player.setDataSource(trimmed.filePath); player.prepare(); assertTrue(player.duration in 1950..2050); player.start(); assertTrue(player.isPlaying) }
                finally { player.release() }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(R.id.audioPreviewFragment, nav.currentDestination?.id)
                    assertFalse("Trim destination must be removed", runCatching { nav.getBackStackEntry(R.id.trimFragment) }.isSuccess)
                }
                onView(withId(R.id.imported_export)).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(trimmed.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                }
            }
        } finally { runBlocking { sdk.projects.deleteProject(project.id) }; source.delete() }
    }

    private fun capture(app: SoundlyApplication, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "i3-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for trim")
    }
}
