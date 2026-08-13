package com.daedalus.notes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings WHERE pendingDelete = 0 AND parentFilename IS NULL ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE filename = :filename")
    suspend fun get(filename: String): Recording?

    // #101: FTS4-backed search. [ftsQuery] is a pre-built, already-escaped MATCH expression --
    // see RecordingRepository.buildFtsMatchQuery -- never raw user input, so it can't be
    // misinterpreted as FTS operator syntax (", *, -, OR, NEAR, ...).
    @Query("""SELECT recordings.* FROM recordings
    JOIN recordings_fts ON recordings.rowid = recordings_fts.rowid
    WHERE recordings_fts MATCH :ftsQuery AND
    pendingDelete = 0 AND parentFilename IS NULL
    ORDER BY createdAt DESC""")
    fun searchFtsFlow(ftsQuery: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE parentFilename = :parent ORDER BY partIndex ASC")
    suspend fun getPartsOf(parent: String): List<Recording>

    /** Filenames of every recording that has parts — lets the list show the expand affordance
     *  without a per-row query. */
    @Query("SELECT DISTINCT parentFilename FROM recordings WHERE parentFilename IS NOT NULL")
    fun parentsWithPartsFlow(): Flow<List<String>>

    @Query("DELETE FROM recordings WHERE parentFilename = :parent")
    suspend fun deletePartsOf(parent: String)

    /** Every live row, child parts included — the one query that must not filter parts out.
     *  A backup built from [getAllFlow] loses each part's title/summary/mindMap/topics, which
     *  is hours of on-device Gemma work that a restore cannot recover. */
    @Query("SELECT * FROM recordings WHERE pendingDelete = 0 ORDER BY createdAt DESC")
    suspend fun getAllForBackup(): List<Recording>

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

    // #125: @Insert(onConflict = REPLACE) compiled to INSERT OR REPLACE, which on this TEXT
    // PRIMARY KEY table deletes-then-reinserts on conflict, reassigning the row's rowid on
    // every save. That rowid churn is real and pragma-independent.
    //
    // The FTS index survives it today only because Room's InvalidationTracker unconditionally
    // sets `PRAGMA recursive_triggers=ON` on every database open (production and Robolectric
    // alike), which makes REPLACE's implicit delete fire the room_fts_content_sync BEFORE_DELETE
    // trigger. Relying on that undocumented Room implementation detail for index integrity is
    // fragile -- a future Room release (e.g. the 2.7+/KMP InvalidationTracker rewrite) could
    // change it and silently orphan FTS rows.
    //
    // @Upsert performs a real UPDATE in place on conflict: rowid stays stable, the write is
    // roughly half the cost (no delete+reinsert of the base row), and FTS integrity no longer
    // depends on that pragma. This is defence-in-depth plus a correctness improvement, not a
    // fix for an observed production corruption.
    @Upsert
    suspend fun upsert(recording: Recording)

    @Query("UPDATE recordings SET title = :title, shortSummary = :shortSummary WHERE filename = :filename")
    suspend fun updateTitleAndSummary(filename: String, title: String, shortSummary: String)

    @Query("UPDATE recordings SET embedding = :embedding WHERE filename = :filename")
    suspend fun updateEmbeddingBytes(filename: String, embedding: ByteArray)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("SELECT COUNT(*) FROM recordings WHERE localPath = :path AND filename != :filename AND (parentFilename IS NULL OR parentFilename != :filename)")
    suspend fun countOtherSharingPath(path: String, filename: String): Int

    @Query("""UPDATE recordings SET transcript = '', summary = '', mindMap = '', title = '',
    shortSummary = '', topics = '', embedding = NULL, analysisFailed = 0""")
    suspend fun wipeAllAnalysis()
}

