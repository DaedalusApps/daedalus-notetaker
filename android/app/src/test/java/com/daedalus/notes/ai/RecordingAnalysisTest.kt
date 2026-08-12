package com.daedalus.notes.ai

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.daedalus.notes.data.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.viewmodel.RecordingViewModel
import com.daedalus.notes.ai.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.io.File

class RecordingAnalysisTest {

    private lateinit var application: Application
    private lateinit var llm: LocalLlmService
    private lateinit var embedder: EmbeddingService
    private lateinit var repo: RecordingRepository

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("custom_prompt", null) } returns null
        every { prefs.getInt(AI_TEXT_BUDGET_KEY, AI_TEXT_BUDGET_DEFAULT) } returns AI_TEXT_BUDGET_DEFAULT
        application = mockk(relaxed = true)
        every { application.getSharedPreferences(any(), any()) } returns prefs

        llm = mockk(relaxed = true)
        embedder = mockk(relaxed = true)
        repo = mockk(relaxed = true)

        every { embedder.isReady } returns false

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private val jsonResponse = """
        {"title": "Standup", "shortSummary": "Quick sync", "topics": ["standup", "sync"], "mindMap": "- point one", "fullSummary": "Discussed standup items."}
    """.trimIndent()

    // Real degraded output captured from a device: Gemma answered as a free-form bullet list of
    // quotes instead of following the JSON/markdown-field instruction. tryParseMarkdown requires
    // ^\s*-\s*(\w+)\s*:\s*(.*) and these lines open with a curly quote, so \w+ never matches, no
    // known key is captured, and SmartAnalysisParser.parse falls back to
    // SmartAnalysis(fullSummary = rawResponse) — blank title/shortSummary/topics/mindMap.
    private val degradedBulletFixture = """
        - “I need an offsite team building event”
        - “September”
        - “approximately” number – “how many people”
        - “Mid-September” – “better than late-September”
        - “Monday or Tuesday” – “This gives us some decent options”
        - “half day” – “This helps determine logistical feasibility”
        - “Relaxed and creative” – “more appealing”
        - “activity-based and focused on team building”
        - “Resort/Hotels” – “This can provide some space and have beautiful/cool views”
        - “Campgrounds” – “This can be very relaxed and budget friendly but requires more planning”
        - “Hotel Ballroom/Event Space” – “This can be more formal and can be customized easily”
    """.trimIndent()

    /** Runs the pipeline over [raw] and returns the (title, shortSummary) actually persisted. */
    private suspend fun capturePersistedTitleAndSummary(
        raw: String,
        transcript: String
    ): Pair<String, String> {
        coEvery { llm.generate(any(), any<String>()) } returns raw

        analyzeTranscript(application, llm, embedder, repo, "note.mp3", transcript)

        val titleSlot = slot<String>()
        val shortSummarySlot = slot<String>()
        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = any(),
                mindMap = any(),
                title = capture(titleSlot),
                shortSummary = capture(shortSummarySlot),
                topics = any()
            )
        }
        return titleSlot.captured to shortSummarySlot.captured
    }

    @Test
    fun updateSummary_degradedFreeFormResponse_derivesSensibleTitleAndSummary() = runTest {
        val (title, shortSummary) =
            capturePersistedTitleAndSummary(degradedBulletFixture, "offsite planning discussion")

        assertTrue("shortSummary should not be blank", shortSummary.isNotBlank())
        assertTrue("title should not be blank", title.isNotBlank())
        assertTrue("title should not start with '-'", !title.startsWith("-"))
        assertTrue(
            "title should not start with a quote character",
            !title.startsWith("\"") && !title.startsWith("'") &&
                !title.startsWith("“") && !title.startsWith("‘")
        )
        assertTrue("title should be within the length cap", title.length <= 60)
        assertTrue("title should be a single line", !title.contains("\n"))
    }

    @Test
    fun updateSummary_degradedResponseWithNoUsableText_stillHasTitleAndPreview() = runTest {
        // Degenerate sources that truncate away to nothing must not leave a blank preview, which
        // is the original bug: empty response + empty transcript, and a punctuation-only response.
        val (emptyTitle, emptySummary) = capturePersistedTitleAndSummary("", "")
        assertTrue("title should not be blank", emptyTitle.isNotBlank())
        assertTrue("shortSummary should not be blank", emptySummary.isNotBlank())

        repo = mockk(relaxed = true)
        val (punctTitle, punctSummary) = capturePersistedTitleAndSummary(".".repeat(300), "")
        assertTrue("title should not be blank", punctTitle.isNotBlank())
        assertTrue("shortSummary should not be blank", punctSummary.isNotBlank())
    }

    @Test
    fun updateSummary_degradedResponseOpeningWithABareMarker_titlesFromTheFirstRealLine() = runTest {
        // The first non-blank line is nothing but a bullet marker; the title must come from the
        // first line that still has content after cleaning, not from the marker line.
        val (title, _) = capturePersistedTitleAndSummary("-\n###\n- Offsite venue options", "tx")

        assertTrue("title should come from the first line with content, was '$title'",
            title.startsWith("Offsite venue options"))
    }

    @Test
    fun updateSummary_degradedResponseWithLongUnbrokenWord_isStillTruncated() = runTest {
        // No space to back off to, so word-boundary truncation must not yield an empty string.
        val (title, _) = capturePersistedTitleAndSummary("a".repeat(300), "tx")

        assertTrue("title should not be blank", title.isNotBlank())
        assertTrue("title should be within the length cap", title.length <= 60)
    }

    @Test
    fun updateSummary_degradedResponseWithEmoji_doesNotSplitSurrogatePair() = runTest {
        // 59 chars then emoji puts a surrogate pair across the 60-char title cap, and there is no
        // space to fall back to; a naive substring would leave a stray half-character.
        val (title, _) = capturePersistedTitleAndSummary("a".repeat(59) + "😀".repeat(10), "tx")

        assertTrue("title should not be blank", title.isNotBlank())
        assertTrue(
            "title must not end with an unpaired surrogate",
            !Character.isHighSurrogate(title.last())
        )
    }

    @Test
    fun updateSummary_degradedFreeFormResponse_topicsAndMindMapRemainEmpty() = runTest {
        coEvery { llm.generate(any(), any<String>()) } returns degradedBulletFixture

        analyzeTranscript(application, llm, embedder, repo, "note.mp3", "offsite planning")

        // Fabricating these would pollute the topic graph and the mind map with invented structure.
        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = any(),
                mindMap = "",
                title = any(),
                shortSummary = any(),
                topics = emptyList()
            )
        }
    }

    @Test
    fun updateSummary_wellFormedJson_isNotAlteredByFallback() = runTest {
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse

        analyzeTranscript(application, llm, embedder, repo, "note.mp3", "short transcript")

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = "Discussed standup items.",
                mindMap = "- point one",
                title = "Standup",
                shortSummary = "Quick sync",
                topics = listOf("standup", "sync")
            )
        }
    }

    @Test
    fun updateSummary_partiallyParsedResponse_isLeftUntouched() = runTest {
        // Has a title but blank shortSummary/mindMap/topics: NOT all four are blank, so the
        // degraded guard must not engage and must not overwrite the partially-parsed result.
        val partialJson = """
            {"title": "Partial Title", "shortSummary": "", "topics": [], "mindMap": "", "fullSummary": "Some full summary text."}
        """.trimIndent()
        coEvery { llm.generate(any(), any<String>()) } returns partialJson

        analyzeTranscript(application, llm, embedder, repo, "note.mp3", "short transcript")

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = "Some full summary text.",
                mindMap = "",
                title = "Partial Title",
                shortSummary = "",
                topics = emptyList()
            )
        }
    }

    @Test
    fun multiPartAnalysis_abortsMidway_doesNotDeleteExistingParts() = runTest {
        val transcriber = mockk<TranscriptionService>()
        val db = mockk<com.daedalus.notes.data.db.AppDatabase>(relaxed = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        
        try {
            // Parent recording > 15 mins
            val parent = Recording(
                filename = "long.mp3",
                localPath = "/fake/long.mp3",
                durationMillis = 20L * 60 * 1000 // 20 mins -> 2 parts
            )
            val fileMock = mockk<File>()
            every { fileMock.exists() } returns true
            every { fileMock.absolutePath } returns "/fake/long.mp3"
            
            every { repo.allRecordings } returns kotlinx.coroutines.flow.flowOf(emptyList())
            every { repo.parentsWithParts } returns kotlinx.coroutines.flow.flowOf(emptyList())
            coEvery { repo.get("long.mp3") } returns parent
            coEvery { repo.countOtherSharingPath(any(), any()) } returns 0
            
            // First part succeeds
            coEvery { transcriber.transcribeRange(any(), 0L, 15L * 60 * 1000) } returns "Part 1 transcript"
            // Second part fails with CancellationException
            coEvery { transcriber.transcribeRange(any(), 15L * 60 * 1000, 20L * 60 * 1000) } throws kotlinx.coroutines.CancellationException("Cancelled")
            
            val viewModel = RecordingViewModel(
                application = application,
                db = db,
                repo = repo,
                llm = llm,
                transcriber = transcriber,
                embedder = embedder,
                ioDispatcher = dispatcher,
                audioRecorderProvider = { mockk(relaxed = true) },
                timerDispatcher = dispatcher
            )
            coEvery { transcriber.transcribeRange(any(), 15L * 60 * 1000, 20L * 60 * 1000) } throws kotlinx.coroutines.CancellationException("Cancelled")
            
            try {
                viewModel.analyze("long.mp3")
                testScheduler.advanceUntilIdle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected
            }
            
            // Verify deletePartsOf was never called
            coVerify(exactly = 0) { repo.deletePartsOf("long.mp3") }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
