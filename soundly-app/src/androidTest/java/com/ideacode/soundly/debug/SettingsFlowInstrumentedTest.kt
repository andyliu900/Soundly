package com.ideacode.soundly.debug

import android.widget.Spinner
import android.widget.RadioGroup
import java.io.File
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
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFlowInstrumentedTest {
    @Test fun settingsPersistAndPrivacyHelpRemainReachable() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        val sdk = (app.appContainer.soundlySdkProvider.get() as AppResult.Success).value
        val original = (runBlocking { sdk.settings.get() } as SoundlyResult.Success).value
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> val nav = (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController; nav.navigate(R.id.homeFragment); nav.navigate(R.id.settingsFragment) }
                waitFor { runCatching { onView(withId(R.id.settings_recording)).check(matches(isDisplayed())) }.isSuccess }
                capture(app, "F31")
                onView(withId(R.id.settings_recording)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.recording_settings_save)).check(matches(isEnabled())) }.isSuccess }
                capture(app, "F32")
                scenario.onActivity { a ->
                    val formats = a.findViewById<Spinner>(R.id.recording_format)
                    assertEquals(listOf("M4A", "AAC"), (0 until formats.count).map { formats.getItemAtPosition(it).toString() })
                    formats.setSelection(1)
                    val scenes = a.findViewById<RadioGroup>(R.id.recording_scene)
                    scenes.check(scenes.getChildAt(1).id)
                }
                scenario.recreate()
                waitFor { runCatching { onView(withId(R.id.recording_settings_save)).check(matches(isEnabled())) }.isSuccess }
                scenario.onActivity { a ->
                    assertEquals("AAC", a.findViewById<Spinner>(R.id.recording_format).selectedItem.toString())
                    val scenes = a.findViewById<RadioGroup>(R.id.recording_scene)
                    assertEquals(1, scenes.indexOfChild(scenes.findViewById(scenes.checkedRadioButtonId)))
                }
                onView(withId(R.id.recording_settings_save)).perform(scrollTo(), click())
                waitFor { runBlocking { (sdk.settings.get() as SoundlyResult.Success).value.recordingFormat == AudioFormat.AAC } }
                waitFor { runCatching { onView(withId(R.id.settings_output)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.settings_output)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.output_settings_save)).check(matches(isEnabled())) }.isSuccess }
                capture(app, "F33")
                scenario.onActivity { a ->
                    val formats = a.findViewById<Spinner>(R.id.output_format)
                    assertEquals(listOf("WAV", "M4A", "AAC", "MP3"), (0 until formats.count).map { formats.getItemAtPosition(it).toString() })
                    formats.setSelection(1)
                    val naming = a.findViewById<RadioGroup>(R.id.output_naming)
                    naming.check(naming.getChildAt(1).id)
                }
                onView(withId(R.id.output_settings_save)).perform(scrollTo(), click())
                waitFor { runBlocking { (sdk.settings.get() as SoundlyResult.Success).value.outputFormat == AudioFormat.M4A } }
                assertEquals(com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy.TIMESTAMP, (runBlocking { sdk.settings.get() } as SoundlyResult.Success).value.namingStrategy)
                waitFor { runCatching { onView(withId(R.id.settings_privacy)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.settings_privacy)).perform(scrollTo(), click())
                capture(app, "F35")
                onView(withId(R.id.privacy_storage)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.storage_clear_cache)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                onView(withId(R.id.storage_clear_cache)).perform(scrollTo())
                onView(withContentDescription(R.string.storage_refresh)).perform(click())
                capture(app, "F34")
                scenario.onActivity { a -> (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.popBackStack(R.id.settingsFragment, false) }
                onView(withId(R.id.settings_help)).perform(scrollTo(), click())
                capture(app, "F36")
                onView(withId(R.id.help_faq_fail_question)).perform(scrollTo(), click())
                onView(withId(R.id.help_faq_fail_answer)).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withId(R.id.help_faq_fail_answer)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.help_diagnostics)).check(matches(isNotChecked()))
                scenario.onActivity { a -> (a.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController.popBackStack(R.id.settingsFragment, false) }
                onView(withId(R.id.settings_pro)).perform(scrollTo(), click())
                waitFor { runCatching { onView(withId(R.id.pro_back)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess }
                capture(app, "F37")
            }
        } finally { runBlocking { sdk.settings.update(original) } }
    }
    private fun capture(app: SoundlyApplication, name: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val dir = File(requireNotNull(app.getExternalFilesDir(null)), "s4-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(condition: () -> Boolean) { val deadline = System.currentTimeMillis() + 20_000; while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }; fail("Timed out waiting for settings") }
}
