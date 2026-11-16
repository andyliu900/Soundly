package com.ideacode.soundly.debug

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.RadioGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportedExportInstrumentedTest {
    @SdkSuppress(minSdkVersion = 29)
    @Test fun allVersionsExportShareAndReopenFromLibrary() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val token = UUID.randomUUID().toString().take(8)
        val pcm = ByteBuffer.allocate(44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100 * 3) { pcm.putShort((kotlin.math.sin(it * .03) * 11000).toInt().toShort()) }
        val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
        val file = File(app.cacheDir, "i5-$token.wav").apply { writeBytes(wav.array()) }
        val project = (runBlocking { sdk.importer.importAudio(AudioImportRequest(Uri.fromFile(file))) } as SoundlyResult.Success).value.project
        val gateway = app.appContainer.editingGateway
        val exported = mutableListOf<Uri>()
        try {
            val trim = (runBlocking { gateway.applyTrim(project.id, project.original.id, TrimRange(500, 2500)) } as AppResult.Success).value
            val task = (runBlocking { gateway.startEnhancement(project.id, trim.variantId, PresetOption.NATURAL_SMOOTH) } as AppResult.Success).value
            val enhanced = (runBlocking { gateway.awaitEnhancement(task.taskId) } as AppResult.Success).value
            val updated = (runBlocking { sdk.projects.getProject(project.id) } as SoundlyResult.Success).value
            val variants = listOf(updated.original, updated.variants.first { it.id == trim.variantId }, updated.variants.first { it.id == enhanced.variantId })
            val labels = listOf("原始音频", "裁剪版本", "增强版本")
            variants.forEachIndexed { index, version ->
                val expected = File(version.filePath).readBytes()
                val name = "soundly-i5-$token-$index"
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        nav.navigate(R.id.homeFragment)
                        nav.navigate(R.id.exportFragment, bundleOf("projectId" to project.id, "variantId" to version.id))
                    }
                    waitFor { runCatching { onView(withId(R.id.export_name)).check(matches(isDisplayed())) }.isSuccess }
                    onView(withId(R.id.export_source_version)).check(matches(withText(labels[index])))
                    onView(withId(R.id.export_share_detail)).check(matches(withText(app.getString(R.string.h7_share_detail, "WAV"))))
                    onView(withId(R.id.export_name)).perform(replaceText(name), closeSoftKeyboard())
                    onView(withId(R.id.export_downloads)).perform(scrollTo(), click())
                    waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                    onView(withId(R.id.success_version)).check(matches(withText(labels[index])))
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = app.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf("$name.wav"), null)!!.use {
                        assertTrue(it.moveToFirst())
                        val savedUri = ContentUris.withAppendedId(collection, it.getLong(0))
                        exported.add(savedUri)
                        assertTrue(it.getString(1) in listOf("audio/wav", "audio/x-wav"))
                        savedUri
                    }
                    assertArrayEquals(expected, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                    val share = (runBlocking { gateway.prepareShare(project.id, version.id) } as AppResult.Success).value
                    assertEquals(Intent.ACTION_SEND, share.action); assertEquals("audio/wav", share.type)
                    assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    val shareUri = if (Build.VERSION.SDK_INT >= 33) share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    assertArrayEquals(expected, app.contentResolver.openInputStream(requireNotNull(shareUri))!!.use { it.readBytes() })
                    scenario.recreate()
                    waitFor { runCatching { onView(withId(R.id.success_name)).check(matches(withText("$name.wav"))) }.isSuccess }
                    onView(withId(R.id.success_library)).perform(scrollTo(), click())
                    waitFor { runCatching { onView(withId(R.id.library_search)).check(matches(isDisplayed())) }.isSuccess }
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        assertFalse(runCatching { nav.getBackStackEntry(R.id.exportFragment) }.isSuccess)
                    }
                    onView(withId(R.id.library_search)).perform(replaceText(project.title), closeSoftKeyboard())
                    waitFor { runCatching { onView(allOf(withId(R.id.project_title), withText(project.title))).check(matches(isDisplayed())) }.isSuccess }
                    onView(allOf(withId(R.id.project_title), withText(project.title))).perform(click())
                    waitFor { runCatching { onView(withId(R.id.project_versions)).check(matches(isDisplayed())) }.isSuccess }
                    scenario.onActivity { activity ->
                        val group = activity.findViewById<RadioGroup>(R.id.project_versions)
                        assertEquals(3, group.childCount)
                        assertTrue((group.getChildAt(1) as RadioButton).contentDescription.toString().contains("裁剪版本 1（来源：原始音频）"))
                        assertTrue((group.getChildAt(2) as RadioButton).contentDescription.toString().contains("增强版本 1（来源：裁剪版本 1）"))
                        group.check(group.getChildAt(index).id)
                    }
                    waitFor {
                        var selected = -1
                        scenario.onActivity { val group = it.findViewById<RadioGroup>(R.id.project_versions); selected = group.indexOfChild(group.findViewById(group.checkedRadioButtonId)) }
                        selected == index
                    }
                    scenario.recreate()
                    waitFor {
                        var selected = -1
                        scenario.onActivity { val group = it.findViewById<RadioGroup>(R.id.project_versions); selected = group.indexOfChild(group.findViewById(group.checkedRadioButtonId)) }
                        selected == index
                    }
                    androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(app)
                    onView(withText(R.string.project_open_preview)).perform(click())
                    scenario.onActivity { activity ->
                        val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                        assertEquals(version.id, nav.currentBackStackEntry?.arguments?.getString("variantId"))
                    }
                }
            }
        } finally {
            exported.forEach { app.contentResolver.delete(it, null, null) }
            runBlocking { sdk.projects.deleteProject(project.id) }
            file.delete()
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for imported export")
    }
}
