package com.ideacode.soundly_core.history.recorder

import com.ideacode.soundly_model.domain.AudioVariant


interface HistoryRecorder {

    /**
     * 在一次音频处理开始时调用
     */
    suspend fun onProcessStart(
        originalPath: String,
        sampleRate: Int,
        channels: Int,
        durationMs: Long
    ): String

    /**
     * 每生成一个处理结果后调用
     */
    suspend fun onVariantGenerated(
        historyId: String,
        variant: AudioVariant
    )

}