package com.daedalus.notes

import com.daedalus.notes.data.model.Mp3FrameScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- #100 follow-up H1: benign trailing padding must not be reported as a gap ----
    //
    // A device that zero-pads the unused tail of its last flash sector produces a short,
    // homogeneous run of filler bytes after the last real frame. Nothing decodes those bytes as
    // a frame, so an unresyncable-to-EOF check that flags *any* unresyncable tail as corruption
    // (the literal #100 fix) also flags this completely benign, expected trailer — a false
    // "corrupted" signal that can drive a user to NoteDetailScreen's "Re-fetch audio" button,
    // which deletes the local file first, against a device copy that is often already gone.

    @Test
    fun shortTrailingZeroPadding_afterCleanFrames_isNotReportedAsGap() {
        // 10 clean frames + 3 zero bytes: too short to be a frame, never resyncs before EOF.
        val data = stream(10) + ByteArray(3) { 0x00 }
        val result = Mp3FrameScan.scan(data)
        assertEquals(
            "a short homogeneous trailing run must not be reported as data loss",
            0,
            result.gapCount
        )
        assertEquals(10, result.framesOk)
    }

    @Test
    fun longTrailingZeroPadding_afterCleanFrames_isNotReportedAsGap() {
        // A larger homogeneous run (e.g. a whole unused flash page) must also be treated as
        // benign — it carries no audio information regardless of length.
        val data = stream(10) + ByteArray(4096) { 0x00 }
        val result = Mp3FrameScan.scan(data)
        assertEquals(0, result.gapCount)
        assertEquals(10, result.framesOk)
    }

    @Test
    fun trailingErasePadding_0xFF_isNotReportedAsGap() {
        // Erased flash is conventionally 0xFF, not 0x00 — the benign-trailer check must not be
        // zero-specific.
        val data = stream(10) + ByteArray(50) { 0xFF.toByte() }
        val result = Mp3FrameScan.scan(data)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun trailingApeV2Footer_isNotReportedAsGap() {
        val footer = "APETAGEX".toByteArray() + ByteArray(24) { 0x01 } // 32-byte APEv2 footer
        val data = stream(10) + footer
        val result = Mp3FrameScan.scan(data)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun trailingLyrics3v2Footer_isNotReportedAsGap() {
        val footer = ByteArray(6) { '0'.code.toByte() } + "LYRICS200".toByteArray()
        val data = stream(10) + footer
        val result = Mp3FrameScan.scan(data)
        assertEquals(0, result.gapCount)
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

    // ---- Real-file cross-check ---------------------------------------------------
    // Gated on -Dmp3.fixtures=<dir>. Skips cleanly (no failure) when absent, matching
    // exact numbers validated against ffmpeg ground truth on real device recordings.

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

    @Test
    fun realFileCrossCheck() {
        val dirProp = System.getProperty("mp3.fixtures")
        if (dirProp.isNullOrBlank()) return
        val dir = File(dirProp)
        if (!dir.isDirectory) return

        for (expected in expectedResults) {
            val file = File(dir, expected.fileName)
            if (!file.exists()) continue
            assertEquals("${expected.fileName} size", expected.size, file.length())
            val result = Mp3FrameScan.scan(file)
            assertEquals("${expected.fileName} gapCount", expected.gapCount, result.gapCount)
            assertEquals("${expected.fileName} gapBytes", expected.gapBytes, result.gapBytes)
        }
    }
}
