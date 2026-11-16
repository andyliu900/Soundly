package com.ideacode.soundly_sdk.history.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate2To4PreservesLegacyHistoryAsProject() {
        helper.createDatabase(TEST_DATABASE, 2).apply {
            execSQL(
                "INSERT INTO audio_history " +
                    "(id, originalPath, sampleRate, channels, durationMs, createAt) " +
                    "VALUES ('legacy-project', '/audio/original.m4a', 44100, 1, 4200, 1000)"
            )
            execSQL(
                "INSERT INTO audio_variant " +
                    "(historyId, filePath, presetOption, sampleRate, channels, durationMs) " +
                    "VALUES ('legacy-project', '/audio/enhanced.wav', 'DEFAULT', 44100, 1, 4200)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4
        ).use { database ->
            database.query(
                "SELECT originalVariantId FROM audio_project WHERE id = 'legacy-project'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("legacy_original_legacy-project", cursor.getString(0))
            }
            database.query(
                "SELECT COUNT(*) FROM project_audio_variant " +
                    "WHERE projectId = 'legacy-project'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate3To4AddsNullableVariantLineageWithoutLosingData() {
        helper.createDatabase(TEST_DATABASE_V3, 3).apply {
            execSQL(
                "INSERT INTO audio_project " +
                    "(id, title, sourceType, recordingScene, status, originalVariantId, " +
                    "createdAt, updatedAt) VALUES " +
                    "('project-v3', 'Legacy project', 'IMPORTED', NULL, 'AVAILABLE', " +
                    "'variant-v3', 1, 1)"
            )
            execSQL(
                "INSERT INTO project_audio_variant " +
                    "(id, projectId, variantType, presetOption, filePath, format, mimeType, " +
                    "sampleRate, channels, durationMs, fileSizeBytes, createdAt) VALUES " +
                    "('variant-v3', 'project-v3', 'ORIGINAL', 'RAW', '/audio/source.wav', " +
                    "'WAV', 'audio/wav', 44100, 1, 1000, 128, 1)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V3,
            4,
            true,
            Migrations.MIGRATION_3_4
        ).use { database ->
            database.query(
                "SELECT sourceVariantId, trimStartMs, trimEndMs " +
                    "FROM project_audio_variant WHERE id = 'variant-v3'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(true, cursor.isNull(0))
                assertEquals(true, cursor.isNull(1))
                assertEquals(true, cursor.isNull(2))
            }
        }
    }

    @Test
    fun migrate4To5PreservesProjectsAndAddsImportJournal() {
        val name = "soundly-migration-v4-test"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO audio_project (id,title,sourceType,recordingScene,status,originalVariantId,createdAt,updatedAt) VALUES ('p','Old','IMPORTED',NULL,'AVAILABLE','v',1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, Migrations.MIGRATION_4_5).use { database ->
            database.query("SELECT title,sourceDisplayName FROM audio_project WHERE id='p'").use {
                it.moveToFirst(); assertEquals("Old", it.getString(0)); assertEquals(true, it.isNull(1))
            }
            database.execSQL("INSERT INTO import_operation (id,projectId,variantId,phase) VALUES ('o','p2','v2','RESERVED')")
            database.query("SELECT phase FROM import_operation WHERE id='o'").use { it.moveToFirst(); assertEquals("RESERVED", it.getString(0)) }
        }
    }

    private companion object {
        const val TEST_DATABASE = "soundly-migration-test"
        const val TEST_DATABASE_V3 = "soundly-migration-v3-test"
    }
}
