package com.ideacode.soundly_sdk.history.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ideacode.soundly_sdk.history.data.entity.AudioHistoryEntity
import com.ideacode.soundly_sdk.history.data.entity.AudioVariantEntity

@Dao
interface AudioHistoryDao {

    @Query("SELECT * FROM audio_history ORDER BY createAt DESC")
    suspend fun getAllHistory(): List<AudioHistoryEntity>

    @Query("SELECT * FROM audio_variant WHERE historyId = :historyId")
    suspend fun getVariants(historyId: String): List<AudioVariantEntity>

    @Insert
    suspend fun insertHistory(entity: AudioHistoryEntity)

    @Insert
    suspend fun insertVariant(entity: AudioVariantEntity)

    @Query("DELETE FROM audio_history WHERE id = :id")
    suspend fun deleteHistory(id: String)

}