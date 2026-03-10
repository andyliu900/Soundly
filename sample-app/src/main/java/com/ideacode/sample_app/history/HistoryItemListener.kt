package com.ideacode.sample_app.history

import com.ideacode.soundly_model.domain.AudioHistory
import com.ideacode.soundly_model.domain.AudioVariant


interface HistoryItemListener {

    /**
     *播放原始音频
     */
    fun onPlayOriginal(history: AudioHistory)

    /**
     * 播放处理后音频
     */
    fun onPlayVariant(audioVariant: AudioVariant)

    /**
     * 删除历史记录
     */
    fun onDelete(history: AudioHistory)

}