package com.daedalus.notes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun upsert_and_get() = runBlocking {
        val rec = Recording(filename = "test.mp3", transcript = "hello")
        db.recordingDao().upsert(rec)
        val result = db.recordingDao().get("test.mp3")
        assertEquals("hello", result?.transcript)
    }

    @Test
    fun delete_removesRecording() = runBlocking {
        val rec = Recording(filename = "test.mp3")
        db.recordingDao().upsert(rec)
        db.recordingDao().delete(rec)
        assertNull(db.recordingDao().get("test.mp3"))
    }

    @Test
    fun markPendingDelete_excludesFromFlows() = runBlocking {
        val rec = Recording(filename = "test.mp3")
        db.recordingDao().upsert(rec)
        
        // Initially visible in flow
        var list = db.recordingDao().getAllFlow().first()
        assertEquals(1, list.size)

        // Mark pending delete
        db.recordingDao().updatePendingDelete("test.mp3", true)

        // Hidden from flow
        list = db.recordingDao().getAllFlow().first()
        assertTrue(list.isEmpty())

        // Still present in get() and getPendingDeletes()
        assertNotNull(db.recordingDao().get("test.mp3"))
        val pending = db.recordingDao().getPendingDeletes()
        assertEquals(1, pending.size)
        assertEquals("test.mp3", pending[0].filename)
    }
}
