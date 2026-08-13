package com.daedalus.notes.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.data.db.AppDatabase
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
 * #101: search now runs through a Room FTS4 index (RecordingDao.searchFtsFlow) instead of the
 * old `LIKE '%q%'` scan. These tests cover every property the old query guaranteed, plus the
 * new escaping/tokenizing behaviour in RecordingRepository.buildFtsMatchQuery.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingRepositorySearchTest {

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

    private suspend fun search(q: String): List<String> = repo.search(q).first().map { it.filename }

    @Test
    fun search_wholeWordHit_returnsMatchingRow() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "We discussed the budget", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("budget"))
    }

    @Test
    fun search_prefixHit_matchesLongerWordStartingWithQuery() = runBlocking {
        // Preserved from the old LIKE behaviour: "init" must still find "initiative".
        repo.save(Recording(filename = "a.mp3", transcript = "A new initiative for Q3", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("init"))
    }

    @Test
    fun search_midWordFragment_noLongerMatches_pinnedBehaviourChange() = runBlocking {
        // BEHAVIOUR CHANGE from the old LIKE scan: "native" used to find "alternative" via
        // substring match. FTS4 has no substring index, only tokens/prefixes, so a mid-word
        // fragment no longer matches. This test pins the new, narrower behaviour deliberately.
        repo.save(Recording(filename = "a.mp3", transcript = "Consider an alternative approach", createdAt = 1))

        assertTrue(search("native").isEmpty())
    }

    @Test
    fun search_excludesPendingDeleteRows() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget talk", createdAt = 1, pendingDelete = true))

        assertTrue(search("budget").isEmpty())
    }

    @Test
    fun search_excludesParts() = runBlocking {
        repo.save(Recording(filename = "parent.mp3", transcript = "unrelated", createdAt = 1))
        repo.save(
            Recording(
                filename = "parent.mp3_p1",
                transcript = "budget talk",
                createdAt = 2,
                parentFilename = "parent.mp3"
            )
        )

        assertTrue(search("budget").isEmpty())
    }

    @Test
    fun search_ordersByCreatedAtDescending() = runBlocking {
        repo.save(Recording(filename = "old.mp3", transcript = "budget one", createdAt = 1))
        repo.save(Recording(filename = "new.mp3", transcript = "budget two", createdAt = 2))

        assertEquals(listOf("new.mp3", "old.mp3"), search("budget"))
    }

    @Test
    fun search_queryWithDoubleQuote_doesNotThrowAndReturnsMatch() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("\"budget\""))
    }

    @Test
    fun search_queryWithAsteriskAndDash_doesNotThrow() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("budget*-"))
    }

    @Test
    fun search_punctuationOnlyQuery_doesNotThrowAndReturnsEmpty() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertTrue(search("!!!---***").isEmpty())
    }

    // --- Finding B regression coverage: FTS sync must be done by Room's own generated
    // triggers, not the hand-written ones the migration used to create. These tests build a
    // real Room.inMemoryDatabaseBuilder DB, so (unlike Migration12To13Test, which uses
    // MigrationTestHelper and never runs onPostMigrate) Room's generated
    // room_fts_content_sync_recordings_fts_* triggers are actually present here.

    @Test
    fun search_updatingTranscriptViaRawUpdate_removesOldTerms_findsNewTerms() = runBlocking {
        // Bypasses the app's upsert(REPLACE) path on purpose: this isolates Finding B (custom
        // triggers reading the wrong row image on UPDATE) from the separately-tracked REPLACE
        // orphaning issue. A real SQL UPDATE is what exercises Room's AFTER_UPDATE/BEFORE_UPDATE
        // sync triggers.
        repo.save(Recording(filename = "a.mp3", transcript = "alpha term one", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("alpha"))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE recordings SET transcript = ? WHERE filename = ?",
            arrayOf("beta term two", "a.mp3")
        )

        assertTrue(search("alpha").isEmpty())
        assertEquals(listOf("a.mp3"), search("beta"))
    }

    @Test
    fun search_wipeAllAnalysis_removesTranscriptFromIndex() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "gamma term", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("gamma"))

        repo.wipeAllAnalysis()

        assertTrue(search("gamma").isEmpty())
    }

    @Test
    fun search_deletingRecording_removesFromIndex() = runBlocking {
        val recording = Recording(filename = "a.mp3", transcript = "delta term", createdAt = 1)
        repo.save(recording)
        assertEquals(listOf("a.mp3"), search("delta"))

        repo.delete(recording)

        assertTrue(search("delta").isEmpty())
    }

    @Test
    fun search_resavingExistingFilename_replacesIndexEntry() = runBlocking {
        // The app's real analyze-after-recording flow: repo.save on an existing filename goes
        // through dao.upsert (@Upsert, #125). This is covered separately from the raw-UPDATE
        // test above; see RecordingUpsertRowidTest for the rowid-stability and FTS-integrity
        // properties of that upsert -- rowids on this table are NOT permanently retired (no
        // AUTOINCREMENT, so a freed rowid can be reused by a later insert), so search must still
        // be correct here regardless.
        repo.save(Recording(filename = "a.mp3", transcript = "epsilon old text", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("epsilon"))

        repo.save(Recording(filename = "a.mp3", transcript = "zeta new text", createdAt = 1))

        assertTrue(search("epsilon").isEmpty())
        assertEquals(listOf("a.mp3"), search("zeta"))
    }
}
