package com.ideacode.soundly_sdk.storage

import android.os.StatFs
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class DefaultAudioFileStore(
    filesDirectory: File,
    cacheDirectory: File,
    private val availableBytesProvider: (File) -> Long = { directory ->
        StatFs(directory.absolutePath).availableBytes
    }
) : AudioFileStore {

    override val rootDirectory: File = File(filesDirectory, ROOT_DIRECTORY).ensureDirectory()
    private val projectsDirectory = File(rootDirectory, PROJECTS_DIRECTORY).ensureDirectory()
    private val temporaryDirectory = File(rootDirectory, TEMP_DIRECTORY).ensureDirectory()
    private val recoveryDirectory = File(rootDirectory, RECOVERY_DIRECTORY).ensureDirectory()
    private val exportCacheDirectory = File(cacheDirectory, EXPORT_CACHE_DIRECTORY).ensureDirectory()
    private val legacyAudioDirectory = File(filesDirectory, LEGACY_AUDIO_DIRECTORY).canonicalFile

    override fun projectDirectory(projectId: String): File =
        safeChild(projectsDirectory, requireIdentifier(projectId)).ensureDirectory()

    override fun originalFile(projectId: String, format: AudioFormat): File =
        safeChild(
            safeChild(projectDirectory(projectId), ORIGINAL_DIRECTORY).ensureDirectory(),
            "original.${format.extension()}"
        )

    override fun variantFile(
        projectId: String,
        variantId: String,
        format: AudioFormat
    ): File = safeChild(
        safeChild(projectDirectory(projectId), VARIANTS_DIRECTORY).ensureDirectory(),
        "${requireIdentifier(variantId)}.${format.extension()}"
    )

    override fun waveformFile(projectId: String, variantId: String): File = safeChild(
        safeChild(projectDirectory(projectId), WAVEFORM_DIRECTORY).ensureDirectory(),
        "${requireIdentifier(variantId)}.bin"
    )

    override fun projectMetadataFile(projectId: String, fileName: String): File = safeChild(
        safeChild(projectDirectory(projectId), METADATA_DIRECTORY).ensureDirectory(),
        sanitizeFileName(fileName)
    )

    override fun recoveryFile(sessionId: String, format: AudioFormat): File = safeChild(
        recoveryDirectory,
        "${requireIdentifier(sessionId)}.${format.extension()}.partial"
    )

    override fun exportCacheFile(fileName: String): File = safeChild(
        exportCacheDirectory,
        sanitizeFileName(fileName)
    )

    override fun shareCacheFile(shareId: String, fileName: String): File = safeChild(
        safeChild(exportCacheDirectory, requireIdentifier(shareId)).ensureDirectory(),
        sanitizeFileName(fileName)
    )

    override fun createTempFile(suffix: String): File {
        val normalizedSuffix = suffix
            .takeIf { it.matches(SAFE_SUFFIX) }
            ?: throw IllegalArgumentException("Invalid temporary file suffix")
        return File.createTempFile("soundly_", normalizedSuffix, temporaryDirectory)
    }

    override fun copyAtomically(input: InputStream, destination: File): File {
        requireManagedDestination(destination)
        destination.parentFile?.ensureDirectory()
        val stagingFile = createSiblingStagingFile(destination)
        try {
            FileOutputStream(stagingFile).use { output ->
                input.use { source -> source.copyTo(output, DEFAULT_BUFFER_SIZE) }
                output.fd.sync()
            }
            return moveAtomically(stagingFile, destination)
        } catch (error: Exception) {
            stagingFile.delete()
            throw error
        }
    }

    override fun moveAtomically(source: File, destination: File): File {
        require(source.exists()) { "Source file does not exist" }
        requireManagedDestination(destination)
        destination.parentFile?.ensureDirectory()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        return destination
    }

    override fun deleteFile(file: File): Boolean {
        requireDeletable(file)
        return !file.exists() || file.delete()
    }

    override fun deleteProject(projectId: String): Boolean {
        val directory = safeChild(projectsDirectory, requireIdentifier(projectId))
        return !directory.exists() || directory.deleteRecursively()
    }

    override fun stageDeletion(file: File): StagedFileDeletion? {
        requireDeletable(file)
        if (!file.exists()) return null
        val staged = safeChild(
            temporaryDirectory,
            "delete_${UUID.randomUUID()}_${sanitizeFileName(file.name)}"
        )
        moveAtomically(file, staged)
        return StagedFileDeletion(original = file, staged = staged)
    }

    override fun commitDeletion(staged: StagedFileDeletion): Boolean {
        requireManagedDestination(staged.staged)
        return !staged.staged.exists() || staged.staged.deleteRecursively()
    }

    override fun rollbackDeletion(staged: StagedFileDeletion): Boolean {
        requireManagedDestination(staged.original)
        requireManagedDestination(staged.staged)
        if (!staged.staged.exists()) return staged.original.exists()
        return runCatching {
            moveAtomically(staged.staged, staged.original)
            true
        }.getOrDefault(false)
    }

    override fun clearTemporaryFiles(): Long =
        clearDirectory(temporaryDirectory) + clearDirectory(exportCacheDirectory)

    override fun storageUsage(): AudioStorageUsage {
        var originalsBytes = 0L
        var variantsBytes = legacyAudioDirectory.sizeRecursively()
        var auxiliaryBytes = 0L
        projectsDirectory.listFiles().orEmpty().forEach { project ->
            originalsBytes += File(project, ORIGINAL_DIRECTORY).sizeRecursively()
            variantsBytes += File(project, VARIANTS_DIRECTORY).sizeRecursively()
            auxiliaryBytes += File(project, WAVEFORM_DIRECTORY).sizeRecursively()
            auxiliaryBytes += File(project, METADATA_DIRECTORY).sizeRecursively()
        }
        return AudioStorageUsage(
            originalsBytes = originalsBytes,
            variantsBytes = variantsBytes,
            cacheBytes = exportCacheDirectory.sizeRecursively() +
                temporaryDirectory.sizeRecursively(),
            recoveryBytes = recoveryDirectory.sizeRecursively(),
            auxiliaryBytes = auxiliaryBytes
        )
    }

    override fun clearAllLocalFiles(): Long {
        val deletedBytes = clearDirectory(rootDirectory) +
            clearDirectory(exportCacheDirectory) +
            clearDirectory(legacyAudioDirectory)
        projectsDirectory.ensureDirectory()
        temporaryDirectory.ensureDirectory()
        recoveryDirectory.ensureDirectory()
        return deletedBytes
    }

    override fun isManaged(file: File): Boolean {
        val candidate = file.canonicalFile
        return candidate.isWithin(rootDirectory) ||
            candidate.isWithin(exportCacheDirectory) ||
            candidate.isWithin(legacyAudioDirectory)
    }

    override fun availableBytes(): Long = availableBytesProvider(rootDirectory)

    private fun clearDirectory(directory: File): Long {
        var deletedBytes = 0L
        directory.listFiles().orEmpty().forEach { child ->
            val size = child.sizeRecursively()
            if (!child.deleteRecursively()) {
                throw IOException("Unable to delete Soundly managed file: ${child.absolutePath}")
            }
            deletedBytes += size
        }
        return deletedBytes
    }

    private fun createSiblingStagingFile(destination: File): File = safeChild(
        requireNotNull(destination.parentFile),
        ".${destination.name}.${UUID.randomUUID()}.tmp"
    )

    private fun requireManagedDestination(file: File) {
        require(isManaged(file)) { "File is outside Soundly managed storage" }
    }

    private fun requireDeletable(file: File) {
        requireManagedDestination(file)
        val candidate = file.canonicalFile
        require(candidate != rootDirectory.canonicalFile) {
            "Soundly storage root cannot be deleted"
        }
        require(candidate != exportCacheDirectory.canonicalFile) {
            "Soundly export cache root cannot be deleted"
        }
        require(candidate != legacyAudioDirectory.canonicalFile) {
            "Soundly legacy audio root cannot be deleted"
        }
    }

    private fun safeChild(parent: File, childName: String): File {
        val child = File(parent, childName).canonicalFile
        require(child.isWithin(parent)) { "Path escapes Soundly managed storage" }
        return child
    }

    private fun requireIdentifier(value: String): String {
        require(value.matches(SAFE_IDENTIFIER)) { "Invalid Soundly identifier" }
        return value
    }

    private fun sanitizeFileName(value: String): String {
        val name = File(value).name
            .replace(CONTROL_FILE_NAME_CHARS, "_")
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .trim('.', ' ')
            .take(MAX_FILE_NAME_LENGTH)
        require(name.isNotBlank()) { "Invalid file name" }
        return name
    }

    private fun File.ensureDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to create directory: $absolutePath" }
    }

    private fun File.isWithin(parent: File): Boolean {
        val parentPath = parent.canonicalFile.toPath()
        return canonicalFile.toPath().startsWith(parentPath)
    }

    private fun File.sizeRecursively(): Long = when {
        isFile -> length()
        isDirectory -> listFiles().orEmpty().sumOf { it.sizeRecursively() }
        else -> 0L
    }

    private fun AudioFormat.extension(): String = when (this) {
        AudioFormat.WAV -> "wav"
        AudioFormat.M4A -> "m4a"
        AudioFormat.AAC -> "aac"
        AudioFormat.MP3 -> "mp3"
        AudioFormat.UNKNOWN -> throw IllegalArgumentException("Unknown audio format")
    }

    private companion object {
        const val ROOT_DIRECTORY = "soundly"
        const val PROJECTS_DIRECTORY = "projects"
        const val ORIGINAL_DIRECTORY = "original"
        const val VARIANTS_DIRECTORY = "variants"
        const val WAVEFORM_DIRECTORY = "waveform"
        const val METADATA_DIRECTORY = "metadata"
        const val TEMP_DIRECTORY = "temp"
        const val RECOVERY_DIRECTORY = "recovery"
        const val EXPORT_CACHE_DIRECTORY = "soundly-export-cache"
        const val LEGACY_AUDIO_DIRECTORY = "audio"
        const val MAX_FILE_NAME_LENGTH = 120

        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        val SAFE_SUFFIX = Regex("\\.[A-Za-z0-9]{1,10}")
        val CONTROL_FILE_NAME_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
        val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    }
}
