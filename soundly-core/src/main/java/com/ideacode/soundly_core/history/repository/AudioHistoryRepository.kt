package com.ideacode.soundly_core.history.repository

import com.ideacode.soundly_model.domain.AudioHistory
import com.ideacode.soundly_model.domain.AudioVariant


interface AudioHistoryRepository {

    suspend fun loadAll(): List<AudioHistory>

    suspend fun save(history: AudioHistory)

    suspend fun addVariant(historyId: String, variant: AudioVariant)

    suspend fun getVariantList(historyId: String): List<AudioVariant>

    suspend fun delete(historyId: String)

}