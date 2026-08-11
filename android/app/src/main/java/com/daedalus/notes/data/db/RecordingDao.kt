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

    @Query("SELECT * FROM recordings WHERE pendingDelete = 0 AND parentFilename IS NULL ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE filename = :filename")
    suspend fun get(filename: String): Recording?

    @Query("""SELECT * FROM recordings WHERE
    (filename LIKE '%' || :q || '%' OR
    transcript LIKE '%' || :q || '%' OR
    summary LIKE '%' || :q || '%') AND
    pendingDelete = 0 AND parentFilename IS NULL
    ORDER BY createdAt DESC""")
    fun searchFlow(q: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE parentFilename = :parent ORDER BY partIndex ASC")
    suspend fun getPartsOf(parent: String): List<Recording>

    /** Filenames of every recording that has parts — lets the list show the expand affordance
     *  without a per-row query. */
    @Query("SELECT DISTINCT parentFilename FROM recordings WHERE parentFilename IS NOT NULL")
    fun parentsWithPartsFlow(): Flow<List<String>>

    @Query("DELETE FROM recordings WHERE parentFilename = :parent")
    suspend fun deletePartsOf(parent: String)

    @Query("SELECT * FROM recordings WHERE pendingDelete = 1")
    suspend fun getPendingDeletes(): List<Recording>

    // Parts are excluded like everywhere else: a split parent already carries the joined
    // transcript and the stitched part summaries, so including both would feed TODO
    // extraction every long recording's content twice.
    @Query("""SELECT * FROM recordings WHERE createdAt >= :cutoff AND pendingDelete = 0
    AND parentFilename IS NULL ORDER BY createdAt DESC""")
    suspend fun getSince(cutoff: Long): List<Recording>

    @Query("UPDATE recordings SET pendingDelete = :pendingDelete WHERE filename = :filename")
    suspend fun updatePendingDelete(filename: String, pendingDelete: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: Recording)

    @Query("UPDATE recordings SET title = :title, shortSummary = :shortSummary WHERE filename = :filename")
    suspend fun updateTitleAndSummary(filename: String, title: String, shortSummary: String)

    @Query("UPDATE recordings SET embedding = :embedding WHERE filename = :filename")
    suspend fun updateEmbeddingBytes(filename: String, embedding: ByteArray)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("UPDATE recordings SET transcript = '', summary = '', mindMap = '', title = '', shortSummary = '', topics = '', embedding = NULL")
    suspend fun wipeAllAnalysis()
}

