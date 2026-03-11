package com.ideacode.soundly_sdk.history.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ideacode.soundly_sdk.history.data.converter.PresetOptionConverter
import com.ideacode.soundly_sdk.history.data.dao.AudioHistoryDao
import com.ideacode.soundly_sdk.history.data.entity.AudioHistoryEntity
import com.ideacode.soundly_sdk.history.data.entity.AudioVariantEntity

@Database(
    entities = [
        AudioHistoryEntity::class,
        AudioVariantEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(PresetOptionConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun audioHistoryDao(): AudioHistoryDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = INSTANCE ?: buildDatabase(context)

                INSTANCE = instance

                instance
            }
        }

        /**
         * 构建数据库实例
         */
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "soundly.db"
            )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}