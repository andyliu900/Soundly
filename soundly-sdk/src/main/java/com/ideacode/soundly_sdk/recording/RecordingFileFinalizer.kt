package com.ideacode.soundly_sdk.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.storage.AudioFileStore
import java.io.File
import java.nio.ByteBuffer

internal interface RecordingFileFinalizer {
    fun finalize(
        recoveryFile: File,
        destination: File,
        outputFormat: AudioFormat
    ): FinalizedRecordingFile
}

internal data class FinalizedRecordingFile(
    val file: File,
    val recoverySourceRetained: Boolean
)

/**
 * Recordings are captured as AAC/ADTS so complete frames remain recoverable after an abrupt
 * process death. M4A is produced only during finalization by remuxing without decoding.
 */
internal class AndroidRecordingFileFinalizer(
    private val fileStore: AudioFileStore
) : RecordingFileFinalizer {

    override fun finalize(
        recoveryFile: File,
        destination: File,
        outputFormat: AudioFormat
    ): FinalizedRecordingFile = when (outputFormat) {
        AudioFormat.AAC -> FinalizedRecordingFile(
            file = fileStore.moveAtomically(recoveryFile, destination),
            recoverySourceRetained = false
        )

        AudioFormat.M4A -> {
            val temporaryM4a = fileStore.createTempFile(".m4a")
            try {
                remuxAacToM4a(recoveryFile, temporaryM4a)
                FinalizedRecordingFile(
                    file = fileStore.moveAtomically(temporaryM4a, destination),
                    recoverySourceRetained = true
                )
            } catch (error: Exception) {
                runCatching { fileStore.deleteFile(temporaryM4a) }
                throw error
            }
        }

        else -> error("Unsupported recording output format: $outputFormat")
    }

    private fun remuxAacToM4a(source: File, destination: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val inputTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Recovery file contains no audio track")
            val format = extractor.getTrackFormat(inputTrack)
            extractor.selectTrack(inputTrack)
            muxer = MediaMuxer(
                destination.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    .coerceAtLeast(DEFAULT_BUFFER_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var firstPresentationTimeUs: Long? = null
            var wroteSample = false
            while (true) {
                val presentationTimeUs = extractor.sampleTime
                if (presentationTimeUs < 0L) break
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleFlags = extractor.sampleFlags
                require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "Encrypted recovery audio is not supported"
                }
                val timelineStart = firstPresentationTimeUs ?: presentationTimeUs.also {
                    firstPresentationTimeUs = it
                }
                var codecFlags = 0
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(
                    0,
                    sampleSize,
                    (presentationTimeUs - timelineStart).coerceAtLeast(0L),
                    codecFlags
                )
                muxer.writeSampleData(outputTrack, buffer, info)
                wroteSample = true
                if (!extractor.advance()) break
            }
            require(wroteSample) { "Recovery file contains no complete audio samples" }
        } finally {
            runCatching { extractor.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
    }
}
