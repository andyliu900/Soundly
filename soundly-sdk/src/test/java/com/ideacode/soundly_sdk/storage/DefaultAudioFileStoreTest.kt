package com.ideacode.soundly_sdk.storage

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DefaultAudioFileStoreTest {

    private val testRoot = Files.createTempDirectory("soundly-store-test").toFile()
    private val filesDirectory = testRoot.resolve("files")
    private val cacheDirectory = testRoot.resolve("cache")
    private val store = DefaultAudioFileStore(filesDirectory, cacheDirectory)

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun atomicCopyCreatesManagedOriginal() {
        val destination = store.originalFile("project_1", AudioFormat.M4A)
        val bytes = byteArrayOf(1, 2, 3, 4)

        store.copyAtomically(bytes.inputStream(), destination)

        assertTrue(destination.exists())
        assertTrue(store.isManaged(destination))
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(destination.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun projectIdCannotEscapeManagedDirectory() {
        store.projectDirectory("../outside")
    }

    @Test
    fun clearTemporaryFilesDoesNotDeleteProjectAudio() {
        val projectFile = store.originalFile("project_1", AudioFormat.WAV).apply {
            writeBytes(byteArrayOf(1))
        }
        store.createTempFile(".tmp").writeBytes(byteArrayOf(1, 2, 3))
        val shareFile = store.shareCacheFile("share_1", "会议.wav").apply {
            writeBytes(byteArrayOf(4, 5))
        }

        val deletedBytes = store.clearTemporaryFiles()

        assertTrue(deletedBytes >= 5L)
        assertTrue(projectFile.exists())
        assertFalse(shareFile.exists())
    }

    @Test
    fun deleteProjectOnlyDeletesRequestedProject() {
        val first = store.originalFile("first", AudioFormat.M4A).apply { writeText("first") }
        val second = store.originalFile("second", AudioFormat.M4A).apply { writeText("second") }

        assertTrue(store.deleteProject("first"))

        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun stagedDeletionCanBeRolledBack() {
        val original = store.originalFile("project_1", AudioFormat.M4A).apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val staged = requireNotNull(store.stageDeletion(original))
        assertFalse(original.exists())

        assertTrue(store.rollbackDeletion(staged))
        assertTrue(original.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun storageRootCannotBeDeleted() {
        store.deleteFile(store.rootDirectory)
    }

    @Test
    fun storageUsageSeparatesOriginalsVariantsAndCache() {
        store.originalFile("project_1", AudioFormat.WAV).writeBytes(ByteArray(11))
        store.variantFile("project_1", "variant_1", AudioFormat.WAV)
            .writeBytes(ByteArray(7))
        store.exportCacheFile("shared.wav").writeBytes(ByteArray(5))

        val usage = store.storageUsage()

        assertEquals(11L, usage.originalsBytes)
        assertEquals(7L, usage.variantsBytes)
        assertTrue(usage.cacheBytes >= 5L)
    }

    @Test
    fun shareCacheUsesIsolatedDirectoryAndKeepsUnicodeDisplayName() {
        val first = store.shareCacheFile("share_1", "中文会议.wav")
        val second = store.shareCacheFile("share_2", "中文会议.wav")

        first.writeBytes(byteArrayOf(1))
        second.writeBytes(byteArrayOf(2))

        assertEquals("中文会议.wav", first.name)
        assertEquals("中文会议.wav", second.name)
        assertTrue(first.parentFile != second.parentFile)
        assertTrue(store.isManaged(first))
        assertTrue(store.isManaged(second))
    }

    @Test
    fun clearAllLocalFilesKeepsStoreReusable() {
        store.originalFile("project_1", AudioFormat.WAV).writeBytes(ByteArray(3))
        store.variantFile("project_1", "variant_1", AudioFormat.WAV)
            .writeBytes(ByteArray(2))

        val deleted = store.clearAllLocalFiles()

        assertTrue(deleted >= 5L)
        val newFile = store.originalFile("project_2", AudioFormat.WAV)
        newFile.writeBytes(byteArrayOf(1))
        assertTrue(newFile.exists())
    }
}
