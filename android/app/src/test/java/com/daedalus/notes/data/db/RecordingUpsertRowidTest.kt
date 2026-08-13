package com.daedalus.notes.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #125: RecordingDao.upsert compiled to INSERT OR REPLACE, and `recordings.filename` is a TEXT
 * PRIMARY KEY (not the rowid), so REPLACE deletes-then-reinserts on conflict, handing the row a
 * new rowid on every save. That rowid churn is real and pragma-independent -- see
 * [upsert_reSaveSameFilename_preservesRowid], which fails pre-fix.
 *
 * Room's InvalidationTracker unconditionally sets `PRAGMA recursive_triggers=ON` on every
 * database open, on every platform (confirmed against room-runtime 2.6.1's bytecode) -- there is
 * no Robolectric-vs-Android divergence here, and this test class intentionally does not touch
 * that pragma, so it runs in the exact configuration production uses. Under that pragma REPLACE's
 * implicit delete already fires Room's FTS content-sync BEFORE_DELETE trigger, so the FTS index
 * does not orphan today; see [ftsIntegrityCheck_holdsAfterRepeatedSaves] for that invariant. That
 * test is a pin, not a regression test -- it passes with the pre-fix REPLACE-based DAO too. The
 * value of @Upsert is that FTS integrity no longer depends on an undocumented Room pragma default
 * that a future Room release could change.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingUpsertRowidTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecordingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RecordingRepository(db.recordingDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rowidOf(filename: String): Long {
        db.openHelper.writableDatabase.query(
            "SELECT rowid FROM recordings WHERE filename = ?",
            arrayOf<Any>(filename)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun ftsIntegrityCheck() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO recordings_fts(recordings_fts) VALUES('integrity-check')"
        )
    }

    private suspend fun search(q: String): List<String> = repo.search(q).first().map { it.filename }

    @Test
    fun upsert_reSaveSameFilename_preservesRowid() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "old text", createdAt = 1))
        val rowidBefore = rowidOf("a.mp3")

        repo.save(Recording(filename = "a.mp3", transcript = "new text", createdAt = 1))
        val rowidAfter = rowidOf("a.mp3")

        assertEquals(rowidBefore, rowidAfter)
    }

    // Invariant pin, not a regression test -- passes with the pre-fix REPLACE-based DAO too
    // (Room forces `recursive_triggers=ON`, so REPLACE's implicit delete already fires the FTS
    // sync trigger). Kept because the invariant -- the FTS index stays structurally sound across
    // repeated saves -- is worth holding regardless of which DAO strategy is in effect.
    @Test
    fun ftsIntegrityCheck_holdsAfterRepeatedSaves() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "one", createdAt = 1))
        repo.save(Recording(filename = "a.mp3", transcript = "two", createdAt = 1))
        repo.save(Recording(filename = "a.mp3", transcript = "three", createdAt = 1))

        ftsIntegrityCheck()
    }

    @Test
    fun upsert_reSaveWithNewTranscript_dropsOldTermsFromIndex() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "alpha term", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("alpha"))

        repo.save(Recording(filename = "a.mp3", transcript = "beta term", createdAt = 1))

        assertTrue(search("alpha").isEmpty())
        assertEquals(listOf("a.mp3"), search("beta"))
    }

    @Test
    fun upsert_noExistingRow_insertsAndIsSearchable() = runBlocking {
        repo.save(Recording(filename = "new.mp3", transcript = "brand new content", createdAt = 1))

        assertEquals(listOf("new.mp3"), search("brand"))
    }

    @Test
    fun upsert_reSave_updatesFields() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "old", summary = "old summary", createdAt = 1))

        repo.save(Recording(filename = "a.mp3", transcript = "new", summary = "new summary", createdAt = 1))

        val result = repo.get("a.mp3")
        assertEquals("new", result?.transcript)
        assertEquals("new summary", result?.summary)
    }

    // `recordings` has no AUTOINCREMENT, so a deleted row's rowid can be reused by a later
    // insert (SQLite assigns max(rowid)+1 over the CURRENT rows, not ever-issued rowids -- the
    // claim in RecordingRepositorySearchTest that "rowids are never reused" was wrong). If a
    // delete ever left an orphaned FTS entry pointing at that rowid, a new recording taking the
    // freed rowid would silently inherit the old recording's indexed terms. This is an invariant
    // pin, not a #125 regression test -- it holds pre-fix too, because Room's forced
    // `recursive_triggers=ON` already makes the delete (both the raw DELETE and REPLACE's
    // implicit one) fire the FTS sync trigger correctly. It is still worth holding: it is the
    // scenario the false "rowids are never reused" comment was masking, and it protects against
    // a future Room release changing that pragma default.
    @Test
    fun delete_thenNewInsertReusingFreedRowid_doesNotInheritOldSearchTerms() = runBlocking {
        val original = Recording(filename = "old.mp3", transcript = "distinctive-term-zzz", createdAt = 1)
        repo.save(original)
        repo.save(original.copy(transcript = "different text"))
        repo.delete(repo.get("old.mp3")!!)

        repo.save(Recording(filename = "new.mp3", transcript = "unrelated content", createdAt = 2))

        assertTrue(search("distinctive-term-zzz").isEmpty())
    }
}
