package com.ideacode.soundly_sdk.editing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.capability.AudioTrimmer
import com.ideacode.soundly_sdk.api.capability.TrimPreview
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.capability.TrimResult
import com.ideacode.soundly_sdk.api.capability.TrimmedMarker
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.storage.AudioFileStore
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

internal class AndroidAudioTrimmer(
    private val projects: AudioProjectRepository,
    private val metadataReader: AudioMetadataReader,
    private val fileStore: AudioFileStore,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioTrimmer {

    override suspend fun previewTrim(
        projectId: String,
        sourceVariantId: String,
        range: TrimRange
    ): SoundlyResult<TrimPreview> = withContext(dispatchers.io) {
        when (val resolved = resolveTrim(projectId, sourceVariantId, range)) {
            is SoundlyResult.Success -> {
                val request = resolved.value
                SoundlyResult.Success(
                    TrimPreview(
                        projectId = projectId,
                        sourceVariantId = sourceVariantId,
                        source = AudioSourceRef.LocalFile(request.sourceFile),
                        range = range,
                        durationMs = range.endMs - range.startMs,
                        markers = mapMarkers(request.project, request.source, range)
                    )
                )
            }

            is SoundlyResult.Failure -> resolved
        }
    }

    override suspend fun applyTrim(
        projectId: String,
        sourceVariantId: String,
        range: TrimRange
    ): SoundlyResult<TrimResult> = withContext(dispatchers.io) {
        val resolved = when (val result = resolveTrim(projectId, sourceVariantId, range)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return@withContext result
        }
        val project = resolved.project
        val source = resolved.source
        val sourceFile = resolved.sourceFile
        val mappedMarkers = mapMarkers(project, source, range)
        val estimatedOutputBytes = source.fileSizeBytes
            .takeIf { it > 0L && source.durationMs > 0L }
            ?.times(range.endMs - range.startMs)
            ?.div(source.durationMs)
            ?: sourceFile.length()
        if (fileStore.availableBytes() < estimatedOutputBytes + MINIMUM_FREE_BYTES) {
            return@withContext SoundlyResult.Failure(SoundlyError.InsufficientStorage)
        }

        val variantId = idGenerator()
        val destination = fileStore.variantFile(projectId, variantId, source.format)
        val staging = fileStore.createTempFile(".${source.format.extension()}")
        try {
            when (source.format) {
                AudioFormat.WAV -> WavTrimmer.trim(
                    sourceFile,
                    staging,
                    range.startMs,
                    range.endMs
                )

                AudioFormat.M4A -> trimM4a(
                    sourceFile,
                    staging,
                    range.startMs * 1_000L,
                    range.endMs * 1_000L
                )

                else -> error("Unsupported trim format")
            }
            fileStore.moveAtomically(staging, destination)
            val metadata = when (val result = metadataReader.read(Uri.fromFile(destination))) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> {
                    fileStore.deleteFile(destination)
                    return@withContext result
                }
            }
            val variant = AudioVariant(
                id = variantId,
                projectId = projectId,
                filePath = destination.absolutePath,
                presetOption = source.presetOption,
                isOriginal = false,
                sampleRate = metadata.sampleRate,
                channels = metadata.channels,
                durationMs = metadata.durationMs,
                format = metadata.format,
                mimeType = metadata.mimeType,
                fileSizeBytes = destination.length(),
                createdAt = clock(),
                variantType = VariantType.TRIMMED,
                sourceVariantId = sourceVariantId,
                trimStartMs = range.startMs,
                trimEndMs = range.endMs
            )
            when (val result = projects.addVariant(projectId, variant)) {
                is SoundlyResult.Success -> SoundlyResult.Success(
                    TrimResult(
                        projectId = projectId,
                        variantId = variantId,
                        durationMs = metadata.durationMs,
                        markers = mappedMarkers
                    )
                )

                is SoundlyResult.Failure -> {
                    fileStore.deleteFile(destination)
                    result
                }
            }
        } catch (cancellation: CancellationException) {
            runCatching { if (staging.exists()) fileStore.deleteFile(staging) }
            runCatching { if (destination.exists()) fileStore.deleteFile(destination) }
            throw cancellation
        } catch (error: Exception) {
            runCatching { if (staging.exists()) fileStore.deleteFile(staging) }
            runCatching { if (destination.exists()) fileStore.deleteFile(destination) }
            SoundlyResult.Failure(SoundlyError.TrimFailed(error))
        }
    }

    private suspend fun resolveTrim(
        projectId: String,
        sourceVariantId: String,
        range: TrimRange
    ): SoundlyResult<ResolvedTrim> {
        if (range.startMs < 0L || range.endMs <= range.startMs) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest("range"))
        }
        val project = when (val result = projects.getProject(projectId)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return result
        }
        val source = (listOf(project.original) + project.variants)
            .firstOrNull { it.id == sourceVariantId }
            ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("sourceVariantId"))
        if (range.startMs >= source.durationMs || range.endMs > source.durationMs) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest("range"))
        }
        if (source.format !in SUPPORTED_FORMATS) {
            return SoundlyResult.Failure(SoundlyError.UnsupportedFormat(source.mimeType))
        }
        val sourceFile = File(source.filePath)
        if (!sourceFile.isFile || !sourceFile.canRead()) {
            return SoundlyResult.Failure(SoundlyError.FileNotFound(source.filePath))
        }
        return SoundlyResult.Success(ResolvedTrim(project, source, sourceFile))
    }

    private fun mapMarkers(
        project: AudioProject,
        source: AudioVariant,
        range: TrimRange
    ): List<TrimmedMarker> {
        val variants = (listOf(project.original) + project.variants).associateBy { it.id }
        val sourceOffsetMs = sourceTimelineOffset(source, variants, mutableSetOf())
        val retainedStartMs = sourceOffsetMs + range.startMs
        val retainedEndMs = sourceOffsetMs + range.endMs
        return project.markers
            .asSequence()
            .filter { it.positionMs in retainedStartMs until retainedEndMs }
            .map { marker ->
                TrimmedMarker(
                    marker = marker,
                    sourcePositionMs = marker.positionMs,
                    trimmedPositionMs = marker.positionMs - retainedStartMs
                )
            }
            .sortedBy { it.trimmedPositionMs }
            .toList()
    }

    private fun sourceTimelineOffset(
        variant: AudioVariant,
        variants: Map<String, AudioVariant>,
        visited: MutableSet<String>
    ): Long {
        if (!visited.add(variant.id)) return 0L
        val parent = variant.sourceVariantId?.let(variants::get) ?: return 0L
        val parentOffset = sourceTimelineOffset(parent, variants, visited)
        return parentOffset + if (variant.variantType == VariantType.TRIMMED) {
            variant.trimStartMs ?: 0L
        } else {
            0L
        }
    }

    private fun trimM4a(source: File, destination: File, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("No audio track")
            val format = extractor.getTrackFormat(audioTrackIndex)
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer = MediaMuxer(
                destination.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var wroteSample = false
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= endUs) break
                if (sampleTime < startUs) {
                    if (!extractor.advance()) break
                    continue
                }
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleFlags = extractor.sampleFlags
                require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "Encrypted audio cannot be trimmed"
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
                    sampleTime - startUs,
                    codecFlags
                )
                muxer.writeSampleData(outputTrack, buffer, info)
                wroteSample = true
                if (!extractor.advance()) break
            }
            require(wroteSample) { "Trim range contains no complete audio samples" }
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun AudioFormat.extension(): String = when (this) {
        AudioFormat.WAV -> "wav"
        AudioFormat.M4A -> "m4a"
        else -> error("Unsupported format")
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1024 * 1024
        const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        val SUPPORTED_FORMATS = setOf(AudioFormat.WAV, AudioFormat.M4A)
    }

    private data class ResolvedTrim(
        val project: AudioProject,
        val source: AudioVariant,
        val sourceFile: File
    )
}
