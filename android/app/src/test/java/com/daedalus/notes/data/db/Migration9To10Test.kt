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
class Migration9To10Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate9To10_keepsRecordingsAndAddsNullDeviceSerial() {
        helper.createDatabase(testDbName, 9).apply {
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

        val db = helper.runMigrationsAndValidate(testDbName, 10, true, AppDatabase.MIGRATION_9_10)

        db.query("SELECT filename, deviceSerial FROM recordings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260716120000.mp3", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
    }
}
