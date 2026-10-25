package com.ideacode.soundly_sdk.storage

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import java.io.File
import java.io.InputStream

/** Internal source of truth for every file owned by Soundly. */
internal interface AudioFileStore {
    val rootDirectory: File

    fun projectDirectory(projectId: String): File
    fun originalFile(projectId: String, format: AudioFormat): File
    fun variantFile(projectId: String, variantId: String, format: AudioFormat): File
    fun waveformFile(projectId: String, variantId: String): File
    fun projectMetadataFile(projectId: String, fileName: String = "session.json"): File
    fun recoveryFile(sessionId: String, format: AudioFormat): File
    fun exportCacheFile(fileName: String): File
    fun shareCacheFile(shareId: String, fileName: String): File
    fun createTempFile(suffix: String): File

    fun copyAtomically(input: InputStream, destination: File): File
    fun moveAtomically(source: File, destination: File): File
    fun deleteFile(file: File): Boolean
    fun deleteProject(projectId: String): Boolean
    fun stageDeletion(file: File): StagedFileDeletion?
    fun commitDeletion(staged: StagedFileDeletion): Boolean
    fun rollbackDeletion(staged: StagedFileDeletion): Boolean
    fun clearTemporaryFiles(): Long
    fun storageUsage(): AudioStorageUsage
    fun clearAllLocalFiles(): Long
    fun isManaged(file: File): Boolean
    fun availableBytes(): Long
}

internal data class AudioStorageUsage(
    val originalsBytes: Long,
    val variantsBytes: Long,
    val cacheBytes: Long,
    val recoveryBytes: Long,
    val auxiliaryBytes: Long
)

internal data class StagedFileDeletion(
    val original: File,
    val staged: File
)
