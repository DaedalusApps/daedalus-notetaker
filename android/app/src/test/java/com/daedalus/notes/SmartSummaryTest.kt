package com.daedalus.notes

import com.daedalus.notes.ai.SmartAnalysis
import com.daedalus.notes.ai.SmartAnalysisParser
import com.daedalus.notes.ai.isTranscriptReadable
import com.daedalus.notes.data.model.DateUtils
import org.junit.Assert.*
import org.junit.Test

class SmartSummaryTest {

    @Test
    fun dateUtils_parseStandardFilename_returnsFormattedDate() {
        val filename = "20260524213434.mp3"
        val expected = "2026-05-24 21:34:34"
        val actual = DateUtils.parseDateFromFilename(filename)
        assertEquals(expected, actual)
    }

    @Test
    fun dateUtils_parseInvalidFilename_returnsOriginal() {
        val filename = "random_recording.mp3"
        val actual = DateUtils.parseDateFromFilename(filename)
        assertEquals(filename, actual)
    }

    @Test
    fun smartAnalysisParser_validJson_returnsAnalysis() {
        val json = """
            {
              "title": "Project Meeting",
              "shortSummary": "Discussed the new mind map feature.",
              "topics": ["MindMap", "Android", "TDD"],
              "fullSummary": "Detailed notes here..."
            }
        """.trimIndent()
        
        val analysis = SmartAnalysisParser.parse(json)
        
        assertEquals("Project Meeting", analysis.title)
        assertEquals("Discussed the new mind map feature.", analysis.shortSummary)
        assertEquals(listOf("MindMap", "Android", "TDD"), analysis.topics)
        assertEquals("Detailed notes here...", analysis.fullSummary)
    }

    @Test
    fun smartAnalysisParser_malformedJson_returnsEmptyFallback() {
        val json = "invalid json"
        val analysis = SmartAnalysisParser.parse(json)
        
        assertEquals("", analysis.title)
        assertEquals("", analysis.shortSummary)
        assertTrue(analysis.topics.isEmpty())
        assertTrue(analysis.fullSummary.contains("invalid json"))
    }

    @Test
    fun isTranscriptReadable_blankOrEmpty_returnsFalse() {
        assertFalse(isTranscriptReadable(""))
        assertFalse(isTranscriptReadable("   "))
    }

    @Test
    fun isTranscriptReadable_bracketTagsOnly_returnsFalse() {
        assertFalse(isTranscriptReadable("[laughter]"))
        assertFalse(isTranscriptReadable("  [music]  "))
        assertFalse(isTranscriptReadable("(silent)"))
        assertFalse(isTranscriptReadable("[laughter] (cough)"))
    }

    @Test
    fun isTranscriptReadable_tooShort_returnsFalse() {
        assertFalse(isTranscriptReadable("Hello."))
        assertFalse(isTranscriptReadable("Yes sure."))
        assertFalse(isTranscriptReadable("[laughter] Hello you")) // clean text has 2 words
    }

    @Test
    fun isTranscriptReadable_whisperLoopHallucination_returnsFalse() {
        // Single word repetition loop
        assertFalse(isTranscriptReadable("Thank you. Thank you. Thank you. Thank you. Thank you. Thank you. Thank you. Thank you."))
        // Two-word repetition loop
        assertFalse(isTranscriptReadable("Is there Is there Is there Is there Is there Is there Is there Is there"))
    }

    @Test
    fun isTranscriptReadable_normalReadableSpeech_returnsTrue() {
        assertTrue(isTranscriptReadable("This is a normal recording with some readable speech."))
        assertTrue(isTranscriptReadable("Meeting starts today at three PM [laughter] to discuss the project roadmap."))
    }
}
