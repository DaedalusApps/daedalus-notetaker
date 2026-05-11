package com.daedalus.notes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE filename = :filename")
    suspend fun get(filename: String): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)
}
