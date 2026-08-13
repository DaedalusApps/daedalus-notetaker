package com.daedalus.notes.data.model

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 index over [Recording], external-content-backed so the (potentially large) transcript
 * text isn't duplicated in a second table -- SQLite stores only the tokenized index here and
 * reads the actual column values back from `recordings`.
 *
 * External content tables are NOT kept in sync automatically by SQLite or by Room: this table
 * is only updated by the `recordings_ai` / `recordings_au` / `recordings_ad` triggers created
 * in MIGRATION_12_13 (AppDatabase.kt). Those triggers are on the underlying `recordings` table,
 * so every write -- through any Dao method, present or future -- keeps this index current.
 */
@Fts4(contentEntity = Recording::class)
@Entity(tableName = "recordings_fts")
data class RecordingFts(
    val filename: String,
    val transcript: String,
    val summary: String
)
