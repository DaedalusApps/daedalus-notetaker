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
class RoomJvmSmokeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadBackRecording() = runBlocking {
        val recording = Recording(filename = "20260716120000.mp3")

        db.recordingDao().upsert(recording)
        val loaded = db.recordingDao().get(recording.filename)

        assertEquals(recording.filename, loaded?.filename)
    }
}
