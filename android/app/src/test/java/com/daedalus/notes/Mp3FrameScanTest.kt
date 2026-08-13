package com.daedalus.notes

import com.daedalus.notes.data.model.Mp3FrameScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

class Mp3FrameScanTest {

    companion object {
        // Synthetic frame header for the real observed device config:
        // MPEG2, LayerIII, 32 kbps, 16 kHz, no padding -> 144-byte frames.
        private val HEADER = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x48.toByte(), 0x00)
        private const val FRAME_LEN = 144
        private const val FILLER: Byte = 0x00 // never 0xFF, so no accidental sync bytes

        private fun frame(): ByteArray {
            val body = ByteArray(FRAME_LEN - HEADER.size) { FILLER }
            return HEADER + body
        }

        private fun stream(frameCount: Int): ByteArray {
            val out = ByteArray(frameCount * FRAME_LEN)
            for (i in 0 until frameCount) {
                frame().copyInto(out, i * FRAME_LEN)
            }
            return out
        }

        // ---- #106 structural guard helpers -----------------------------------------------
        // Comments stripped so a comment claiming to do the right thing can't satisfy a
        // `.contains(...)` check that was meant to verify real code. See HIGH-1 in the #99 review.
        private fun stripComments(source: String): String {
            val noBlockComments = source.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            return noBlockComments.lineSequence().joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
        }

