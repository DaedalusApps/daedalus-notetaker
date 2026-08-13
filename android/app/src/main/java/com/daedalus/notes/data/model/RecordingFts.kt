package com.daedalus.notes.data.model

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 index over [Recording], external-content-backed so the (potentially large) transcript
 * text isn't duplicated in a second table -- SQLite stores only the tokenized index here and
 * reads the actual column values back from `recordings`.
 *
 * External content tables are NOT kept in sync automatically by SQLite -- but for a Room
 * `contentEntity` FTS table like this one, Room itself generates and owns the sync triggers
 * (`room_fts_content_sync_recordings_fts_*`) during `onPostMigrate`, so every write through any
 * Dao method, present or future, keeps this index current. `AppDatabase.MIGRATION_12_13`'s only
 * jobs are creating this table and back-filling rows that already existed before the index did;
 * see its comment for details.
 */
@Fts4(contentEntity = Recording::class)
@Entity(tableName = "recordings_fts")
data class RecordingFts(
    val filename: String,
    val transcript: String,
    val summary: String
)
