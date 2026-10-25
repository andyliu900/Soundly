package com.ideacode.soundly_sdk.api

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.exception.SoundlySdkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class SoundlyFacadeTest {

    @Test
    fun sdkBeforeInitializationThrowsTypedError() {
        val failure = runCatching { Soundly.sdk() }.exceptionOrNull()

        assertTrue(failure is SoundlySdkException)
        assertEquals(
            SoundlyError.NotInitialized,
            (failure as SoundlySdkException).error
        )
    }

    @Test
    fun sdkResultBeforeInitializationReturnsTypedFailure() {
        val result = Soundly.sdkResult()

        assertTrue(result is com.ideacode.soundly_sdk.api.result.SoundlyResult.Failure)
        assertEquals(
            SoundlyError.NotInitialized,
            (result as com.ideacode.soundly_sdk.api.result.SoundlyResult.Failure).error
        )
    }

    @Test
    fun dependencyContainerIsNotPartOfSoundlySourceApi() {
        val publicMethodNames = Soundly::class.java.methods
            .filterNot { it.isSynthetic }
            .map { it.name }

        assertFalse("container" in publicMethodNames)
        assertFalse("getPlayer" in SoundlySdk::class.java.methods.map { it.name })
        assertFalse("getHistory" in SoundlySdk::class.java.methods.map { it.name })
        assertFalse(
            Modifier.isPublic(Class.forName("com.ideacode.soundly_sdk.api.SdkContainer").modifiers)
        )
    }

    @Test
    fun sdkFacadeExposesOnlyTheStableCapabilitySet() {
        val capabilityGetters = SoundlySdk::class.java.declaredMethods
            .filter { Modifier.isAbstract(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "getProcessor",
                "getProjects",
                "getImporter",
                "getMetadata",
                "getRecorder",
                "getPlayback",
                "getTrimmer",
                "getExporter",
                "getStorage",
                "getEnhancer",
                "getSettings"
            ),
            capabilityGetters
        )
    }
}
