package com.ideacode.soundly_sdk.history.recoder

import com.ideacode.soundly_core.history.recorder.HistoryRecorder
import com.ideacode.soundly_core.history.repository.AudioHistoryRepository
import com.ideacode.soundly_model.audio.dsp.PresetOption
import com.ideacode.soundly_model.domain.AudioHistory
import com.ideacode.soundly_model.domain.AudioVariant
import java.util.UUID

class RoomHistoryRecoder(
    private val repository: AudioHistoryRepository
) : HistoryRecorder {

    override suspend fun onProcessStart(
        originalPath: String,
        sampleRate: Int,
        channels: Int,
        durationMs: Long
    ): String {

        val historyId = UUID.randomUUID().toString()

        repository.save(
            AudioHistory(
                id = historyId,
                original = AudioVariant(
                    filePath = originalPath,
                    presetOption = PresetOption.RAW,
                    isOriginal = true,
                    sampleRate = sampleRate,
                    channels = channels,
                    durationMs = durationMs
                ),
                variants = emptyList(),
                createAt = System.currentTimeMillis()
            )
        )

        return historyId
    }

    override suspend fun onVariantGenerated(
        historyId: String,
        variant: AudioVariant
    ) {
        repository.addVariant(historyId, variant)
    }
}