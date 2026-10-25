package com.ideacode.soundly_sdk.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.importer.AndroidAudioMetadataReader
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingFileFinalizerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.filesDir.resolve("soundly").deleteRecursively()
    }

    @Test
    fun adtsRecoveryCanBeInspectedRemuxedAndDecoded() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val recovery = store.recoveryFile("recoverable_aac", AudioFormat.AAC).apply {
            writeBytes(Base64.decode(RECOVERY_AAC_BASE64, Base64.DEFAULT))
        }
        val metadata = AndroidAudioMetadataReader(context).read(Uri.fromFile(recovery))

        assertTrue("Unexpected metadata: $metadata", metadata is SoundlyResult.Success)
        assertEquals(
            AudioFormat.AAC,
            (metadata as SoundlyResult.Success).value.format
        )

        val destination = store.originalFile("recovered_project", AudioFormat.M4A)
        val finalized = AndroidRecordingFileFinalizer(store).finalize(
            recovery,
            destination,
            AudioFormat.M4A
        )

        assertTrue(finalized.recoverySourceRetained)
        assertTrue(recovery.isFile)
        assertTrue(destination.isFile)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(destination.absolutePath)
            assertTrue((0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            })
        } finally {
            extractor.release()
        }
        val waveform = MediaCodecStaticWaveformGenerator().generate(destination, 250L)
        assertTrue(waveform.amplitudes.isNotEmpty())
        assertTrue(waveform.amplitudes.all { it in 0f..1f })
    }

    private companion object {
        // 250 ms mono AAC/ADTS silence at 8 kHz; deterministic fixture generated with FFmpeg.
        const val RECOVERY_AAC_BASE64 =
            "//FsQAO//N4EAExhdmM1OC4xMzQuMTAwAAIwQA7/8WxAAX/8ARggB//xbEABf/wBGCAH"
    }
}
