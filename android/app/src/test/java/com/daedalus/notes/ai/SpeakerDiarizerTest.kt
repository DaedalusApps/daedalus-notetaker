package com.daedalus.notes.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDiarizerTest {

    @Test
    fun formatTranscript_addsSpeakerTagsAndParagraphs() {
        val raw = "Hello world. This is a test meeting. We are discussing the project. Next topic is AI initiative. Everything looks good."
        val formatted = SpeakerDiarizer.formatTranscript(raw)

        assertTrue("Should contain Speaker 1 tag", formatted.contains("Speaker 1:"))
        assertTrue("Should contain Speaker 2 tag", formatted.contains("Speaker 2:"))
        assertTrue("Should contain paragraph breaks", formatted.contains("\n\n"))
    }

    @Test
    fun formatTranscript_handlesBlankOrAlreadyFormattedText() {
        assertTrue(SpeakerDiarizer.formatTranscript("").isEmpty())

        val alreadyFormatted = "Speaker 1:\nHello there."
        assertTrue(SpeakerDiarizer.formatTranscript(alreadyFormatted) == alreadyFormatted)
    }
}
