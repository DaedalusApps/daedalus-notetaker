package com.daedalus.notes.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.recordingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getSince_returnsOnlyFreshNonDeletedRows() = runBlocking {
        val cutoff = 1_000L
        dao.upsert(Recording(filename = "before.mp3", createdAt = cutoff - 1))
        dao.upsert(Recording(filename = "after.mp3", createdAt = cutoff + 1))
        dao.upsert(Recording(filename = "pending.mp3", createdAt = cutoff + 2, pendingDelete = true))

        val result = dao.getSince(cutoff)

        assertEquals(listOf("after.mp3"), result.map { it.filename })
    }
}
