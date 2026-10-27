package com.ideacode.soundly_sdk.preset

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PresetFactoryTest {
    @Test
    fun aiPostProcessingDisablesLegacyNoiseGate() {
        assertNull(PresetFactory.build(PresetOption.AI, features = null).noiseReductionPreset)
        assertNotNull(PresetFactory.build(PresetOption.DEFAULT, features = null).noiseReductionPreset)
    }
}
