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
class Migration10To11Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate10To11_keepsRecordingsAndAddsSplitColumns() {
        helper.createDatabase(testDbName, 10).apply {
            execSQL(
                """
                INSERT INTO recordings
                (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
                 createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete,
                 deviceSerial)
                VALUES
                ('20260716120000.mp3', '', 0, '', '', '', 1, 1000, 'Kept', '', '', 0, 0, 0, NULL)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 11, true, AppDatabase.MIGRATION_10_11)

        db.query("SELECT filename, title, parentFilename, partIndex FROM recordings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertEquals("Kept", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
        }
        db.close()
    }
}
