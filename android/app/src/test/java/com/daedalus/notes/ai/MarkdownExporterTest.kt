package com.daedalus.notes.ai

import com.daedalus.notes.data.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExporterTest {

    private val parent = Recording(
        filename = "20260811110046",
        transcript = "PART ONE WORDS\n\nPART TWO WORDS",
        summary = "Overall summary",
        title = "Interview",
        durationMillis = 25L * 60 * 1000
    )

    private val parts = listOf(
        Recording(
            filename = "20260811110046_p1",
            transcript = "PART ONE WORDS",
            summary = "First half",
            shortSummary = "Intro and scoping",
            title = "Opening",
            durationMillis = 15L * 60 * 1000,
            parentFilename = "20260811110046",
            partIndex = 1
        ),
        Recording(
            filename = "20260811110046_p2",
            transcript = "PART TWO WORDS",
            summary = "Second half",
            shortSummary = "Wrap up",
            title = "Closing",
            durationMillis = 10L * 60 * 1000,
            parentFilename = "20260811110046",
            partIndex = 2
        )
    )

    @Test
    fun export_withoutParts_keepsSingleTranscriptSection() {
        val md = MarkdownExporter.export(parent.copy(transcript = "ONLY WORDS"))

        assertTrue(md.contains("# Interview"))
        assertTrue(md.contains("## Transcript"))
        assertTrue(md.contains("ONLY WORDS"))
        assertFalse("no part sections for an unsplit recording", md.contains("## Part 1"))
    }

    @Test
    fun export_withParts_emitsEveryPartInOrderWithTimeRanges() {
        val md = MarkdownExporter.export(parent, parts)

        // Ranges use AudioUtils.formatDuration, the same formatter the recording list uses,
        // so a duration reads identically in the app and in the export.
        assertTrue(md.contains("## Part 1: Opening (0:00–15:00)"))
        assertTrue(md.contains("## Part 2: Closing (15:00–25:00)"))
        assertTrue(md.indexOf("PART ONE WORDS") < md.indexOf("PART TWO WORDS"))
        assertTrue(md.contains("Intro and scoping"))
        assertTrue(md.contains("in 2 parts"))
    }

    @Test
    fun export_withParts_doesNotDuplicateTheTranscript() {
        val md = MarkdownExporter.export(parent, parts)

        // The parent's joined transcript would repeat every word already carried by the
        // part sections, doubling the size of a 45-minute export.
        assertEquals(1, Regex("PART ONE WORDS").findAll(md).count())
        assertEquals(1, Regex("PART TWO WORDS").findAll(md).count())
    }

    @Test
    fun export_withParts_emitsEachPartHeadingExactlyOnce() {
        // The parent's summary is a concatenation of the part summaries using the same
        // "## Part N" headings, so emitting both produced two sets of identical headings.
        val md = MarkdownExporter.export(
            parent.copy(summary = "## Part 1: Opening\nFirst half\n\n## Part 2: Closing\nSecond half"),
            parts
        )

        assertEquals(1, Regex("(?m)^## Part 1:").findAll(md).count())
        assertEquals(1, Regex("(?m)^## Part 2:").findAll(md).count())
    }

    @Test
    fun export_withParts_demotesHeadingsInsideAPartSummary() {
        val md = MarkdownExporter.export(
            parent,
            listOf(parts[0].copy(summary = "Recap\n\n## Action Items\n- [ ] send notes"))
        )

        assertTrue("part sub-headings must nest under the part", md.contains("#### Action Items"))
        assertFalse(md.contains("\n## Action Items"))
    }

    @Test
    fun export_demotesStrayTopLevelHeadingsFromModelOutput() {
        // Degraded Gemma output leaks bare "#" lines; an H1 mid-part wrecks the outline.
        val md = MarkdownExporter.export(
            parent,
            listOf(parts[0].copy(summary = "# this are an.\n### also this"))
        )

        assertFalse(Regex("(?m)^# this are an\\.").containsMatchIn(md))
        assertTrue(md.contains("#### this are an."))
        assertTrue(md.contains("#### also this"))
    }

    @Test
    fun export_dropsBareHashLinesFromModelOutput() {
        val md = MarkdownExporter.export(
            parent,
            listOf(parts[0].copy(summary = "Recap\n#\nmore text\n##\nend"))
        )

        assertFalse("a lone # renders as an empty heading", Regex("(?m)^#+\\s*$").containsMatchIn(md))
        assertTrue(md.contains("Recap"))
        assertTrue(md.contains("more text"))
        assertTrue(md.contains("end"))
    }

    @Test
    fun export_withOnePart_readsAsSingular() {
        val md = MarkdownExporter.export(parent, parts.take(1))

        assertTrue(md.contains("in 1 part_"))
    }

    @Test
    fun export_withBlankPartTitle_fallsBackToUntitled() {
        val md = MarkdownExporter.export(parent, listOf(parts[0].copy(title = "")))

        assertTrue(md.contains("## Part 1: Untitled"))
    }
}
