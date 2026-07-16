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
class Migration8To9Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate8To9_keepsRecordingsAndAddsTodos() {
        helper.createDatabase(testDbName, 8).apply {
            execSQL(
                """
                INSERT INTO recordings
                (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
                 createdAt, title, shortSummary, topics, durationMillis, isLocal, pendingDelete)
                VALUES
                ('20260716120000.mp3', '', 0, '', '', '', 1, 1000, '', '', '', 0, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 9, true, AppDatabase.MIGRATION_8_9)

        // Recordings row survived the migration.
        db.query("SELECT filename FROM recordings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
        }

        // The new todos table accepts an insert and read-back.
        db.execSQL(
            "INSERT INTO todos (text, isDone, createdAt, sourceFilename, isAiGenerated) " +
                "VALUES ('buy milk', 0, 2000, NULL, 0)"
        )
        db.query("SELECT text, isDone FROM todos").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("buy milk", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.close()
    }
}
