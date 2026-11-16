package com.ideacode.soundly.debug

import android.content.Intent
import android.content.ContentUris
import android.os.Environment
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.TextView
import android.view.View
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.api.model.export.ExportRequest
import com.ideacode.soundly_sdk.api.model.export.ExportDestination
import com.ideacode.soundly_sdk.api.model.export.FileConflictStrategy
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {
    @Test fun selectedVersionExportsRealFileSurvivesRecreationAndReturnsToLibrary() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val id = UUID.randomUUID().toString()
        val name = "会议录音-${id.take(8)}"
        val originalFile = File(app.filesDir, "soundly/projects/$id/original/original.wav")
        val enhancedFile = File(app.filesDir, "soundly/projects/$id/variants/$id-enhanced.wav")
        fun writeWav(file: File, sample: Byte) {
            requireNotNull(file.parentFile).mkdirs()
            val pcm = ByteArray(44100 * 2) { sample }
            val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
            file.writeBytes(wav.array())
        }
        writeWav(originalFile, 0); writeWav(enhancedFile, 2)
        val original = AudioVariant(originalFile.absolutePath, PresetOption.RAW, true, 44100, 1, 1000, id = "$id-original", projectId = id, format = AudioFormat.WAV, fileSizeBytes = originalFile.length())
        val enhanced = original.copy(filePath = enhancedFile.absolutePath, id = "$id-enhanced", isOriginal = false,
            variantType = VariantType.ENHANCED, presetOption = PresetOption.AI, sourceVariantId = original.id)
        val project = AudioProject(id, "产品周会 H7", AudioSourceType.RECORDED, RecordingScene.MEETING, original, variants = listOf(enhanced), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        var exportedUri: Uri? = null
        var duplicateUri: Uri? = null
        try {
            assertTrue(runBlocking { sdk.projects.saveProject(project) } is SoundlyResult.Success)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO }
                waitFor { var day = false; scenario.onActivity { day = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_NO }; day }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    nav.navigate(R.id.homeFragment)
                    nav.navigate(R.id.exportFragment, bundleOf("projectId" to id, "variantId" to enhanced.id))
                }
                waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(isDisplayed())) }.isSuccess }
                onView(withId(R.id.export_source_version)).check(matches(withText(R.string.h7_source_enhanced)))
                onView(withId(R.id.export_name)).perform(replaceText(name), closeSoftKeyboard())
                capture(app, "F24-light")
                scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES }
                waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(withText(name))) }.isSuccess }
                waitFor { var night = false; scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }; night }
                capture(app, "F24-dark")
                scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO }
                waitFor { var day = false; scenario.onActivity { day = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_NO }; day }
                waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(withText(name))) }.isSuccess }
                onView(withId(R.id.export_name)).perform(closeSoftKeyboard())
                val needsStorage = Build.VERSION.SDK_INT <= 28 && listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE).any {
                    androidx.core.content.ContextCompat.checkSelfPermission(app, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                onView(withId(R.id.export_root)).perform(swipeUp())
                onView(withId(R.id.export_downloads)).perform(scrollTo(), click())
                if (needsStorage) allowLegacyStorage(app)
                try {
                    waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                } catch (e: AssertionError) {
                    scenario.onActivity { activity ->
                        val message = activity.findViewById<TextView>(R.id.export_error)?.text
                        val invalid = activity.findViewById<View>(R.id.export_name_error)?.visibility
                        val result = activity.findViewById<TextView>(R.id.success_name)?.text
                        throw AssertionError("Export did not complete: error=$message nameErrorVisibility=$invalid result=$result", e)
                    }
                    throw e
                }
                capture(app, "F25-light")
                exportedUri = if (Build.VERSION.SDK_INT >= 29) {
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    app.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf("$name.wav"), null)!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        ContentUris.withAppendedId(collection, cursor.getLong(0))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    Uri.fromFile(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Soundly/$name.wav"))
                }
                val uri = requireNotNull(exportedUri)
                assertTrue(runBlocking { com.ideacode.soundly.feature.export.AndroidExportedFileVerifier(app).exists(uri.toString()) })
                val journal = com.ideacode.soundly.feature.export.AndroidExportJournal(app, id, enhanced.id)
                assertEquals(uri.toString(), runBlocking { journal.read() }?.result?.destinationUri)
                val exported = if (uri.scheme == "file") File(requireNotNull(uri.path)).readBytes() else app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertArrayEquals(enhancedFile.readBytes(), exported)
                val player = android.media.MediaPlayer()
                try {
                    if (uri.scheme == "file") player.setDataSource(requireNotNull(uri.path)) else player.setDataSource(app, uri)
                    player.prepare()
                    assertTrue("Exported audio must have the expected duration", player.duration in 900..1100)
                    player.start()
                    assertTrue("Exported audio must be playable", player.isPlaying)
                } finally { player.release() }
                val shared = runBlocking { app.appContainer.editingGateway.prepareShare(id, enhanced.id) } as AppResult.Success
                assertEquals(Intent.ACTION_SEND, shared.value.action)
                assertTrue(shared.value.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val shareUri = if (Build.VERSION.SDK_INT >= 33) shared.value.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") shared.value.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                assertArrayEquals(enhancedFile.readBytes(), app.contentResolver.openInputStream(requireNotNull(shareUri))!!.use { it.readBytes() })
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                if (Build.VERSION.SDK_INT >= 29) {
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    app.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf("$name.wav"), null)!!.use { assertEquals(1, it.count) }
                }
                val duplicate = runBlocking { sdk.exporter.export(ExportRequest(id, enhanced.id, name, AudioFormat.WAV, ExportDestination.Downloads, FileConflictStrategy.RENAME)) } as SoundlyResult.Success
                duplicateUri = Uri.parse(duplicate.value.destinationUri)
                assertEquals("$name (1).wav", duplicate.value.displayName)
                scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES }
                waitFor { var night = false; scenario.onActivity { night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }; night }
                waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                capture(app, "F25-dark")
                onView(withId(R.id.export_root)).perform(swipeUp())
                onView(withId(R.id.success_library)).perform(scrollTo(), click())
                waitFor { var destination = 0; scenario.onActivity { activity -> destination = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.currentDestination?.id ?: 0 }; destination == R.id.libraryFragment }
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertEquals(R.id.libraryFragment, nav.currentDestination?.id)
                }
                assertNull(runBlocking { journal.read() })
                waitFor { runCatching { onView(withText("产品周会 H7")).check(matches(isDisplayed())) }.isSuccess }
                pressBack()
                scenario.onActivity { activity ->
                    val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                    assertNotEquals(R.id.exportFragment, nav.currentDestination?.id)
                }
            }
        } finally {
            listOfNotNull(exportedUri, duplicateUri).forEach { uri -> if (uri.scheme == "file") File(requireNotNull(uri.path)).delete() else app.contentResolver.delete(uri, null, null) }
            runBlocking { sdk.projects.deleteProject(id) }
            originalFile.delete(); enhancedFile.delete()
        }
    }
    private fun allowLegacyStorage(app: SoundlyApplication) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        waitFor {
            val granted = listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
                androidx.core.content.ContextCompat.checkSelfPermission(app, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!granted) {
                val window = automation.rootInActiveWindow
                val button = window?.findAccessibilityNodeInfosByViewId("com.android.packageinstaller:id/permission_allow_button")?.firstOrNull()
                    ?: window?.findAccessibilityNodeInfosByText("ALLOW")?.firstOrNull()
                    ?: window?.findAccessibilityNodeInfosByText("允许")?.firstOrNull()
                button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            granted
        }
    }
    private fun capture(app: SoundlyApplication, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "h7-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for export")
    }
}
