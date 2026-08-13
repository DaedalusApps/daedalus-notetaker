package com.daedalus.notes.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.data.model.RecordingFts
import com.daedalus.notes.data.model.TodoItem

@Database(entities = [Recording::class, TodoItem::class, RecordingFts::class], version = 13, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    abstract fun todoDao(): TodoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN embedding BLOB")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN isLocal INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS todos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text TEXT NOT NULL,
                        isDone INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        sourceFilename TEXT,
                        isAiGenerated INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN deviceSerial TEXT")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN parentFilename TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN partIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN analysisFailed INTEGER NOT NULL DEFAULT 0")
            }
        }

        // #101: FTS4 search index over recordings (filename, transcript, summary), external-
        // content-backed by `recordings` so transcript text isn't duplicated. The CREATE VIRTUAL
        // TABLE text below must byte-for-byte match what Room's annotation processor generates
        // for RecordingFts (see app/schemas/.../13.json) or Room's identity check fails at
        // runtime on the owner's device.
        //
        // Room owns index synchronisation for a `contentEntity` FTS table: RoomOpenHelper's
        // onPostMigrate step (which runs after every migration, including this one) generates
        // its own room_fts_content_sync_recordings_fts_* triggers for INSERT/UPDATE/DELETE on
        // `recordings`. This migration's only jobs are creating the table and back-filling rows
        // that already existed before the index did -- Room's triggers only cover writes made
        // after they're created, so old rows would otherwise be permanently unsearchable.
        //
        // This migration used to also hand-write recordings_ai/ad/au triggers. They were
        // removed: they were both redundant with Room's own triggers and semantically wrong for
        // a content= external-content table (their AFTER UPDATE/DELETE bodies re-read the
        // content table to learn which terms to un-index, but by AFTER time the row already
        // holds the new/deleted state, so they un-indexed the wrong terms). The DROP statements
        // below are defensive: DBUtil.dropFtsSyncTriggers only ever cleans up triggers named
        // `room_fts_content_sync_*`, so any hand-written trigger, once created, would never be
        // removed by Room and would diverge permanently from a clean install.
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `recordings_fts` USING FTS4(`filename` TEXT NOT NULL, `transcript` TEXT NOT NULL, `summary` TEXT NOT NULL, content=`recordings`)"
                )
                // Defensive: a debug build from during development of #101 may carry these from
                // an earlier version of this migration. Production installs (pre-#101 release)
                // never had them, but drop them unconditionally so no device can carry them.
                db.execSQL("DROP TRIGGER IF EXISTS recordings_ai")
                db.execSQL("DROP TRIGGER IF EXISTS recordings_ad")
                db.execSQL("DROP TRIGGER IF EXISTS recordings_au")
                // Populate the index for every row that already exists -- without this, search
                // silently returns nothing for the owner's 22 existing recordings after upgrade.
                db.execSQL(
                    "INSERT INTO recordings_fts(docid, filename, transcript, summary) " +
                        "SELECT rowid, filename, transcript, summary FROM recordings"
                )
            }
        }

        @VisibleForTesting
        internal fun buildDatabase(context: Context, name: String): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
                )
                .build()
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext, "daedalus_notes.db").also { INSTANCE = it }
            }
        }
    }
}
