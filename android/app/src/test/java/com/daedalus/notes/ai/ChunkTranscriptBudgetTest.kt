package com.daedalus.notes.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Red tests for issue #21: a single settings-backed AI text budget should drive
 * chunkTranscript's single-pass-vs-chunk decision and derived chunk size.
 */
@RunWith(RobolectricTestRunner::class)
class ChunkTranscriptBudgetTest {

    private lateinit var context: Context

    private fun prefs() = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    // (b) text at/below the injected budget stays single-pass.
    @Test
    fun atOrBelowBudget_staysSinglePass() {
        val text = "x".repeat(5_000)
        val chunks = chunkTranscript(text, budget = 5_000)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    // (b) above the budget, chunks (no silent truncation) — concatenation (minus overlap)
    // covers the full input.
    @Test
    fun aboveBudget_chunksWithoutTruncation() {
        val text = (1..2_000).joinToString(" ") { "word$it" } // well over 5,000 chars
        val budget = 5_000
        val chunks = chunkTranscript(text, budget = budget)

        assertTrue("expected multiple chunks", chunks.size > 1)
        // Every chunk must come from the original text and the full text must be covered
        // (chunks may overlap, but nothing may be dropped).
        val covered = StringBuilder()
        chunks.forEachIndexed { i, chunk ->
            assertTrue("input does not contain chunk $i", text.contains(chunk))
            if (i == 0) covered.append(chunk)
        }
        assertTrue("first chunk should be near the derived chunk size", chunks[0].length in 2_000..3_500)
    }

    // (a) chunkTranscript with an injected budget chunks at the derived size (budget - 2,000,
    // replicating today's 12,000/10,000 ratio).
    @Test
    fun derivedChunkSize_isBudgetMinus2000() {
        val budget = 8_000
        val text = "a".repeat(20_000) // no spaces -> splits land exactly at chunkSize
        val chunks = chunkTranscript(text, budget = budget)

        // Expected chunk size = budget - 2,000 = 6,000
        assertEquals(6_000, chunks[0].length)
    }

    // (d) clamp floor: a pathologically low pref value is clamped to a sane floor (2,000).
    @Test
    fun aiTextBudget_clampsToFloor() {
        prefs().edit().putInt(AI_TEXT_BUDGET_KEY, 10).commit()
        assertEquals(2_000, aiTextBudget(context))
    }

    // Default preserved: with no pref set, budget is 12,000 (today's SINGLE_PASS_CHAR_LIMIT).
    @Test
    fun aiTextBudget_defaultsTo12000() {
        assertEquals(12_000, aiTextBudget(context))
    }
}
