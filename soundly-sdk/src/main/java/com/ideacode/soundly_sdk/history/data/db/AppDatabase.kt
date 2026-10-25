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
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.project.data.entity.AudioMarkerEntity
import com.ideacode.soundly_sdk.project.data.entity.AudioProjectEntity
import com.ideacode.soundly_sdk.project.data.entity.ProjectAudioVariantEntity
import com.ideacode.soundly_sdk.project.data.entity.RecoverySessionEntity

@Database(
    entities = [
        AudioHistoryEntity::class,
        AudioVariantEntity::class,
        AudioProjectEntity::class,
        ProjectAudioVariantEntity::class,
        AudioMarkerEntity::class,
        RecoverySessionEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(PresetOptionConverter::class)
internal abstract class AppDatabase : RoomDatabase() {

    abstract fun audioHistoryDao(): AudioHistoryDao
    internal abstract fun audioProjectDao(): AudioProjectDao

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
                .addMigrations(Migrations.MIGRATION_2_3)
                .addMigrations(Migrations.MIGRATION_3_4)
                .fallbackToDestructiveMigrationFrom(1)
                .build().also { INSTANCE = it }
        }
    }
}
