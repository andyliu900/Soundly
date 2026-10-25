package com.ideacode.soundly_sdk.api.capability

import android.net.Uri
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.importer.AudioImportResult
import com.ideacode.soundly_sdk.api.model.importer.AudioMetadata
import com.ideacode.soundly_sdk.api.result.SoundlyResult

interface AudioMetadataReader {
    suspend fun read(source: Uri): SoundlyResult<AudioMetadata>
}

interface AudioImporter {
    suspend fun importAudio(request: AudioImportRequest): SoundlyResult<AudioImportResult>
}
