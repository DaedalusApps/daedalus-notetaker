package com.daedalus.notes.ai

import android.app.Application
import com.daedalus.notes.data.RecordingRepository

/**
 * Runs the Gemma summarize/mind-map analysis plus embedding generation against an already-known
 * transcript and saves the result via [repo]. Extracted so this post-transcript pipeline is
 * defined exactly once and shared by RecordingViewModel.doAnalyze (after transcription) and
 * ConversationViewModel.endSession (transcript already in hand from the session file).
 *
 * [onProgress] receives the user-facing stage labels a caller with a progress indicator can show;
 * chunked transcripts report per-chunk progress because they take minutes.
 */
suspend fun analyzeTranscript(
    application: Application,
    llm: LocalLlmService,
    embedder: EmbeddingService,
    repo: RecordingRepository,
    filename: String,
    transcript: String,
    onProgress: ((String) -> Unit)? = null
) {
    llm.ensureLoaded()
    val chunks = chunkTranscript(transcript, aiTextBudget(application))
    val rawResponse = if (chunks.size == 1) {
        onProgress?.invoke("Analyzing with Gemma…")
        llm.generate(activePrompt(application), chunks[0])
    } else {
        val chunkSummaries = chunks.mapIndexed { i, chunk ->
            onProgress?.invoke("Analyzing part ${i + 1} of ${chunks.size}…")
            llm.generate(CHUNK_SUMMARY_PROMPT, chunk)
        }
        onProgress?.invoke("Synthesizing results…")
        llm.generate(activePrompt(application), chunkSummaries.joinToString("\n\n"))
    }
    val cleanJson = stripCodeFences(rawResponse)
    val analysis = SmartAnalysisParser.parse(cleanJson)

    val fullSummaryFinal = if ("## Action Items" !in analysis.fullSummary) {
        val items = extractActionItems(transcript)
        if (items.isNotEmpty()) {
            analysis.fullSummary.trimEnd() + "\n\n## Action Items\n" +
                items.joinToString("\n") { "- [ ] $it" }
        } else {
            analysis.fullSummary
        }
    } else {
        analysis.fullSummary
    }

    repo.updateSummary(
        filename = filename,
        summary = fullSummaryFinal,
        mindMap = analysis.mindMap,
        title = analysis.title,
        shortSummary = analysis.shortSummary,
        topics = analysis.topics
    )

    if (embedder.isReady) {
        embedder.ensureLoaded()
        val embText = "${analysis.shortSummary} ${analysis.topics.joinToString(" ")}"
        embedder.embed(embText)?.let { repo.updateEmbedding(filename, it) }
    }
}
