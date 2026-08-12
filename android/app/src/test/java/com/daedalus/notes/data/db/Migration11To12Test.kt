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
class Migration11To12Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate11To12_keepsRecordingsAndDefaultsAnalysisFailedToFalse() {
        helper.createDatabase(testDbName, 11).apply {
            execSQL(
                """
                INSERT INTO recordings
                (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
                 createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete,
                 deviceSerial, parentFilename, partIndex)
                VALUES
                ('20260716120000.mp3', '', 0, '', '', '', 1, 1000, 'Kept', '', '', 0, 0, 0,
                 NULL, NULL, 0),
                ('20260716120000.mp3_p1', '', 0, '', '', '', 1, 1001, 'Part', '', '', 0, 0, 0,
                 NULL, '20260716120000.mp3', 1)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 12, true, AppDatabase.MIGRATION_11_12)

        // Every pre-existing recording must land as "not yet written off", or the upgrade would
        // silently stop auto-analysis from ever retrying rows it had never actually attempted.
        db.query("SELECT filename, title, partIndex, analysisFailed FROM recordings ORDER BY createdAt").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertEquals("Kept", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))

            assertTrue(cursor.moveToNext())
            assertEquals("20260716120000.mp3_p1", cursor.getString(0))
            assertEquals(1, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
        }
        db.close()
    }
}
