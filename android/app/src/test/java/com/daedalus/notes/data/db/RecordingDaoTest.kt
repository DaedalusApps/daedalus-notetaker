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

    @Test
    fun countOtherSharingPath_parentAndChild_returnsCorrectCounts() = runBlocking {
        val path = "/data/file.mp3"
        dao.upsert(Recording(filename = "parent.mp3", localPath = path))
        dao.upsert(Recording(filename = "child.mp3", localPath = path, parentFilename = "parent.mp3"))

        // For child, parent shares it: returns 1
        assertEquals(1, dao.countOtherSharingPath(path, "child.mp3"))

        // For parent, child is excluded because its parentFilename == parent.mp3: returns 0
        assertEquals(0, dao.countOtherSharingPath(path, "parent.mp3"))
    }

    @Test
    fun countOtherSharingPath_twoStandalone_returnsOneForBoth() = runBlocking {
        val path = "/data/standalone.mp3"
        dao.upsert(Recording(filename = "file1.mp3", localPath = path))
        dao.upsert(Recording(filename = "file2.mp3", localPath = path))

        assertEquals(1, dao.countOtherSharingPath(path, "file1.mp3"))
        assertEquals(1, dao.countOtherSharingPath(path, "file2.mp3"))
    }

    @Test
    fun countOtherSharingPath_standaloneAlone_returnsZero() = runBlocking {
        val path = "/data/alone.mp3"
        dao.upsert(Recording(filename = "alone.mp3", localPath = path))

        assertEquals(0, dao.countOtherSharingPath(path, "alone.mp3"))
    }
}
