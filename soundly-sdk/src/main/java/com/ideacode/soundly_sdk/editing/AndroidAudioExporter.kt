package com.ideacode.soundly_sdk.editing

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ideacode.soundly_sdk.api.capability.AudioExporter
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.export.ExportDestination
import com.ideacode.soundly_sdk.api.model.export.ExportProgress
import com.ideacode.soundly_sdk.api.model.export.ExportQuality
import com.ideacode.soundly_sdk.api.model.export.ExportRequest
import com.ideacode.soundly_sdk.api.model.export.ExportResult
import com.ideacode.soundly_sdk.api.model.export.ExportStage
import com.ideacode.soundly_sdk.api.model.export.FileConflictStrategy
import com.ideacode.soundly_sdk.api.model.export.ShareRequest
import com.ideacode.soundly_sdk.api.model.export.ShareResult
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettingsRepository
import com.ideacode.soundly_sdk.storage.AudioFileStore
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal class AndroidAudioExporter(
    private val context: Context,
    private val projects: AudioProjectRepository,
    private val fileStore: AudioFileStore,
    private val settings: SoundlySettingsRepository,
    private val wavEncoder: StreamingWavEncoder = StreamingWavEncoder(),
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioExporter {

    override suspend fun export(
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit
    ): SoundlyResult<ExportResult> = withContext(dispatchers.io) {
        onProgress(ExportProgress(ExportStage.PREPARING, 0))
        if (request.format == AudioFormat.UNKNOWN) {
            return@withContext SoundlyResult.Failure(
                SoundlyError.UnsupportedFormat(request.format.name)
            )
        }
        val normalizedName = normalizeDisplayName(request.displayName, request.format)
            ?: return@withContext SoundlyResult.Failure(
                SoundlyError.InvalidRequest("displayName")
            )
        val resolved = when (val result = resolveSource(request.projectId, request.variantId)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return@withContext result
        }
        var temporaryExport: File? = null
        try {
            val plan = when (val result = resolveExportPlan(request, resolved)) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> return@withContext result
            }
            val exportSource = when (plan.operation) {
                ExportOperation.COPY -> resolved.file
                ExportOperation.ENCODE_PCM16_WAV -> {
                    val estimatedBytes = estimateWavBytes(resolved.variant)
                    if (fileStore.availableBytes() < estimatedBytes + MINIMUM_FREE_BYTES) {
                        return@withContext SoundlyResult.Failure(SoundlyError.InsufficientStorage)
                    }
                    fileStore.createTempFile(".wav").also { temporary ->
                        temporaryExport = temporary
                        wavEncoder.encode(resolved.file, temporary, onProgress)
                    }
                }
            }
            val writingStartPercent = if (temporaryExport != null) 70 else 0
            val result = when (val target = request.destination) {
                ExportDestination.Music -> exportToMediaStore(
                    exportSource,
                    normalizedName,
                    request.format,
                    Environment.DIRECTORY_MUSIC,
                    request.conflictStrategy,
                    plan.actualQuality,
                    onProgress,
                    writingStartPercent
                )

                ExportDestination.Downloads -> exportToMediaStore(
                    exportSource,
                    normalizedName,
                    request.format,
                    Environment.DIRECTORY_DOWNLOADS,
                    request.conflictStrategy,
                    plan.actualQuality,
                    onProgress,
                    writingStartPercent
                )

                is ExportDestination.DocumentUri -> exportToDocument(
                    exportSource,
                    target.uri.toUri(),
                    normalizedName,
                    request.format,
                    request.conflictStrategy,
                    plan.actualQuality,
                    onProgress,
                    writingStartPercent
                )
            }
            if (result is SoundlyResult.Success) {
                onProgress(
                    ExportProgress(
                        stage = ExportStage.COMPLETED,
                        percent = 100,
                        bytesWritten = result.value.fileSizeBytes,
                        totalBytes = result.value.fileSizeBytes
                    )
                )
            }
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ExportConflictException) {
            SoundlyResult.Failure(SoundlyError.DestinationConflict(normalizedName))
        } catch (error: SecurityException) {
            SoundlyResult.Failure(SoundlyError.PermissionDenied)
        } catch (error: FileNotFoundException) {
            SoundlyResult.Failure(SoundlyError.FileNotFound(cause = error))
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.ExportFailed(error))
        } finally {
            temporaryExport?.let { temporary ->
                runCatching { if (temporary.exists()) fileStore.deleteFile(temporary) }
            }
        }
    }

    override suspend fun exportUsingDefaults(
        projectId: String,
        variantId: String,
        destination: ExportDestination,
        conflictStrategy: FileConflictStrategy,
        onProgress: (ExportProgress) -> Unit
    ): SoundlyResult<ExportResult> {
        val currentSettings = when (val result = settings.get()) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return result
        }
        val project = when (val result = projects.getProject(projectId)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return result
        }
        return export(
            ExportRequest(
                projectId = projectId,
                variantId = variantId,
                displayName = project.title,
                format = currentSettings.outputFormat,
                destination = destination,
                conflictStrategy = conflictStrategy,
                quality = ExportQuality.AUTOMATIC
            ),
            onProgress
        )
    }

    override suspend fun prepareShare(
        request: ShareRequest
    ): SoundlyResult<ShareResult> = withContext(dispatchers.io) {
        val resolved = when (val result = resolveSource(request.projectId, request.variantId)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return@withContext result
        }
        if (resolved.variant.format == AudioFormat.UNKNOWN) {
            return@withContext SoundlyResult.Failure(
                SoundlyError.UnsupportedFormat(resolved.variant.mimeType)
            )
        }
        val normalizedName = normalizeDisplayName(
            request.displayName ?: resolved.project.title,
            resolved.variant.format
        ) ?: return@withContext SoundlyResult.Failure(
            SoundlyError.InvalidRequest("displayName")
        )
        if (resolved.file.length() > fileStore.availableBytes()) {
            return@withContext SoundlyResult.Failure(SoundlyError.InsufficientStorage)
        }

        val cachedFile = try {
            fileStore.shareCacheFile(UUID.randomUUID().toString(), normalizedName)
        } catch (error: Exception) {
            return@withContext SoundlyResult.Failure(SoundlyError.ShareFailed(error))
        }

        try {
            coroutineContext.ensureActive()
            fileStore.copyAtomically(resolved.file.inputStream(), cachedFile)
            coroutineContext.ensureActive()

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.$FILE_PROVIDER_SUFFIX",
                cachedFile
            )
            val mimeType = resolved.variant.format.mimeType()
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                clipData = ClipData.newUri(context.contentResolver, normalizedName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            SoundlyResult.Success(
                ShareResult(
                    sendIntent = sendIntent,
                    contentUri = contentUri.toString(),
                    displayName = normalizedName,
                    mimeType = mimeType,
                    format = resolved.variant.format,
                    fileSizeBytes = cachedFile.length()
                )
            )
        } catch (cancellation: CancellationException) {
            deleteShareCopy(cachedFile)
            throw cancellation
        } catch (error: SecurityException) {
            deleteShareCopy(cachedFile)
            SoundlyResult.Failure(SoundlyError.PermissionDenied)
        } catch (error: Exception) {
            deleteShareCopy(cachedFile)
            SoundlyResult.Failure(SoundlyError.ShareFailed(error))
        }
    }

    override fun createDocumentIntent(
        displayName: String,
        format: AudioFormat
    ): SoundlyResult<Intent> {
        if (format == AudioFormat.UNKNOWN) {
            return SoundlyResult.Failure(SoundlyError.UnsupportedFormat(format.name))
        }
        val normalizedName = normalizeDisplayName(displayName, format)
            ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("displayName"))
        return SoundlyResult.Success(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = format.mimeType()
                putExtra(Intent.EXTRA_TITLE, normalizedName)
            }
        )
    }

    private suspend fun exportToMediaStore(
        source: File,
        initialName: String,
        format: AudioFormat,
        directory: String,
        strategy: FileConflictStrategy,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
        writingStartPercent: Int
    ): SoundlyResult<ExportResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return exportToLegacyDirectory(
                source,
                initialName,
                format,
                directory,
                strategy,
                quality,
                onProgress,
                writingStartPercent
            )
        }
        val resolver = context.contentResolver
        val collection = if (directory == Environment.DIRECTORY_DOWNLOADS) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = "$directory/$EXPORT_DIRECTORY_NAME"
        val existing = queryExisting(resolver, collection, initialName, relativePath)
        val finalName = when (strategy) {
            FileConflictStrategy.FAIL -> {
                if (existing != null) throw ExportConflictException()
                initialName
            }

            FileConflictStrategy.REPLACE -> initialName
            FileConflictStrategy.RENAME -> uniqueName(
                initialName,
                format,
                nameExists = { queryExisting(resolver, collection, it, relativePath) != null }
            )
        }
        val stagedReplacement = strategy == FileConflictStrategy.REPLACE && existing != null
        val insertedName = if (stagedReplacement) {
            ".soundly-${UUID.randomUUID()}.${format.extension()}"
        } else {
            finalName
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, insertedName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType())
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw FileNotFoundException("Unable to create MediaStore item")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                copyWithCancellation(source, output, onProgress, writingStartPercent)
            } ?: throw FileNotFoundException(uri.toString())
            val published = resolver.update(uri, ContentValues().apply {
                if (insertedName != finalName) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                }
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            check(published > 0) { "Unable to publish MediaStore item" }
            if (stagedReplacement) {
                resolver.delete(requireNotNull(existing), null, null)
            }
            return SoundlyResult.Success(
                ExportResult(uri.toString(), finalName, format, source.length(), quality)
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun exportToLegacyDirectory(
        source: File,
        initialName: String,
        format: AudioFormat,
        directory: String,
        strategy: FileConflictStrategy,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
        writingStartPercent: Int
    ): SoundlyResult<ExportResult> {
        val outputDirectory = File(
            Environment.getExternalStoragePublicDirectory(directory),
            EXPORT_DIRECTORY_NAME
        )
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create export directory"
        }
        var destination = File(outputDirectory, initialName)
        when (strategy) {
            FileConflictStrategy.FAIL -> if (destination.exists()) throw ExportConflictException()
            FileConflictStrategy.REPLACE -> Unit
            FileConflictStrategy.RENAME -> {
                val finalName = uniqueName(
                    initialName,
                    format,
                    nameExists = { File(outputDirectory, it).exists() }
                )
                destination = File(outputDirectory, finalName)
            }
        }
        val stagingFile = File(outputDirectory, ".soundly-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(stagingFile, false).use { output ->
                copyWithCancellation(source, output, onProgress, writingStartPercent)
                output.fd.sync()
            }
            try {
                Files.move(
                    stagingFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    stagingFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (error: Exception) {
            stagingFile.delete()
            throw error
        }
        val uri = Uri.fromFile(destination)
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
        return SoundlyResult.Success(
            ExportResult(
                uri.toString(),
                destination.name,
                format,
                destination.length(),
                quality
            )
        )
    }

    private suspend fun exportToDocument(
        source: File,
        uri: Uri,
        displayName: String,
        format: AudioFormat,
        strategy: FileConflictStrategy,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
        writingStartPercent: Int
    ): SoundlyResult<ExportResult> {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Invalid document URI" }
        val resolver = context.contentResolver
        val existingSize = querySize(resolver, uri)
        if (strategy == FileConflictStrategy.FAIL && existingSize > 0L) {
            throw ExportConflictException()
        }
        resolver.openOutputStream(uri, "rwt")?.use { output ->
            copyWithCancellation(source, output, onProgress, writingStartPercent)
        } ?: throw FileNotFoundException(uri.toString())
        return SoundlyResult.Success(
            ExportResult(
                destinationUri = uri.toString(),
                displayName = queryDisplayName(resolver, uri) ?: displayName,
                format = format,
                fileSizeBytes = source.length(),
                quality = quality
            )
        )
    }

    private fun resolveExportPlan(
        request: ExportRequest,
        resolved: ResolvedSource
    ): SoundlyResult<ExportPlan> = ExportQualityPolicy.resolve(
        sourceFormat = resolved.variant.format,
        targetFormat = request.format,
        requestedQuality = request.quality,
        sourceIsPcm16Wav = resolved.variant.format == AudioFormat.WAV &&
            isPcm16Wav(resolved.file)
    )

    private fun isPcm16Wav(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < WAV_HEADER_BYTES || input.readAscii(4) != "RIFF") return@use false
            input.skipBytes(4)
            if (input.readAscii(4) != "WAVE") return@use false
            while (input.filePointer + 8L <= input.length()) {
                val id = input.readAscii(4)
                val size = input.readLittleEndianUnsignedInt()
                val start = input.filePointer
                if (id == "fmt ") {
                    if (size < 16L) return@use false
                    val audioFormat = input.readLittleEndianUnsignedShort()
                    input.skipBytes(12)
                    val bitsPerSample = input.readLittleEndianUnsignedShort()
                    return@use audioFormat == PCM_WAV_FORMAT && bitsPerSample == PCM16_BITS
                }
                input.seek((start + size + (size and 1L)).coerceAtMost(input.length()))
            }
            false
        }
    }.getOrDefault(false)

    private fun RandomAccessFile.readAscii(count: Int): String =
        ByteArray(count).also(::readFully).toString(Charsets.US_ASCII)

    private fun RandomAccessFile.readLittleEndianUnsignedShort(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readLittleEndianUnsignedInt(): Long =
        (readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)) and 0xFFFF_FFFFL

    private suspend fun copyWithCancellation(
        source: File,
        output: OutputStream,
        onProgress: (ExportProgress) -> Unit,
        writingStartPercent: Int
    ) {
        val totalBytes = source.length().coerceAtLeast(1L)
        var bytesWritten = 0L
        var lastPercent = -1
        onProgress(
            ExportProgress(ExportStage.WRITING, writingStartPercent, 0L, totalBytes)
        )
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                bytesWritten += count
                val percent = (
                    writingStartPercent +
                        (bytesWritten * (99L - writingStartPercent) / totalBytes)
                    ).toInt().coerceIn(writingStartPercent, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(
                        ExportProgress(
                            ExportStage.WRITING,
                            percent,
                            bytesWritten,
                            totalBytes
                        )
                    )
                }
            }
            output.flush()
        }
    }

    private suspend fun resolveSource(
        projectId: String,
        variantId: String
    ): SoundlyResult<ResolvedSource> {
        if (projectId.isBlank()) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest("projectId"))
        }
        if (variantId.isBlank()) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest("variantId"))
        }
        val project = when (val result = projects.getProject(projectId)) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> return result
        }
        val variant = (listOf(project.original) + project.variants)
            .firstOrNull { it.id == variantId }
            ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("variantId"))
        val source = File(variant.filePath)
        if (!fileStore.isManaged(source)) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest("variantId"))
        }
        if (!source.isFile || !source.canRead()) {
            return SoundlyResult.Failure(SoundlyError.FileNotFound(source.absolutePath))
        }
        if (source.length() <= 0L) {
            return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
        }
        return SoundlyResult.Success(ResolvedSource(project, variant, source))
    }

    private fun queryExisting(
        resolver: ContentResolver,
        collection: Uri,
        name: String,
        relativePath: String
    ): Uri? = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(name, "$relativePath/"),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            Uri.withAppendedPath(collection, cursor.getLong(0).toString())
        } else {
            null
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long = resolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    } ?: 0L

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }

    private fun normalizeDisplayName(value: String, format: AudioFormat): String? {
        val extension = format.extension()
        val leafName = File(value).name
            .replace(CONTROL_FILE_NAME_CHARS, "_")
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .trim('.', ' ')
        val suffix = ".$extension"
        val baseName = if (leafName.endsWith(suffix, ignoreCase = true)) {
            leafName.dropLast(suffix.length)
        } else {
            leafName
        }.trim('.', ' ')
        val maxBaseLength = MAX_FILE_NAME_LENGTH - suffix.length
        val safeBase = baseName.take(maxBaseLength).trimEnd('.', ' ')
        return safeBase.takeIf { it.isNotBlank() }?.plus(suffix)
    }

    private fun estimateWavBytes(variant: AudioVariant): Long {
        if (variant.durationMs <= 0L || variant.sampleRate <= 0 || variant.channels <= 0) {
            return variant.fileSizeBytes.coerceAtLeast(1L) * 12L
        }
        return runCatching {
            Math.multiplyExact(
                Math.multiplyExact(variant.durationMs, variant.sampleRate.toLong()),
                variant.channels.toLong() * PCM16_BYTES_PER_SAMPLE
            ) / 1_000L + WAV_HEADER_BYTES
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun uniqueName(
        initialName: String,
        format: AudioFormat,
        nameExists: (String) -> Boolean
    ): String {
        if (!nameExists(initialName)) return initialName
        val extension = ".${format.extension()}"
        val base = initialName.dropLast(extension.length)
        var index = 1
        while (index < MAX_RENAME_ATTEMPTS) {
            val suffix = " ($index)$extension"
            val candidate = "${base.take(MAX_FILE_NAME_LENGTH - suffix.length)}$suffix"
            if (!nameExists(candidate)) return candidate
            index += 1
        }
        throw ExportConflictException()
    }

    private fun deleteShareCopy(file: File) {
        runCatching { fileStore.deleteFile(file) }
        runCatching { file.parentFile?.delete() }
    }

    private fun AudioFormat.extension(): String = when (this) {
        AudioFormat.WAV -> "wav"
        AudioFormat.M4A -> "m4a"
        AudioFormat.AAC -> "aac"
        AudioFormat.MP3 -> "mp3"
        AudioFormat.UNKNOWN -> throw IllegalArgumentException("Unknown format")
    }

    private fun AudioFormat.mimeType(): String = when (this) {
        AudioFormat.WAV -> "audio/wav"
        AudioFormat.M4A -> "audio/mp4"
        AudioFormat.AAC -> "audio/aac"
        AudioFormat.MP3 -> "audio/mpeg"
        AudioFormat.UNKNOWN -> throw IllegalArgumentException("Unknown format")
    }

    private data class ResolvedSource(
        val project: AudioProject,
        val variant: AudioVariant,
        val file: File
    )

    private class ExportConflictException : IllegalStateException()

    private companion object {
        const val FILE_PROVIDER_SUFFIX = "soundly.fileprovider"
        const val EXPORT_DIRECTORY_NAME = "Soundly"
        const val MAX_FILE_NAME_LENGTH = 120
        const val MAX_RENAME_ATTEMPTS = 10_000
        const val PCM16_BYTES_PER_SAMPLE = 2L
        const val PCM16_BITS = 16
        const val PCM_WAV_FORMAT = 1
        const val WAV_HEADER_BYTES = 44L
        const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        val CONTROL_FILE_NAME_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
        val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    }
}
