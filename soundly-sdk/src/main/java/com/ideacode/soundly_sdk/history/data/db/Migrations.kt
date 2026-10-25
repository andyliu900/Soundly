package com.ideacode.soundly_sdk.history.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object Migrations {

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audio_project` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `recordingScene` TEXT,
                    `status` TEXT NOT NULL,
                    `originalVariantId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_audio_variant` (
                    `id` TEXT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `variantType` TEXT NOT NULL,
                    `presetOption` TEXT NOT NULL,
                    `filePath` TEXT NOT NULL,
                    `format` TEXT NOT NULL,
                    `mimeType` TEXT,
                    `sampleRate` INTEGER NOT NULL,
                    `channels` INTEGER NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `fileSizeBytes` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`projectId`) REFERENCES `audio_project`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_project_audio_variant_projectId` " +
                    "ON `project_audio_variant` (`projectId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audio_marker` (
                    `id` TEXT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `positionMs` INTEGER NOT NULL,
                    `label` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`projectId`) REFERENCES `audio_project`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_audio_marker_projectId` " +
                    "ON `audio_marker` (`projectId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `recovery_session` (
                    `id` TEXT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `tempFilePath` TEXT NOT NULL,
                    `recordedDurationMs` INTEGER NOT NULL,
                    `recordedBytes` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_recovery_session_projectId` " +
                    "ON `recovery_session` (`projectId`)"
            )

            migrateLegacyHistory(db)
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `project_audio_variant` " +
                    "ADD COLUMN `sourceVariantId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `project_audio_variant` " +
                    "ADD COLUMN `trimStartMs` INTEGER"
            )
            db.execSQL(
                "ALTER TABLE `project_audio_variant` " +
                    "ADD COLUMN `trimEndMs` INTEGER"
            )
        }
    }

    private fun migrateLegacyHistory(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO `audio_project` (
                `id`, `title`, `sourceType`, `recordingScene`, `status`,
                `originalVariantId`, `createdAt`, `updatedAt`
            )
            SELECT
                `id`,
                'Recording ' || substr(`id`, 1, 8),
                'IMPORTED',
                NULL,
                'AVAILABLE',
                'legacy_original_' || `id`,
                `createAt`,
                `createAt`
            FROM `audio_history`
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `project_audio_variant` (
                `id`, `projectId`, `variantType`, `presetOption`, `filePath`,
                `format`, `mimeType`, `sampleRate`, `channels`, `durationMs`,
                `fileSizeBytes`, `createdAt`
            )
            SELECT
                'legacy_original_' || `id`,
                `id`,
                'ORIGINAL',
                'RAW',
                `originalPath`,
                'UNKNOWN',
                NULL,
                `sampleRate`,
                `channels`,
                `durationMs`,
                0,
                `createAt`
            FROM `audio_history`
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `project_audio_variant` (
                `id`, `projectId`, `variantType`, `presetOption`, `filePath`,
                `format`, `mimeType`, `sampleRate`, `channels`, `durationMs`,
                `fileSizeBytes`, `createdAt`
            )
            SELECT
                'legacy_variant_' || `audio_variant`.`id`,
                `audio_variant`.`historyId`,
                'ENHANCED',
                `audio_variant`.`presetOption`,
                `audio_variant`.`filePath`,
                'WAV',
                'audio/wav',
                `audio_variant`.`sampleRate`,
                `audio_variant`.`channels`,
                `audio_variant`.`durationMs`,
                0,
                `audio_history`.`createAt`
            FROM `audio_variant`
            INNER JOIN `audio_history`
                ON `audio_history`.`id` = `audio_variant`.`historyId`
            """.trimIndent()
        )
    }
}
