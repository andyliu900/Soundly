package com.ideacode.soundly.debug

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.common.ui.audio.AudioPlayerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiGalleryInstrumentedTest {
    @Test
    fun errorRetryShowsLoadingAndRemainsUsableAfterRecreation() {
        ActivityScenario.launch(UiGalleryActivity::class.java).use { scenario ->
            onView(withId(R.id.gallery_error)).perform(scrollTo(), click())
            onView(withText(R.string.gallery_error)).check(matches(isDisplayed()))
            onView(withId(R.id.status_retry)).perform(scrollTo(), click())
            onView(withId(R.id.status_progress)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.gallery_error)).perform(scrollTo(), click())
            onView(withText(R.string.gallery_error)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun renderingPlaybackStateNeverInitiatesASeek() {
        ActivityScenario.launch(UiGalleryActivity::class.java).use { scenario ->
            var seekCalls = 0
            scenario.onActivity { activity ->
                val player = activity.findViewById<AudioPlayerView>(R.id.gallery_player)
                player.onSeek = { seekCalls++ }
                player.render(AudioPlayerUiState(durationMs = 100_000, positionMs = 50_000, enabled = true))
                player.render(AudioPlayerUiState(durationMs = 100_000, positionMs = 90_000, enabled = true))
            }
            assertEquals(0, seekCalls)
        }
    }

    @Test
    fun materialDialogAndBottomSheetOpenAndClose() {
        ActivityScenario.launch(UiGalleryActivity::class.java).use {
            onView(withId(R.id.gallery_dialog)).perform(scrollTo(), click())
            onView(withText(R.string.gallery_dialog_message)).check(matches(isDisplayed()))
            onView(withText(R.string.cancel)).perform(click())
            onView(withId(R.id.gallery_sheet)).perform(scrollTo(), click())
            onView(withText(R.string.gallery_sheet_body)).check(matches(isDisplayed()))
            onView(withId(R.id.gallery_sheet_close)).perform(click())
        }
    }
}
