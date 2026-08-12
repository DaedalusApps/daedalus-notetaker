package com.daedalus.notes.ai

object SpeakerDiarizer {

    /**
     * Formats raw transcript text into structured speaker turns and clean paragraphs.
     * Uses sentence boundaries and turn-taking heuristics to alternate between Speaker 1 and Speaker 2.
     */
    fun formatTranscript(rawTranscript: String): String {
        if (rawTranscript.isBlank()) return ""

        // If transcript already contains explicit Speaker tags, preserve them
        if (rawTranscript.contains("Speaker 1:") || rawTranscript.contains("Speaker 2:")) {
            return rawTranscript
        }

        // Split transcript into sentence chunks
        val sentences = rawTranscript.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return rawTranscript

        val formattedBuilder = StringBuilder()
        var currentSpeaker = 1
        var currentChunkCount = 0
        // Group every 2-4 sentences into a speaker turn for natural readability
        val sentencesPerTurn = 3

        for ((index, sentence) in sentences.withIndex()) {
            if (currentChunkCount == 0) {
                if (index > 0) formattedBuilder.append("\n\n")
                formattedBuilder.append("Speaker $currentSpeaker:\n")
            } else {
                formattedBuilder.append(" ")
            }

            formattedBuilder.append(sentence.trim())
            currentChunkCount++

            if (currentChunkCount >= sentencesPerTurn) {
                currentSpeaker = if (currentSpeaker == 1) 2 else 1
                currentChunkCount = 0
            }
        }

        return formattedBuilder.toString()
    }
}
