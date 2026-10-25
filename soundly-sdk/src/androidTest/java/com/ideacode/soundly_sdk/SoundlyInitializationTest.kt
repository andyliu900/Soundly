package com.ideacode.soundly_sdk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundlyInitializationTest {

    @Test
    fun repeatedInitializationIsIdempotent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val first = Soundly.initialize(context)
        val firstSdk = Soundly.sdk()
        val second = Soundly.initialize(context)

        assertTrue(first is SoundlyResult.Success)
        assertTrue(second is SoundlyResult.Success)
        assertSame(firstSdk, Soundly.sdk())
    }
}
