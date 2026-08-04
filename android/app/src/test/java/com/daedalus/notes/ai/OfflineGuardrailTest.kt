package com.daedalus.notes.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.data.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineGuardrailTest {

    private lateinit var context: Context

    private fun prefs() =
        context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun defaultPrompt_containsGuardrail() {
        assertTrue(DEFAULT_PROMPT.contains(OFFLINE_GUARDRAIL))
    }

    @Test
    fun chunkSummaryPrompt_containsGuardrail() {
        assertTrue(CHUNK_SUMMARY_PROMPT.contains(OFFLINE_GUARDRAIL))
    }

    @Test
    fun todoExtractionPrompt_containsGuardrail() {
        assertTrue(TODO_EXTRACTION_PROMPT.contains(OFFLINE_GUARDRAIL))
    }

    @Test
    fun activePrompt_default_containsGuardrailExactlyOnce() {
        val prompt = activePrompt(context)
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun activePrompt_custom_containsGuardrailExactlyOnce() {
        prefs().edit().putString("custom_prompt", "My custom instructions.").commit()
        val prompt = activePrompt(context)
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun noteQuestionPrompt_containsGuardrail() {
        val prompt = buildNoteQuestionPrompt("Title", "Summary")
        assertTrue(prompt.contains(OFFLINE_GUARDRAIL))
    }

    @Test
    fun libraryQuestionPrompt_containsGuardrail() {
        val source = Recording(filename = "a.wav", title = "Note A", shortSummary = "Summary A")
        val prompt = buildLibraryQuestionPrompt(listOf(source))
        assertTrue(prompt.contains(OFFLINE_GUARDRAIL))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index != -1) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
