package com.ideacode.soundly_sdk.editing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.importer.AndroidAudioMetadataReader
import com.ideacode.soundly_sdk.importer.DefaultAudioImporter
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import com.ideacode.soundly_sdk.core.util.WavWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class M4aTrimInstrumentedTest {
    @Test fun cancellationAfterVariantCommitKeepsCommittedFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(db.audioProjectDao(), store)
        val metadata = AndroidAudioMetadataReader(context)
        val input = WavWriter(context.cacheDir).write(FloatArray(44100) { .1f }, 44100, 1, "i3-cancel")
        var projectId: String? = null
        try {
            val project = (DefaultAudioImporter(context.contentResolver, metadata, repository, store).importAudio(AudioImportRequest(Uri.fromFile(input))) as SoundlyResult.Success).value.project
            projectId = project.id
            val interrupted = object : AudioProjectRepository by repository {
                override suspend fun addVariant(projectId: String, variant: AudioVariant): SoundlyResult<Unit> {
                    assertTrue(repository.addVariant(projectId, variant) is SoundlyResult.Success)
                    throw CancellationException("after database commit")
                }
            }
            try { AndroidAudioTrimmer(interrupted, metadata, store).applyTrim(project.id, project.original.id, TrimRange(100, 800)); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            val updated = (repository.getProject(project.id) as SoundlyResult.Success).value
            assertEquals(1, updated.variants.size)
            assertTrue("Committed audio must not be deleted", File(updated.variants.single().filePath).isFile)
        } finally { projectId?.let(store::deleteProject); input.delete(); db.close() }
    }
    @Test fun selectedM4aFramesArePreservedAndOutputIsPlayable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(db.audioProjectDao(), store)
        val metadata = AndroidAudioMetadataReader(context)
        val input = File(context.cacheDir, "i3-generated.m4a")
        var projectId: String? = null
        try {
            writeM4a(input)
            val importer = DefaultAudioImporter(context.contentResolver, metadata, repository, store)
            val project = (importer.importAudio(AudioImportRequest(Uri.fromFile(input))) as SoundlyResult.Success).value.project
            projectId = project.id
            val original = File(project.original.filePath).readBytes()
            val trimmer = AndroidAudioTrimmer(repository, metadata, store)
            val result = trimmer.applyTrim(project.id, project.original.id, TrimRange(1000, 2000))
            assertTrue("M4A trim should succeed: $result", result is SoundlyResult.Success)
            val updated = (repository.getProject(project.id) as SoundlyResult.Success).value
            val trimmed = updated.variants.single()
            assertEquals(project.original.id, trimmed.sourceVariantId)
            assertTrue("Actual duration must reflect frame boundaries", trimmed.durationMs in 900..1100)
            assertArrayEquals(original, File(project.original.filePath).readBytes())
            val expected = frames(File(project.original.filePath), 1_000_000, 2_000_000)
            val actual = frames(File(trimmed.filePath), 0, Long.MAX_VALUE)
            assertTrue(expected.isNotEmpty())
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (a, b) -> assertArrayEquals(a, b) }
            val player = MediaPlayer()
            try { player.setDataSource(trimmed.filePath); player.prepare(); player.start(); assertTrue(player.isPlaying) }
            finally { player.release() }
        } finally { projectId?.let(store::deleteProject); input.delete(); db.close() }
    }

    private fun frames(file: File, startUs: Long, endUs: Long): List<ByteArray> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(index); extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val result = mutableListOf<ByteArray>(); val buffer = ByteBuffer.allocate(64 * 1024)
            while (extractor.sampleTime >= 0 && extractor.sampleTime < endUs) {
                if (extractor.sampleTime >= startUs) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    result.add(ByteArray(size).also { buffer.position(0); buffer.get(it) })
                }
                if (!extractor.advance()) break
            }
            result
        } finally { extractor.release() }
    }

    private fun writeM4a(destination: File) {
        val sampleRate = 44100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo(); val total = sampleRate * 3
            var submitted = 0; var inputDone = false; var outputDone = false; var track = -1
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        if (submitted >= total) { codec.queueInputBuffer(index, 0, 0, submitted * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val count = minOf(buffer.remaining() / 2, total - submitted)
                            repeat(count) { offset -> buffer.putShort((sin((submitted + offset) * .04) * 10000).toInt().toShort()) }
                            codec.queueInputBuffer(index, 0, count * 2, submitted * 1_000_000L / sampleRate, 0); submitted += count
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    else -> if (index >= 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) { buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer.writeSampleData(track, buffer, info) }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); if (muxerStarted) runCatching { muxer.stop() }; muxer.release() }
    }
}
