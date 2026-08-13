package com.daedalus.notes.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration13To12Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val recordingColumns = """
        (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
         createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete,
         deviceSerial, parentFilename, partIndex, analysisFailed)
    """.trimIndent()

    @Test
    fun migrate13To12_keepsAllRecordingsAndTodosUnchanged() {
        helper.createDatabase(testDbName, 12).apply {
            execSQL(
                """
                INSERT INTO recordings $recordingColumns
                VALUES
                ('20260716120000.mp3', '/rec/1.mp3', 1234, 'We reviewed the quarterly budget initiative today',
                 'Budget review summary', '{"nodes":[]}', 1, 1000, 'Budget Meeting', 'short', 'finance,budget', 60000, 0, 0,
                 'SERIAL1', NULL, 0, 0),
                ('20260716130000.mp3', '/rec/2.mp3', 5678, 'Discussed the new marketing plan',
                 'Marketing summary', '{"nodes":[]}', 2, 2000, 'Marketing Sync', 'short2', 'marketing', 30000, 1, 1,
                 NULL, '20260716120000.mp3', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO todos (text, isDone, createdAt, sourceFilename, isAiGenerated)
                VALUES ('Send follow-up email', 0, 5000, '20260716120000.mp3', 1)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 13, true, AppDatabase.MIGRATION_12_13
        )
        db.close()

        val downgraded = helper.runMigrationsAndValidate(
            testDbName, 12, true, AppDatabase.MIGRATION_13_12
        )

        // Every pre-existing recording row must survive the downgrade with all column values
        // unchanged -- the whole point of this migration is that data is not lost.
        downgraded.query(
            "SELECT filename, localPath, sizeBytes, transcript, summary, mindMap, category, " +
                "createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete, " +
                "deviceSerial, parentFilename, partIndex, analysisFailed " +
                "FROM recordings ORDER BY createdAt"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertEquals("/rec/1.mp3", cursor.getString(1))
            assertEquals(1234, cursor.getLong(2))
            assertEquals("We reviewed the quarterly budget initiative today", cursor.getString(3))
            assertEquals("Budget review summary", cursor.getString(4))
            assertEquals("{\"nodes\":[]}", cursor.getString(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals(1000, cursor.getLong(7))
            assertEquals("Budget Meeting", cursor.getString(8))
            assertEquals("short", cursor.getString(9))
            assertEquals("finance,budget", cursor.getString(10))
            assertEquals(60000, cursor.getLong(11))
            assertEquals(0, cursor.getInt(12))
            assertEquals(0, cursor.getInt(13))
            assertEquals("SERIAL1", cursor.getString(14))
            assertEquals(null, cursor.getString(15))
            assertEquals(0, cursor.getInt(16))
            assertEquals(0, cursor.getInt(17))

            assertTrue(cursor.moveToNext())
            assertEquals("20260716130000.mp3", cursor.getString(0))
            assertEquals("/rec/2.mp3", cursor.getString(1))
            assertEquals(5678, cursor.getLong(2))
            assertEquals("Discussed the new marketing plan", cursor.getString(3))
            assertEquals("Marketing summary", cursor.getString(4))
            assertEquals("{\"nodes\":[]}", cursor.getString(5))
            assertEquals(2, cursor.getInt(6))
            assertEquals(2000, cursor.getLong(7))
            assertEquals("Marketing Sync", cursor.getString(8))
            assertEquals("short2", cursor.getString(9))
            assertEquals("marketing", cursor.getString(10))
            assertEquals(30000, cursor.getLong(11))
            assertEquals(1, cursor.getInt(12))
            assertEquals(1, cursor.getInt(13))
            assertEquals(null, cursor.getString(14))
            assertEquals("20260716120000.mp3", cursor.getString(15))
            assertEquals(1, cursor.getInt(16))
            assertEquals(1, cursor.getInt(17))

            assertFalse(cursor.moveToNext())
        }

        // The todo row must also survive untouched.
        downgraded.query(
            "SELECT text, isDone, createdAt, sourceFilename, isAiGenerated FROM todos"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Send follow-up email", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(5000, cursor.getLong(2))
            assertEquals("20260716120000.mp3", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertFalse(cursor.moveToNext())
        }

        downgraded.close()
    }

    @Test
    fun migrate13To12_dropsFtsTableAndSyncTriggers() {
        helper.createDatabase(testDbName, 12).apply {
            close()
        }
        helper.runMigrationsAndValidate(testDbName, 13, true, AppDatabase.MIGRATION_12_13).close()

        val downgraded = helper.runMigrationsAndValidate(
            testDbName, 12, true, AppDatabase.MIGRATION_13_12
        )

        downgraded.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='recordings_fts'"
        ).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }

        val triggerNames = listOf(
            "room_fts_content_sync_recordings_fts_BEFORE_UPDATE",
            "room_fts_content_sync_recordings_fts_BEFORE_DELETE",
            "room_fts_content_sync_recordings_fts_AFTER_UPDATE",
            "room_fts_content_sync_recordings_fts_AFTER_INSERT"
        )
        for (name in triggerNames) {
            downgraded.query(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND name=?",
                arrayOf(name)
            ).use { cursor ->
                assertFalse("trigger $name should have been dropped", cursor.moveToFirst())
            }
        }

        downgraded.close()
    }

    @Test
    fun roundTrip12To13To12_keepsDataAndValidatesAtV12() {
        helper.createDatabase(testDbName, 12).apply {
            execSQL(
                """
                INSERT INTO recordings $recordingColumns
                VALUES
                ('20260716140000.mp3', '/rec/3.mp3', 999, 'Round trip transcript',
                 'Round trip summary', '', 3, 3000, 'Round Trip', '', '', 15000, 0, 0,
                 NULL, NULL, 0, 0)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(testDbName, 13, true, AppDatabase.MIGRATION_12_13).close()

        val downgraded = helper.runMigrationsAndValidate(
            testDbName, 12, true, AppDatabase.MIGRATION_13_12
        )

        downgraded.query(
            "SELECT filename, transcript, summary FROM recordings WHERE filename = '20260716140000.mp3'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716140000.mp3", cursor.getString(0))
            assertEquals("Round trip transcript", cursor.getString(1))
            assertEquals("Round trip summary", cursor.getString(2))
        }

        downgraded.close()
    }
}
