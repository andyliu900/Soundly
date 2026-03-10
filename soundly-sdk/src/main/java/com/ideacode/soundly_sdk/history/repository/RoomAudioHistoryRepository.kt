package com.ideacode.soundly_sdk.history.repository

import com.ideacode.soundly_core.history.repository.AudioHistoryRepository
import com.ideacode.soundly_model.domain.AudioHistory
import com.ideacode.soundly_model.domain.AudioVariant
import com.ideacode.soundly_sdk.history.data.dao.AudioHistoryDao
import com.ideacode.soundly_sdk.history.data.entity.AudioVariantEntity
import com.ideacode.soundly_sdk.history.data.entity.toDomain
import com.ideacode.soundly_sdk.history.data.util.DomainEntityTrans
import timber.log.Timber

class RoomAudioHistoryRepository(
    private val dao: AudioHistoryDao
) : AudioHistoryRepository {

    override suspend fun loadAll(): List<AudioHistory> {
        return dao.getAllHistory().map { history ->

            Timber.i("history: $history")

            val variants = dao.getVariants(historyId = history.id)

            Timber.i("variants: $variants")

            history.toDomain(variants)
        }
    }

    override suspend fun save(history: AudioHistory) {
        dao.insertHistory(DomainEntityTrans.historyDomainToEntity(history))
    }

    override suspend fun addVariant(
        historyId: String,
        variant: AudioVariant
    ) {
        dao.insertVariant(DomainEntityTrans.variantDomainToEntity(historyId, variant))
    }

    override suspend fun getVariantList(historyId: String): List<AudioVariant> {
        val audioVariantEntityList = dao.getVariants(historyId)

        var audioVariantList = mutableListOf<AudioVariant>()
        audioVariantEntityList.forEach { it
            var audioVariant = it.toDomain()
            audioVariantList.add(audioVariant)
        }

        return audioVariantList
    }

    override suspend fun delete(historyId: String) {
        dao.deleteHistory(historyId)
    }




}