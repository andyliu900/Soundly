package com.ideacode.soundly.debug

import android.content.ContentUris
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
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
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportEndToEndInstrumentedTest {
    @Test
    fun systemPickerToTrimEnhanceExportAndLibrary() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val name = "soundly-i6-${UUID.randomUUID().toString().take(8)}"
        var projectId: String? = null
        var exportUri: android.net.Uri? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.navigate(R.id.homeFragment)
                }
                onView(withId(R.id.home_import_audio)).perform(click())
                waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", true) == true }
                fun findText(value: String): AccessibilityNodeInfo? = automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(value)?.firstOrNull { it.text?.toString() == value }
                fun findDrawer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    node ?: return null
                    val description = node.contentDescription?.toString().orEmpty()
                    if (description.contains("Show roots", true) || description.contains("显示根", true) || description.contains("导航抽屉", true)) return node
                    for (i in 0 until node.childCount) findDrawer(node.getChild(i))?.let { return it }
                    return null
                }
                fun clickNode(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    var target = node
                    while (target != null) {
                        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        target = target.parent
                    }
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) return false
                    val now = android.os.SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    val resultDown = automation.injectInputEvent(down, true)
                    android.os.SystemClock.sleep(50)
                    val up = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    val result = resultDown && automation.injectInputEvent(up, true)
                    down.recycle(); up.recycle()
                    return result
                }
                if (findText("i6-picker-audio.wav") == null) {
                    waitFor { findDrawer(automation.rootInActiveWindow) != null }
                    assertTrue(requireNotNull(findDrawer(automation.rootInActiveWindow)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    waitFor { findText("Soundly I6 测试") != null }
                    assertTrue(clickNode(findText("Soundly I6 测试")))
                }
                waitFor { findText("i6-picker-audio.wav") != null }
                assertTrue(clickNode(findText("i6-picker-audio.wav")))
                if (android.os.Build.VERSION.SDK_INT <= 28) {
                    android.os.SystemClock.sleep(100)
                    val open = findText("Open") ?: findText("OPEN") ?: findText("打开")
                    if (open != null) clickNode(open) else findText("i6-picker-audio.wav")?.let { clickNode(it) }
                }
                waitFor { runCatching { onView(withId(R.id.imported_trim)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                scenario.onActivity { activity ->
                    val controller = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    projectId = controller.currentBackStackEntry?.arguments?.getString("projectId")
                }
                val id = requireNotNull(projectId)
                val imported = (runBlocking { sdk.projects.getProject(id) } as SoundlyResult.Success).value
                assertEquals("i6-picker-audio.wav", imported.sourceDisplayName)
                val originalBytes = File(imported.original.filePath).readBytes()
                onView(withId(R.id.imported_trim)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.trim_start)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.trim_start)).perform(scrollTo(), click())
                onView(withId(R.id.trim_time_input)).perform(replaceText("00:00.500"), closeSoftKeyboard())
                onView(withText(android.R.string.ok)).perform(click())
                onView(withId(R.id.trim_end)).perform(scrollTo(), click())
                onView(withId(R.id.trim_time_input)).perform(replaceText("00:02.500"), closeSoftKeyboard())
                onView(withText(android.R.string.ok)).perform(click())
                onView(withId(R.id.trim_apply)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.imported_version_title)).check(matches(withText(R.string.imported_trimmed_title))) }.isSuccess }
                onView(withId(R.id.imported_enhance)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.setup_source_title)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.setup_mode_natural)).perform(scrollTo(), click())
                onView(withId(R.id.enhancement_start)).perform(click())
                waitFor { runCatching { onView(withId(R.id.version_enhanced)).check(matches(isDisplayed())) }.isSuccess }
                val project = (runBlocking { sdk.projects.getProject(id) } as SoundlyResult.Success).value
                val trimmed = project.variants.single { it.variantType == com.ideacode.soundly_sdk.api.model.domain.VariantType.TRIMMED }
                val enhanced = project.variants.single { it.variantType == com.ideacode.soundly_sdk.api.model.domain.VariantType.ENHANCED }
                assertEquals(imported.original.id, trimmed.sourceVariantId); assertEquals(trimmed.id, enhanced.sourceVariantId)
                assertArrayEquals(originalBytes, File(project.original.filePath).readBytes())
                onView(withId(R.id.enhancement_result_export)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.export_name)).perform(replaceText(name), closeSoftKeyboard())
                onView(withId(R.id.export_downloads)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                exportUri = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    app.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf("$name.wav"), null)!!.use {
                        assertTrue(it.moveToFirst()); ContentUris.withAppendedId(collection, it.getLong(0))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.net.Uri.fromFile(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Soundly/$name.wav"))
                }
                val output = requireNotNull(exportUri)
                val bytes = if (output.scheme == "file") File(requireNotNull(output.path)).readBytes()
                    else app.contentResolver.openInputStream(output)!!.use { it.readBytes() }
                assertArrayEquals(File(enhanced.filePath).readBytes(), bytes)
                onView(withId(R.id.success_library)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.library_search)).check(matches(isDisplayed())) }.isSuccess }
                scenario.onActivity { activity ->
                    val controller = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertFalse(runCatching { controller.getBackStackEntry(R.id.exportFragment) }.isSuccess)
                }
            }
        } finally {
            exportUri?.let { if (it.scheme == "file") File(requireNotNull(it.path)).delete() else app.contentResolver.delete(it, null, null) }
            projectId?.let { runBlocking { sdk.projects.deleteProject(it) } }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out during picker-to-export flow")
    }
}
