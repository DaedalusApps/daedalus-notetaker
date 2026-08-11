package com.daedalus.notes.ai

import com.daedalus.notes.data.model.AudioUtils
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.viewmodel.PART_DURATION_MS

object MarkdownExporter {

    /**
     * One document for the whole recording. For a split recording pass its [parts] in order —
     * each contributes its own section, so the parts' transcripts read as one continuous
     * transcript instead of being spread across separate exports. The parent's own joined
     * transcript is then omitted, since the part sections already carry it.
     */
    fun export(recording: Recording, parts: List<Recording> = emptyList()): String = buildString {
        appendLine("# ${recording.title.ifBlank { recording.filename }}")
        if (parts.isNotEmpty()) {
            appendLine()
            appendLine("_${recording.filename} — ${AudioUtils.formatDuration(recording.durationMillis)} " +
                "in ${parts.size} part${if (parts.size == 1) "" else "s"}_")
        }
        // A split parent's summary is itself a concatenation of the part summaries, which the
        // part sections below emit in place — printing both gives the reader two sets of
        // "Part N" headings for the same content.
        if (recording.summary.isNotBlank() && parts.isEmpty()) {
            appendLine()
            appendLine("## Summary")
            appendLine(recording.summary)
        }
        if (recording.mindMap.isNotBlank()) {
            appendLine()
            appendLine("## Mind Map")
            appendLine(recording.mindMap)
        }

        if (parts.isEmpty()) {
            if (recording.transcript.isNotBlank()) {
                appendLine()
                appendLine("## Transcript")
                appendLine(recording.transcript)
            }
            return@buildString
        }

        parts.forEach { part ->
            // Derive the offset from partIndex, not a running sum: a part whose audio was
            // unreadable is skipped at analysis time, so parts can be [1, 3] and summing
            // durations would place part 3 at part 2's timestamp.
            val start = (part.partIndex - 1).coerceAtLeast(0) * PART_DURATION_MS
            appendLine()
            appendLine("## Part ${part.partIndex}: ${part.title.ifBlank { "Untitled" }} " +
                "(${AudioUtils.formatDuration(start)}–${AudioUtils.formatDuration(start + part.durationMillis)})")
            if (part.shortSummary.isNotBlank()) {
                appendLine()
                appendLine("_${part.shortSummary}_")
            }
            if (part.summary.isNotBlank()) {
                appendLine()
                appendLine("### Summary")
                // Every heading in a part summary is demoted below the part itself. Model
                // output regularly contains stray "#" lines, and an H1 halfway through a
                // part wrecks the document outline.
                appendLine(
                    part.summary
                        .replace(Regex("(?m)^#{1,4} "), "#### ")
                        .replace(Regex("(?m)^#+\\s*$\\R?"), "")  // bare "#" lines render as empty headings
                )
            }
            if (part.transcript.isNotBlank()) {
                appendLine()
                appendLine("### Transcript")
                appendLine(part.transcript)
            }
        }
    }


    fun exportQa(question: String, answer: String, sources: List<Recording>): String = buildString {
        appendLine("# Ask: $question")
        appendLine()
        appendLine(answer)
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("## Sources")
            sources.forEach { appendLine("- ${it.title.ifBlank { it.filename }}") }
        }
    }
}
