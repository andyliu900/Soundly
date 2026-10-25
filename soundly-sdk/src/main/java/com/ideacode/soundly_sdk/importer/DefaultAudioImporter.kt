package com.ideacode.soundly_sdk.importer

import android.content.ContentResolver
import com.ideacode.soundly_sdk.api.capability.AudioImporter
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.ProjectStatus
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.importer.AudioImportResult
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.storage.AudioFileStore
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID

internal class DefaultAudioImporter(
    private val contentResolver: ContentResolver,
    private val metadataReader: AudioMetadataReader,
    private val projects: AudioProjectRepository,
    private val fileStore: AudioFileStore,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioImporter {

    override suspend fun importAudio(
        request: AudioImportRequest
    ): SoundlyResult<AudioImportResult> = withContext(dispatchers.io) {
        val metadata = when (val result = metadataReader.read(request.source)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return@withContext result
        }
        if (metadata.fileSizeBytes > 0L &&
            fileStore.availableBytes() < metadata.fileSizeBytes + MINIMUM_FREE_BYTES
        ) {
            return@withContext SoundlyResult.Failure(SoundlyError.InsufficientStorage)
        }

        val projectId = idGenerator()
        val variantId = idGenerator()
        val destination = try {
            fileStore.originalFile(projectId, metadata.format)
        } catch (error: Exception) {
            return@withContext SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }

        try {
            val input = contentResolver.openInputStream(request.source)
                ?: throw FileNotFoundException(request.source.toString())
            fileStore.copyAtomically(input, destination)
            val now = clock()
            val title = normalizeTitle(request.title ?: metadata.displayName)
            val original = AudioVariant(
                id = variantId,
                projectId = projectId,
                filePath = destination.absolutePath,
                presetOption = PresetOption.RAW,
                isOriginal = true,
                sampleRate = metadata.sampleRate,
                channels = metadata.channels,
                durationMs = metadata.durationMs,
                format = metadata.format,
                mimeType = metadata.mimeType,
                fileSizeBytes = destination.length(),
                createdAt = now,
                variantType = VariantType.ORIGINAL
            )
            val project = AudioProject(
                id = projectId,
                title = title,
                sourceType = AudioSourceType.IMPORTED,
                scene = null,
                original = original,
                status = ProjectStatus.AVAILABLE,
                createdAt = now,
                updatedAt = now
            )

            when (val saveResult = projects.saveProject(project)) {
                is SoundlyResult.Success -> SoundlyResult.Success(
                    AudioImportResult(
                        project = project,
                        copiedBytes = destination.length()
                    )
                )

                is SoundlyResult.Failure -> {
                    fileStore.deleteProject(projectId)
                    saveResult
                }
            }
        } catch (error: SecurityException) {
            fileStore.deleteProject(projectId)
            SoundlyResult.Failure(SoundlyError.PermissionDenied)
        } catch (error: FileNotFoundException) {
            fileStore.deleteProject(projectId)
            SoundlyResult.Failure(SoundlyError.FileNotFound(cause = error))
        } catch (error: Exception) {
            fileStore.deleteProject(projectId)
            SoundlyResult.Failure(SoundlyError.ImportFailed(error))
        }
    }

    private fun normalizeTitle(value: String): String {
        val extension = value.substringAfterLast('.', "").lowercase()
        val withoutExtension = if (extension in AUDIO_EXTENSIONS) {
            value.dropLast(extension.length + 1)
        } else {
            value
        }
        return withoutExtension
            .trim()
            .take(MAX_TITLE_LENGTH)
            .ifBlank { DEFAULT_TITLE }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 120
        const val DEFAULT_TITLE = "Imported audio"
        const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        val AUDIO_EXTENSIONS = setOf("wav", "wave", "m4a", "mp4", "aac", "mp3")
    }
}
