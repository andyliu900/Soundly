package com.ideacode.soundly_sdk.api.capability

import android.net.Uri
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.importer.AudioImportResult
import com.ideacode.soundly_sdk.api.model.importer.AudioMetadata
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.api.error.SoundlyError

interface AudioMetadataReader {
    suspend fun read(source: Uri): SoundlyResult<AudioMetadata>
}

interface AudioImporter {
    suspend fun importAudio(request: AudioImportRequest): SoundlyResult<AudioImportResult>
    /** Identified operations are idempotent and can be inspected after process termination. */
    suspend fun importAudio(operationId: String, request: AudioImportRequest): SoundlyResult<AudioImportResult> =
        SoundlyResult.Failure(SoundlyError.InvalidState("identifiedImportUnavailable"))
    suspend fun inspectImport(operationId: String): SoundlyResult<ImportOperationStatus> =
        SoundlyResult.Failure(SoundlyError.InvalidState("identifiedImportUnavailable"))
}
