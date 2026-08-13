package com.daedalus.notes.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    // NOTE: MigrationTestHelper's runMigrationsAndValidate does NOT run RoomOpenHelper's
    // onPostMigrate step, so the resulting database never gets Room's generated
    // room_fts_content_sync_recordings_fts_* triggers -- a topology that exists on no real
    // device (every real open, including the very first one after this migration, runs
    // onPostMigrate). This test therefore only proves the table is created and the back-fill
    // populates it; it does NOT exercise index-sync-on-write behaviour (insert/update/delete
    // keeping the FTS index correct). That's covered where the real topology exists: the
    // Room.inMemoryDatabaseBuilder-based tests in RecordingRepositorySearchTest.
    @Test
    fun migrate12To13_keepsRecordingsAndPopulatesFtsIndexFromExistingRows() {
        helper.createDatabase(testDbName, 12).apply {
            execSQL(
                """
                INSERT INTO recordings
                (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
                 createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete,
                 deviceSerial, parentFilename, partIndex, analysisFailed)
                VALUES
                ('20260716120000.mp3', '', 0, 'We reviewed the quarterly budget initiative today',
                 'Budget review summary', '', 1, 1000, 'Budget Meeting', '', '', 0, 0, 0,
                 NULL, NULL, 0, 0),
                ('20260716130000.mp3', '', 0, 'Discussed the new marketing plan',
                 'Marketing summary', '', 1, 2000, 'Marketing Sync', '', '', 0, 0, 0,
                 NULL, NULL, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 13, true, AppDatabase.MIGRATION_12_13)

        // Every pre-existing row must survive the migration unchanged.
        db.query("SELECT filename, transcript, summary FROM recordings ORDER BY createdAt").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertEquals("We reviewed the quarterly budget initiative today", cursor.getString(1))
            assertEquals("Budget review summary", cursor.getString(2))

            assertTrue(cursor.moveToNext())
            assertEquals("20260716130000.mp3", cursor.getString(0))
        }

        // The FTS index must be populated from the pre-existing rows by the migration itself --
        // an empty index here would silently break search for every recording already on the
        // owner's phone after the upgrade.
        db.query("SELECT filename FROM recordings_fts WHERE recordings_fts MATCH 'budget*'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertTrue(!cursor.moveToNext())
        }

        db.query("SELECT filename FROM recordings_fts WHERE recordings_fts MATCH 'marketing*'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716130000.mp3", cursor.getString(0))
            assertTrue(!cursor.moveToNext())
        }

        db.close()
    }
}