        /** Returns the text between a `{` at [openBraceIndex] and its matching `}`.
         *  NOTE: brace-counts only, no awareness of string literals — an unpaired `{`/`}` inside
         *  a string literal in the scanned method body would corrupt this extraction. */
        private fun balancedBraceBlock(source: String, openBraceIndex: Int): String {
            var depth = 0
            for (i in openBraceIndex until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(openBraceIndex, i + 1)
                    }
                }
            }
            error("Unbalanced braces starting at index $openBraceIndex")
        }

        /** Walks up from the test JVM's working directory to find the `:app` module root
         *  (the directory containing this very test file). */
        private fun findModuleRoot(): File {
            var dir: File? = File(".").canonicalFile
            repeat(6) {
                val candidate = dir?.let { File(it, "src/test/java/com/daedalus/notes/Mp3FrameScanTest.kt") }
                if (candidate != null && candidate.exists()) return dir!!
                dir = dir?.parentFile
            }
            error(
                "Could not locate the :app module root (looked for " +
                    "src/test/java/com/daedalus/notes/Mp3FrameScanTest.kt) from ${File(".").canonicalPath}"
            )
        }

        private fun extractRealFileCrossCheckBody(): String {
            val file = File(findModuleRoot(), "src/test/java/com/daedalus/notes/Mp3FrameScanTest.kt")
            val source = stripComments(file.readText())
            // Built via concatenation, not as one contiguous literal, so this marker doesn't
            // match itself when this very file's source text is scanned below.
            val marker = "fun " + "realFileCrossCheck() {"
            val start = source.indexOf(marker)
            require(start >= 0) { "Could not find the realFileCrossCheck() declaration in Mp3FrameScanTest.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }
    }

    @Test
    fun syntheticHeader_parsesToExpectedConfig() {
        // Sanity-check our hand-derived header bytes against the scanner itself:
        // a single clean frame must be accepted with framesOk == 1, 0 gaps.
        val result = Mp3FrameScan.scan(frame())
        assertEquals(1, result.framesOk)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun cleanStream_noGaps() {
        val data = stream(50)
        val result = Mp3FrameScan.scan(data)
        assertEquals(50, result.framesOk)
        assertEquals(0, result.gapCount)
        assertEquals(0L, result.gapBytes)
        assertNull(result.firstGapOffset)
    }

    @Test
    fun oneLostBleNotification_isDetected() {
        val data = stream(50)
        val mid = 25 * FRAME_LEN
        val deleted = data.copyOfRange(0, mid) + data.copyOfRange(mid + 244, data.size)
        val result = Mp3FrameScan.scan(deleted)
        assertTrue("expected at least one gap", result.gapCount >= 1)
        assertTrue("expected gapBytes > 0", result.gapBytes > 0)
        val offset = result.firstGapOffset
        assertTrue("expected a firstGapOffset", offset != null)
        assertTrue(
            "firstGapOffset ($offset) should be within one frame length of the deletion point ($mid)",
            offset!! in (mid - FRAME_LEN).toLong()..(mid + FRAME_LEN).toLong()
        )
    }

    @Test
    fun truncatedTail_isNotReportedAsLoss() {
        val data = stream(50)
        val chopped = data.copyOfRange(0, data.size - FRAME_LEN / 2)
        val result = Mp3FrameScan.scan(chopped)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun leadingLoss_reportedAtOffsetZero() {
        val junk = ByteArray(100) { FILLER }
        val data = junk + stream(50)
        val result = Mp3FrameScan.scan(data)
        assertTrue("expected at least one gap", result.gapCount >= 1)
        assertEquals(0L, result.firstGapOffset)
    }

    @Test
    fun emptyArray_noExceptionNoGaps() {
        val result = Mp3FrameScan.scan(ByteArray(0))
        assertEquals(0, result.framesOk)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun threeByteArray_noExceptionNoGaps() {
        val result = Mp3FrameScan.scan(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(0, result.framesOk)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun id3v2Tag_isSkipped() {
        // 10-byte ID3v2 header declaring a 20-byte payload (no footer flag), then junk
        // payload bytes, then clean frames.
        val payloadSize = 20
        val id3 = ByteArray(10)
        id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
        id3[3] = 0x03; id3[4] = 0x00 // version
        id3[5] = 0x00 // flags, no footer
        // syncsafe size, 7 bits per byte
        id3[6] = ((payloadSize shr 21) and 0x7F).toByte()
        id3[7] = ((payloadSize shr 14) and 0x7F).toByte()
        id3[8] = ((payloadSize shr 7) and 0x7F).toByte()
        id3[9] = (payloadSize and 0x7F).toByte()
        val payload = ByteArray(payloadSize) { 0x2A }
        val data = id3 + payload + stream(50)

        val result = Mp3FrameScan.scan(data)
        assertEquals(50, result.framesOk)
        assertEquals(0, result.gapCount)
    }

    /**
     * KNOWN LIMITATION: a loss of exactly a whole number of frame lengths leaves the
     * following frames byte-aligned with what the scanner expects, so the chain
     * validation cannot distinguish it from a clean stream. This test documents that
     * blind spot, it is not asserting correct loss detection.
     */
    @Test
    fun wholeFrameLossIsInvisible_knownLimitation() {
        val data = stream(50)
        val mid = 25 * FRAME_LEN
        val deleted = data.copyOfRange(0, mid) + data.copyOfRange(mid + FRAME_LEN, data.size)
        val result = Mp3FrameScan.scan(deleted)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun scanFile_missingFile_returnsZeroResultNoException() {
        val result = Mp3FrameScan.scan(File("does-not-exist-${System.nanoTime()}.mp3"))
        assertEquals(0, result.framesOk)
        assertEquals(0, result.gapCount)
        assertEquals(0L, result.gapBytes)
        assertNull(result.firstGapOffset)
    }

    @Test
    fun trailingId3v1Tag_notReportedAsGap() {
        val clean = stream(50)
        val tag = ByteArray(128) { 0 }
        tag[0] = 'T'.code.toByte()
        tag[1] = 'A'.code.toByte()
        tag[2] = 'G'.code.toByte()
        val data = clean + tag
        val result = Mp3FrameScan.scan(data)
        // All 50 frames are valid audio preceding the tag. Previously this asserted 49: the
        // scanner used to under-count the very last frame before a trailing tag because its
        // chain-confirmation lookahead ran into the tag bytes instead of true EOF. The trailing
        // ID3v1 tag is now excluded from the scanned region (mirrors the leading ID3v2 handling
        // above), so the last real frame is correctly confirmed instead of penalized.
        assertEquals(50, result.framesOk)
        assertEquals(0, result.gapCount)
    }

    // ---- #100 follow-up round 3, HIGH-1: unbounded homogeneous-run carve-out is a data-loss
    // ---- blind spot, not a fix — it must NOT exist. (Round 2 added it; this reverts that.)
    //
    // MP3 audio, including digital silence, is never a constant byte run. A classifier that
    // treats any same-value trailing span as "benign" cannot be protecting real audio from a
    // false positive — it can only be hiding the ABSENCE of audio. NoteDetailScreen.kt gates its
    // corruption banner on gapCount > 0 && gapBytes > 0: two valid frames (72ms) followed by
    // megabytes of zero-fill must show the banner, not report clean, even though the device copy
    // may still be recoverable via a re-fetch the user is never offered because nothing looked
    // wrong. Round 2's own empirical finding stands unchanged: across all 19 real device-file
    // copies, this carve-out never affected the result — the false positive it targeted was
    // theoretical on real data; the false negative it introduces is not.

    @Test
    fun massiveTrailingZeroRun_afterFewFrames_IS_reportedAsGap() {
        // 2 valid frames (288 bytes / 72ms) followed by 100KB of zero-fill: almost the entire
        // recording is missing. This must be reported as damage.
        val data = stream(2) + ByteArray(100_000) { 0x00 }
        val result = Mp3FrameScan.scan(data)
        assertTrue(
            "a huge trailing zero-run after only a couple of real frames hides near-total audio " +
                "loss and MUST be reported as a gap, not treated as benign padding",
            result.gapCount > 0
        )
        assertTrue("gapBytes must be nonzero so NoteDetailScreen's banner gate fires", result.gapBytes > 0)
    }

    @Test
    fun trailingErasePadding_0xFF_isAlsoReportedAsGap() {
        // 0xFF (conventional erased-flash fill) must not get special treatment either — same
        // reasoning as the zero-fill case above.
        val data = stream(2) + ByteArray(50_000) { 0xFF.toByte() }
        val result = Mp3FrameScan.scan(data)
        assertTrue(result.gapCount > 0)
    }

    // ---- #100 follow-up round 4, HIGH: total loss (zero decodable frames) must not read clean ----
    //
    // massiveTrailingZeroRun_afterFewFrames_IS_reportedAsGap above only proves the >=1-surviving-
    // frame case (it uses stream(2)). Removing the homogeneous-run carve-out closed the
    // partial-loss blind spot but left total loss untouched: when NO frame chains anywhere,
    // scan() took an entirely separate early-return path that still reports Mp3ScanResult(0, 0,
    // 0L, ...) — perfectly clean — for a file that is completely worthless. That is the one case
    // where the FW920 copy is most likely still recoverable and the banner is most needed.

    @Test
    fun totalLoss_noFrameAnywhere_isReportedAsGapNotClean() {
        // Pure junk with no 0xFF byte anywhere: zero frames found, same as a 920KB transfer of
        // all-zero bytes or a shifted/garbled stream that never happens to sync.
        val data = ByteArray(920_000) { 0x00 }
        val result = Mp3FrameScan.scan(data)
        assertEquals("nothing decoded, so framesOk must be 0", 0, result.framesOk)
        assertTrue(
            "total loss must be reported as a gap so NoteDetailScreen's banner (gapCount > 0 && " +
                "gapBytes > 0) actually fires and offers a re-fetch",
            result.gapCount > 0
        )
        assertTrue("gapBytes must be nonzero too — the banner gate requires both", result.gapBytes > 0)
    }

    // ---- #100 follow-up round 4, MEDIUM: a trailing gap must not overcharge its boundary frame ----
    //
    // The frame immediately before a genuinely unresyncable trailing span can be real,
    // independently-decodable audio whose lookahead confirmation failed only because nothing
    // valid follows it — the benign-trailer branch already excludes it from the loss count
    // (framesOk++ at :256/:279), but the real-corruption branch two lines below charged it as
    // lost bytes anyway, inflating the reported loss by a whole frame length on every trailing
    // gap (~50x on a 10-frame + 3-byte fixture: 147 bytes reported lost instead of 3).

    @Test
    fun trailingGapAfterFewFrames_doesNotOverchargeTheBoundaryFrameAsLoss() {
        val junk = byteArrayOf(0x12, 0x34, 0x56) // heterogeneous, no 0xFF, never resyncs
        val data = stream(10) + junk
        val result = Mp3FrameScan.scan(data)
        assertEquals("the 10th frame is real, decodable audio, not part of the gap", 10, result.framesOk)
        assertEquals(
            "only the 3 genuinely unresyncable junk bytes should be charged as loss, not the " +
                "144-byte real frame in front of them",
            3L,
            result.gapBytes
        )
    }

    // ---- #100 follow-up round 3, HIGH-2: tag recognition must be bounded to the tag itself ----
    //
    // An APEv2 footer's own size field must be used to verify the unresyncable span is EXACTLY
    // the tag and nothing else. Matching only the last 32 bytes' "APETAGEX" preamble — ignoring
    // whatever precedes it — lets real corruption hide behind a legitimate-looking footer.

    private fun apeV2Footer(tagSize: Int): ByteArray {
        // 32-byte APEv2 footer: "APETAGEX"(8) + version LE(4) + size LE(4) + item count LE(4) +
        // flags LE(4) + reserved(8). [tagSize] is the on-disk size the footer itself claims,
        // including the footer's own 32 bytes (the common footer-only-tag convention).
        val footer = ByteArray(32)
        "APETAGEX".toByteArray().copyInto(footer, 0)
        // version = 2000
        footer[8] = 0xD0.toByte(); footer[9] = 0x07
        // size field, little-endian
        footer[12] = (tagSize and 0xFF).toByte()
        footer[13] = ((tagSize shr 8) and 0xFF).toByte()
        footer[14] = ((tagSize shr 16) and 0xFF).toByte()
        footer[15] = ((tagSize shr 24) and 0xFF).toByte()
        return footer
    }

    @Test
    fun trailingApeV2Footer_withCorrectSizeField_isNotReportedAsGap() {
        // A well-formed, footer-only (no items) APEv2 tag: size field == 32, and the tag really
        // is exactly those 32 bytes — nothing precedes it but real audio.
        val data = stream(10) + apeV2Footer(tagSize = 32)
        val result = Mp3FrameScan.scan(data)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun trailingApeV2Footer_precededByGenuineCorruption_isStillReportedAsGap() {
        // The HIGH-2 counterexample: real damage immediately before a real, well-formed footer.
        // The footer's own size field (32 — footer-only, no items) proves the tag is only the
        // last 32 bytes, so the corruption before it must still be reported.
        val corruption = ByteArray(5000) { (it * 31 + 7).toByte() }.also {
            // Guarantee no accidental 0xFF sync byte anywhere in the corruption span.
            for (i in it.indices) if (it[i] == 0xFF.toByte()) it[i] = 0x01
        }
        val data = stream(10) + corruption + apeV2Footer(tagSize = 32)
        val result = Mp3FrameScan.scan(data)
        assertTrue(
            "corruption preceding a legitimate APEv2 footer must still be reported — the tag's " +
                "own size field proves the tag is only the last 32 bytes",
            result.gapCount > 0
        )
        assertTrue(result.gapBytes >= 5000)
    }

    @Test
    fun trailingApeV2Footer_withSizeFieldNotMatchingSpan_isReportedAsGap() {
        // A footer whose size field claims a tag larger than what's actually between it and the
        // last confirmed frame (or a bogus/implausible value) must not be trusted as benign.
        val data = stream(10) + apeV2Footer(tagSize = 1_000_000)
        val result = Mp3FrameScan.scan(data)
        assertTrue(result.gapCount > 0)
    }

    @Test
    fun heterogeneousUnresyncableTrailingCorruption_isStillReportedAsGap() {
        // Real damage — varied, non-repeating bytes that never resync — must still be caught.
        // This is the actual #100 defect the H1 fix targets; the benign-trailer carve-out must
        // not swallow genuine corruption along with the false positives.
        val junk = byteArrayOf(
            0x12, 0x34, 0x56, 0x78,
            0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
        )
        val data = stream(10) + junk
        val result = Mp3FrameScan.scan(data)
        assertTrue(
            "heterogeneous unresyncable trailing bytes are real damage and must be reported",
            result.gapCount > 0
        )
    }

    // ---- Known coverage gap (#106) -------------------------------------------------------
    // The trailing-span branches exercised only above by synthetic fixtures — recordGap on an
    // unresyncable EOF, isBenignTrailer, and isBoundedApeV2Footer — are NOT exercised by any file
    // in the real device corpus below. realFileCrossCheck's six recordings never take those paths.
    // This is a known, deliberate non-exercise gap, not an oversight: a fixture authored from the
    // same mental model as the parser can agree with it and both be wrong (this project already
    // lost a cadence-based loss detector that passed 10 red-first tests and two reviews while
    // being completely wrong about the hardware). Closing this gap needs genuinely damaged real
    // captures (e.g. a deliberately interrupted BLE transfer), not more synthetic fixtures.
    // Tracked in #106.

    // ---- Real-file cross-check ---------------------------------------------------
    // Gated on -Dmp3.fixtures=<dir>. Reports SKIPPED (via Assume.assumeTrue, never a bare
    // `return`) when the property is absent or points at a bad path, so an unrun cross-check can
    // never be mistaken for a passing one. When a fixture directory IS supplied, every file in
    // expectedResults must be present — a missing file is a hard failure, not a silent skip —
    // and the numbers are matched exactly against ffmpeg ground truth on real device recordings.

    private data class Expected(
        val fileName: String,
        val size: Long,
        val gapCount: Int,
        val gapBytes: Long,
    )

    private val expectedResults = listOf(
        Expected("20260719173605.mp3", 589004, gapCount = 0, gapBytes = 0),
        Expected("20260806130549.mp3", 62680, gapCount = 0, gapBytes = 0),
        Expected("20260806130923.mp3", 52164, gapCount = 0, gapBytes = 0),
        Expected("20260807173505.mp3", 335414, gapCount = 0, gapBytes = 0),
        Expected("20260713064926.mp3", 476314, gapCount = 2, gapBytes = 496),
        Expected("20260804141258.mp3", 920440, gapCount = 221, gapBytes = 48476),
    )

    // ---- #106 follow-up, MEDIUM-1: expectedResults itself must not be silently emptied ----
    // expectedResults IS this test's entire definition of coverage: if a future edit ever empties
    // or truncates it (bad rebase, merge-conflict resolution, entries commented out), then in
    // realFileCrossCheck missingFiles becomes empty, the for-loop runs zero times, and
    // checked == expectedResults.size trivially holds at 0 == 0 — a genuine JUnit PASS having
    // validated nothing, reproducing the #106 defect through a different vector.
    //
    // This is a plain @Test, not part of realFileCrossCheck, so it runs unconditionally —
    // including in CI, where the cross-check itself is always skipped (no mp3.fixtures property).
    // That means CI guards the coverage definition even though CI never runs the cross-check.
    // The count is pinned exactly (not just "non-empty") so a legitimate-looking *shrink* is
    // caught too, not only a full wipe. When fixtures are deliberately added or removed, update
    // this constant deliberately alongside expectedResults.
    @Test
    fun `expectedResults size is pinned so it cannot be silently emptied or truncated`() {
        val pinnedFixtureCount = 6
        assertEquals(
            "expectedResults defines the entire coverage of realFileCrossCheck: if this count " +
                "shrinks unexpectedly, some real device recording silently stopped being " +
                "validated (see #106). If fixtures were legitimately added or removed, update " +
                "this pinned count deliberately.",
            pinnedFixtureCount,
            expectedResults.size
        )
    }

    @Test
    fun realFileCrossCheck() {
        val dirProp = System.getProperty("mp3.fixtures")
        assumeTrue("mp3.fixtures system property not set; skipping real-file cross-check", !dirProp.isNullOrBlank())
        val dir = File(dirProp!!)
        assumeTrue("mp3.fixtures ($dirProp) is not a directory; skipping real-file cross-check", dir.isDirectory)

        val missingFiles = expectedResults.filterNot { File(dir, it.fileName).exists() }
        assertTrue(
            "mp3.fixtures directory ($dirProp) is missing expected fixture file(s): " +
                "${missingFiles.map { it.fileName }} — every file in expectedResults must be " +
                "present so this test actually validates all of them, not just whichever happen " +
                "to exist",
            missingFiles.isEmpty()
        )

        var checked = 0
        for (expected in expectedResults) {
            val file = File(dir, expected.fileName)
            assertEquals("${expected.fileName} size", expected.size, file.length())
            val result = Mp3FrameScan.scan(file)
            assertEquals("${expected.fileName} gapCount", expected.gapCount, result.gapCount)
            assertEquals("${expected.fileName} gapBytes", expected.gapBytes, result.gapBytes)
            checked++
        }
        assertEquals(
            "did not check every expected fixture — coverage is incomplete",
            expectedResults.size,
            checked
        )
    }

    // ---- #106 structural guard: realFileCrossCheck must skip, never silently pass -----------
    // A bare `return` inside a @Test method makes JUnit report it PASSED, indistinguishable from
    // a real, fully-validated run. This guard reads this file's own (comment-stripped) source and
    // asserts realFileCrossCheck's body contains no bare `return` — it must use
    // org.junit.Assume.assumeTrue(...) to skip instead, which JUnit reports as SKIPPED.

    @Test
    fun `realFileCrossCheck body has no bare early-return gating`() {
        val body = extractRealFileCrossCheckBody()
        assertTrue(
            "realFileCrossCheck must not contain a bare 'return' — that makes JUnit report a " +
                "silently-skipped run as PASSED. Use org.junit.Assume.assumeTrue(...) instead, " +
                "which JUnit reports as SKIPPED.",
            // (?!@) excludes labeled returns like `return@forEach` / `return@label`: those exit
            // only the enclosing lambda, not the test method, so they don't cause the silent-pass
            // this guard exists to prevent.
            !Regex("""\breturn\b(?!@)""").containsMatchIn(body)
        )
        assertTrue(
            "realFileCrossCheck must use org.junit.Assume.assumeTrue(...) to skip when fixtures " +
                "are unavailable.",
            body.contains("assumeTrue")
        )
    }

}
