package com.ideacode.soundly_sdk.importer

import android.content.ContentResolver
import com.ideacode.soundly_sdk.api.capability.AudioImporter
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.importer.*
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.project.data.entity.ImportOperationEntity
import com.ideacode.soundly_sdk.project.data.entity.toDomain
import com.ideacode.soundly_sdk.project.data.entity.toEntity
import com.ideacode.soundly_sdk.storage.AudioFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Serializes identified imports and persists the commit boundary in Room. */
internal class RecoverableAudioImporter(
    private val legacy: AudioImporter,
    private val resolver: ContentResolver,
    private val metadata: AudioMetadataReader,
    private val dao: AudioProjectDao,
    private val files: AudioFileStore
) : AudioImporter {

    override suspend fun importAudio(request: AudioImportRequest) = legacy.importAudio(request)

    override suspend fun importAudio(operationId: String, request: AudioImportRequest): SoundlyResult<AudioImportResult> =
        withContext(Dispatchers.IO) {
            if (!validId(operationId)) return@withContext SoundlyResult.Failure(SoundlyError.InvalidRequest("operationId"))
            try { locked {
                try {
                    val existing = dao.getImport(operationId)
                    if (existing != null) {
                        return@locked when (val status = inspectLocked(existing)) {
                            is SoundlyResult.Success -> when (val value = status.value) {
                                is ImportOperationStatus.Completed -> SoundlyResult.Success(value.result)
                                else -> SoundlyResult.Failure(SoundlyError.InvalidState("importInterrupted"))
                            }
                            is SoundlyResult.Failure -> status
                        }
                    }
                    val operation = ImportOperationEntity(operationId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "RESERVED")
                    dao.reserveImport(operation)
                    val info = when (val read = metadata.read(request.source)) {
                        is SoundlyResult.Failure -> { dao.updateImportPhase(operationId, "INTERRUPTED"); return@locked read }
                        is SoundlyResult.Success -> read.value
                    }
                    if (info.fileSizeBytes > 0 && (info.fileSizeBytes > Long.MAX_VALUE - MINIMUM_FREE || files.availableBytes() < info.fileSizeBytes + MINIMUM_FREE)) {
                        dao.updateImportPhase(operationId, "INTERRUPTED")
                        return@locked SoundlyResult.Failure(SoundlyError.InsufficientStorage)
                    }
                    if (!files.reserveProjectDirectory(operation.projectId)) {
                        dao.updateImportPhase(operationId, "INTERRUPTED")
                        return@locked SoundlyResult.Failure(SoundlyError.InvalidState("projectDirectoryExists"))
                    }
                    check(dao.updateImportPhase(operationId, "COPYING") == 1)
                    val destination = files.originalFile(operation.projectId, info.format)
                    val input = resolver.openInputStream(request.source) ?: throw FileNotFoundException()
                    files.copyAtomically(input, destination)
                    currentCoroutineContext().ensureActive()
                    check(dao.updateImportPhase(operationId, "READY") == 1)
                    val now = System.currentTimeMillis()
                    val title = (request.title ?: info.displayName).removeSuffixByFormat().trim().take(120).ifBlank { "Imported audio" }
                    val original = AudioVariant(destination.absolutePath, PresetOption.RAW, true, info.sampleRate, info.channels,
                        info.durationMs, id = operation.variantId, projectId = operation.projectId, format = info.format,
                        mimeType = info.mimeType, fileSizeBytes = destination.length(), createdAt = now)
                    val project = AudioProject(operation.projectId, title, AudioSourceType.IMPORTED, null, original,
                        createdAt = now, updatedAt = now, sourceDisplayName = info.sourceDisplayName)
                    dao.commitImport(operationId, project.toEntity(), original.toEntity())
                    SoundlyResult.Success(AudioImportResult(project, destination.length()))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: SecurityException) {
                    SoundlyResult.Failure(SoundlyError.PermissionDenied)
                } catch (error: FileNotFoundException) {
                    SoundlyResult.Failure(SoundlyError.FileNotFound(cause = error))
                } catch (error: IOException) {
                    if (runCatching { files.availableBytes() < MINIMUM_FREE }.getOrDefault(false))
                        SoundlyResult.Failure(SoundlyError.InsufficientStorage)
                    else SoundlyResult.Failure(SoundlyError.ImportFailed(error))
                } catch (error: Exception) {
                    SoundlyResult.Failure(SoundlyError.ImportFailed(error))
                }
            } } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { SoundlyResult.Failure(SoundlyError.FileOperationFailed(error)) }
        }

    override suspend fun inspectImport(operationId: String): SoundlyResult<ImportOperationStatus> = withContext(Dispatchers.IO) {
        if (!validId(operationId)) return@withContext SoundlyResult.Failure(SoundlyError.InvalidRequest("operationId"))
        try { locked {
            try {
                val operation = dao.getImport(operationId) ?: return@locked SoundlyResult.Success(ImportOperationStatus.NotStarted)
                inspectLocked(operation)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { SoundlyResult.Failure(SoundlyError.DatabaseFailed(error)) }
        } } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { SoundlyResult.Failure(SoundlyError.FileOperationFailed(error)) }
    }

    private suspend fun inspectLocked(operation: ImportOperationEntity): SoundlyResult<ImportOperationStatus> {
        val aggregate = dao.getProject(operation.projectId)
        if (aggregate != null) {
            if (operation.phase != "COMMITTED") return SoundlyResult.Failure(SoundlyError.InvalidState("importResultUncertain"))
            val project = aggregate.toDomain()
            val file = File(project.original.filePath)
            if (project.original.id != operation.variantId || !file.isFile || !file.canRead())
                return SoundlyResult.Failure(SoundlyError.FileNotFound())
            return SoundlyResult.Success(ImportOperationStatus.Completed(AudioImportResult(project, file.length())))
        }
        if (operation.phase == "COMMITTED") return SoundlyResult.Failure(SoundlyError.InvalidState("importResultMissing"))
        if (operation.phase !in setOf("RESERVED", "COPYING", "READY", "INTERRUPTED"))
            return SoundlyResult.Failure(SoundlyError.InvalidState("importResultUncertain"))
        // Only COPYING/READY proves this operation exclusively created its directory.
        if (operation.phase == "COPYING" || operation.phase == "READY") {
            if (!files.deleteProject(operation.projectId)) return SoundlyResult.Failure(SoundlyError.FileOperationFailed())
        }
        check(dao.updateImportPhase(operation.id, "INTERRUPTED") == 1)
        return SoundlyResult.Success(ImportOperationStatus.Interrupted)
    }

    private fun validId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        RandomAccessFile(File(files.rootDirectory, "import.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { block() }
        }
    }
    private fun String.removeSuffixByFormat(): String = replace(Regex("\\.(wav|wave|m4a|mp4|aac|mp3)$", RegexOption.IGNORE_CASE), "")
    private companion object {
        const val MINIMUM_FREE = 16L * 1024 * 1024
        val mutex = Mutex()
    }
}
