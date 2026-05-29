package com.daedalus.notes.ai

import com.daedalus.notes.data.model.Recording

object MarkdownExporter {

    fun export(recording: Recording): String = buildString {
        appendLine("# ${recording.filename}")
        if (recording.transcript.isNotBlank()) {
            appendLine()
            appendLine("## Transcript")
            appendLine(recording.transcript)
        }
        if (recording.summary.isNotBlank()) {
            appendLine()
            appendLine("## Summary")
            appendLine(recording.summary)
        }
        if (recording.mindMap.isNotBlank()) {
            appendLine()
            appendLine("## Mind Map")
            appendLine(recording.mindMap)
        }
    }
}
