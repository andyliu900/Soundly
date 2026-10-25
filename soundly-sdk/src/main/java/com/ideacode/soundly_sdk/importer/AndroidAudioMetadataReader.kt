package com.ideacode.soundly_sdk.importer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.importer.AudioMetadata
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

internal class AndroidAudioMetadataReader(
    private val context: Context,
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioMetadataReader {

    override suspend fun read(source: Uri): SoundlyResult<AudioMetadata> =
        withContext(dispatchers.io) {
            readOnIo(source)
        }

    private fun readOnIo(source: Uri): SoundlyResult<AudioMetadata> {
        if (source.scheme !in SUPPORTED_SCHEMES) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "source"))
        }
        if (source.scheme == ContentResolver.SCHEME_FILE) {
            val sourceFile = source.path?.let(::File)
            if (sourceFile == null || !sourceFile.isFile) {
                return SoundlyResult.Failure(
                    SoundlyError.FileNotFound(path = source.path)
                )
            }
        }

        val resolver = context.contentResolver
        val document = resolver.queryDocumentInfo(source)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, source, null)
            val trackFormat = extractor.firstAudioTrack()
                ?: return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)
            val sourceName = document.displayName ?: source.lastPathSegment
            val detectedMime = resolver.getType(source) ?: trackMime ?: DEFAULT_MIME
            val format = AudioFormatDetector.detect(detectedMime, sourceName)
                ?: return SoundlyResult.Failure(
                    SoundlyError.UnsupportedFormat(mimeType = detectedMime)
                )
            val mimeType = AudioFormatDetector.canonicalMimeType(format, detectedMime)
            val durationUs = trackFormat.longOrNull(MediaFormat.KEY_DURATION)
                ?: if (source.isRecoveryFile()) {
                    UNKNOWN_DURATION_US
                } else {
                    return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
                }
            val sampleRate = trackFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                ?: return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
            val channels = trackFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                ?: return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
            if (durationUs < 0L || sampleRate <= 0 || channels <= 0 ||
                (durationUs == 0L && !source.isRecoveryFile())
            ) {
                return SoundlyResult.Failure(SoundlyError.InvalidAudioFile())
            }

            val displayName = document.displayName
                ?.takeIf { it.isNotBlank() }
                ?: source.lastPathSegment?.substringAfterLast('/')
                ?: DEFAULT_DISPLAY_NAME
            val size = document.size
                ?.takeIf { it >= 0L }
                ?: resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
                ?: UNKNOWN_SIZE

            SoundlyResult.Success(
                AudioMetadata(
                    displayName = displayName,
                    format = format,
                    mimeType = mimeType,
                    durationMs = durationUs / 1_000L,
                    fileSizeBytes = size.coerceAtLeast(0L),
                    sampleRate = sampleRate,
                    channels = channels,
                    bitRate = trackFormat.intOrNull(MediaFormat.KEY_BIT_RATE)
                )
            )
        } catch (error: SecurityException) {
            SoundlyResult.Failure(SoundlyError.PermissionDenied)
        } catch (error: FileNotFoundException) {
            SoundlyResult.Failure(SoundlyError.FileNotFound(cause = error))
        } catch (error: IllegalArgumentException) {
            SoundlyResult.Failure(SoundlyError.InvalidAudioFile(error))
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.InvalidAudioFile(error))
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaExtractor.firstAudioTrack(): MediaFormat? {
        repeat(trackCount) { index ->
            val format = getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return format
            }
        }
        return null
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private fun Uri.isRecoveryFile(): Boolean =
        scheme == ContentResolver.SCHEME_FILE &&
            lastPathSegment?.endsWith(RECOVERY_FILE_SUFFIX, ignoreCase = true) == true

    private fun ContentResolver.queryDocumentInfo(uri: Uri): DocumentInfo {
        var cursor: Cursor? = null
        return try {
            cursor = query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            if (cursor?.moveToFirst() == true) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentInfo(
                    displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                )
            } else {
                DocumentInfo(null, null)
            }
        } catch (_: Exception) {
            DocumentInfo(null, null)
        } finally {
            cursor?.close()
        }
    }

    private data class DocumentInfo(
        val displayName: String?,
        val size: Long?
    )

    private companion object {
        const val DEFAULT_DISPLAY_NAME = "Imported audio"
        const val DEFAULT_MIME = "application/octet-stream"
        const val UNKNOWN_SIZE = 0L
        const val UNKNOWN_DURATION_US = 0L
        const val RECOVERY_FILE_SUFFIX = ".partial"
        val SUPPORTED_SCHEMES = setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)
    }
}
