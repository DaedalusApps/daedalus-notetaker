package com.daedalus.notes.data.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioRepairEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        // MPEG1 Layer III, 128kbps, 44.1kHz, no padding -> 417-byte frames (original test format).
        private val MPEG1_HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        private const val MPEG1_FRAME_LEN = 417

        private fun mpeg1Frame(fill: Byte = 0x55): ByteArray =
            ByteArray(MPEG1_FRAME_LEN) { fill }.also { System.arraycopy(MPEG1_HEADER, 0, it, 0, 4) }

        // MPEG2 Layer III, 32kbps, 16kHz, no padding -> the FW920's actual on-device format.
        // frameLen = 576/8 * 32000 / 16000 + padding = 72 * 2 + 0 = 144 bytes.
        private val MPEG2_HEADER = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x48.toByte(), 0x00)
        private const val MPEG2_FRAME_LEN = 144

        private fun mpeg2Frame(fill: Byte): ByteArray =
            ByteArray(MPEG2_FRAME_LEN) { fill }.also { System.arraycopy(MPEG2_HEADER, 0, it, 0, 4) }

        // fill varies per stream so two streams from different calls are never byte-identical
        // (a same-content "before"/"after" pair would make substring-presence checks meaningless).
        private fun mpeg2Stream(frameCount: Int, fill: Byte = 0x11): ByteArray {
            val out = ByteArray(frameCount * MPEG2_FRAME_LEN)
            for (i in 0 until frameCount) {
                mpeg2Frame(fill).copyInto(out, i * MPEG2_FRAME_LEN)
            }
            return out
        }
    }

    // ---- Legacy MPEG1 case: kept, now asserting on bytes instead of a boolean ----

    @Test
    fun repairMp3File_mpeg1_cleanFile_isByteIdentical() {
        val testFile = tempFolder.newFile("test_recording.mp3")
        val frame1 = mpeg1Frame()
        val frame2 = mpeg1Frame()
        val fullData = frame1 + frame2
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)

        assertTrue(
            "clean chained stream should be reported Clean, got $result",
            result is AudioRepairEngine.RepairResult.Clean
        )
        assertArrayEquals("clean file bytes must be untouched", fullData, testFile.readBytes())
    }

    @Test
    fun repairMp3File_mpeg2_cleanStream_isByteIdentical() {
        val testFile = tempFolder.newFile("clean_mpeg2.mp3")
        val fullData = mpeg2Stream(20)
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)

        assertTrue(
            "clean MPEG2 stream should be reported Clean, got $result",
            result is AudioRepairEngine.RepairResult.Clean
        )
        assertArrayEquals("clean file bytes must be untouched, not even a single byte", fullData, testFile.readBytes())
    }

    // ---- CATASTROPHIC BUG: interior gap must not discard audio after it ----

    @Test
    fun repairMp3File_mpeg2_interiorGap_audioAfterGapSurvives() {
        val testFile = tempFolder.newFile("interior_gap.mp3")

        // 10 clean frames, a corrupted span in the middle, then 10 MORE clean frames. Distinct
        // fill bytes so "before" and "after" are never byte-identical to each other.
        val before = mpeg2Stream(10, fill = 0x11)
        val junk = ByteArray(50) { 0x00 } // never 0xFF, no accidental resync
        val after = mpeg2Stream(10, fill = 0x22)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)

        // Mp3FrameScan's chain validation confirms a frame only once its *successor* also
        // parses (see Mp3FrameScan docstring / Mp3FrameScanTest's "within one frame length"
        // tolerance) — so the single frame immediately preceding an unconfirmable successor is
        // folded into the gap along with the 50 junk bytes. This is a pre-existing, documented
        // characteristic of the scanner (not something this fix changes), so the expected
        // excised span is deterministic: 50 junk bytes + 1 frame length.
        val expectedRemoved = 50 + MPEG2_FRAME_LEN
        assertEquals(
            "expected the junk span (plus the one unconfirmable boundary frame) to be excised, got $result",
            AudioRepairEngine.RepairResult.Repaired(expectedRemoved),
            result
        )
        val resultBytes = testFile.readBytes()

        // The critical assertion: the 10 frames of audio AFTER the gap must still be present.
        // The buggy implementation truncates to the first gap and this trailing audio is lost.
        val expectedBytes = before.copyOfRange(0, before.size - MPEG2_FRAME_LEN) + after
        assertArrayEquals(
            "repaired file must retain all audio frames after the gap, with only the corrupt " +
                "span (plus its one unconfirmable boundary frame) excised",
            expectedBytes,
            resultBytes
        )
    }

    // ---- CATASTROPHIC BUG: leading gap with no ID3 tag must not zero the file ----

    @Test
    fun repairMp3File_mpeg2_leadingGapNoId3_fileNotReducedToZero() {
        val testFile = tempFolder.newFile("leading_gap.mp3")

        val junk = ByteArray(30) { 0x00 } // no ID3 tag at all
        val audio = mpeg2Stream(10)
        val fullData = junk + audio
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)

        assertEquals(
            "expected exactly the 30 leading junk bytes to be excised, got $result",
            AudioRepairEngine.RepairResult.Repaired(30),
            result
        )
        val resultBytes = testFile.readBytes()

        assertTrue("repaired file must not be empty", resultBytes.isNotEmpty())
        assertArrayEquals(
            "repaired file should be exactly the audio stream with leading junk excised",
            audio,
            resultBytes
        )
    }

    // ---- CRITICAL: backup must exist before the original is overwritten ----

    @Test
    fun repairMp3File_repair_leavesRecoverableBackupOfOriginal() {
        val testFile = tempFolder.newFile("needs_backup.mp3")
        val before = mpeg2Stream(5, fill = 0x11)
        val junk = ByteArray(20) { 0x00 }
        val after = mpeg2Stream(5, fill = 0x22)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)
        assertTrue("expected a Repaired result, got $result", result is AudioRepairEngine.RepairResult.Repaired)

        val backupFile = AudioRepairEngine.backupFileFor(testFile)
        assertTrue("expected a backup file preserving the pre-repair bytes", backupFile.exists())
        assertArrayEquals(
            "backup must contain the exact original bytes, including the audio the repair excised",
            fullData,
            backupFile.readBytes()
        )
    }

    // ---- #100 follow-up C1: repair backup must never collide with the re-download backup ----
    //
    // RecordingViewModel.redownloadAndAnalyze uses File(parent, name + ".bak") — the identical
    // sibling path — as its pre-re-download backup (RecordingViewModel.kt:700), with
    // overwrite=true and no existence check on both sides. If AudioRepairEngine ever wrote to
    // that same path, whichever backup landed second would silently destroy the other's only
    // recovery copy. This test fails if the two mechanisms can ever resolve to the same path,
    // for any filename — not just the one this test happens to construct.

    @Test
    fun repairBackupPath_neverCollidesWithRedownloadBackupPath() {
        val names = listOf(
            "needs_backup.mp3", "20260812102746.mp3", "a.mp3.bak.mp3", "weird name with spaces.mp3",
        )
        for (name in names) {
            val original = File(tempFolder.newFolder(), name)
            // This mirrors RecordingViewModel.kt:700's exact construction:
            // `src.copyTo(File(src.parentFile, src.name + ".bak"), overwrite = true)`.
            val redownloadBackupPath = File(original.parentFile, original.name + ".bak")
            val repairBackupPath = AudioRepairEngine.backupFileFor(original)
            assertTrue(
                "AudioRepairEngine's backup path for '$name' collides with " +
                    "RecordingViewModel's re-download backup path ($redownloadBackupPath)",
                repairBackupPath.canonicalPath != redownloadBackupPath.canonicalPath
            )
        }
    }

    @Test
    fun repair_doesNotWriteToTheRedownloadBackupPath() {
        val testFile = tempFolder.newFile("needs_backup2.mp3")
        val before = mpeg2Stream(5, fill = 0x11)
        val junk = ByteArray(20) { 0x00 }
        val after = mpeg2Stream(5, fill = 0x22)
        testFile.writeBytes(before + junk + after)

        AudioRepairEngine.repairMp3File(testFile)

        val redownloadBackupPath = File(testFile.parentFile, "${testFile.name}.bak")
        assertTrue(
            "AudioRepairEngine must never write to the path RecordingViewModel uses for its " +
                "re-download backup (RecordingViewModel.kt:700) — see #100 follow-up C1",
            !redownloadBackupPath.exists()
        )
    }

    // ---- HIGH: unresyncable trailing corruption must be detected, not reported clean ----

    @Test
    fun repairMp3File_mpeg2_unresyncableTrailingCorruption_isDetectedNotClean() {
        val testFile = tempFolder.newFile("trailing_corrupt.mp3")

        val clean = mpeg2Stream(10)
        // Real damage: varied, non-repeating bytes that never resync before EOF and don't match
        // any recognized trailer format. A homogeneous run here would be (correctly) treated as
        // benign padding — see repairMp3File_mpeg2_benignTrailingZeroPadding_isNotTouched below
        // and the #100 follow-up H1 note in Mp3FrameScanTest.
        val trailingJunk = byteArrayOf(
            0x12, 0x34, 0x56, 0x78,
            0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
        ) + ByteArray(52) { (it * 7 + 1).toByte() }
        val fullData = clean + trailingJunk
        testFile.writeBytes(fullData)

        val scan = Mp3FrameScan.scan(fullData)
        assertTrue(
            "unresyncable trailing corruption must be counted as a gap by the scanner, was reported clean (gapCount=0)",
            scan.gapCount > 0
        )

        val result = AudioRepairEngine.repairMp3File(testFile)
        assertTrue(
            "engine must not report Clean when trailing bytes are corrupt and unresyncable, got $result",
            result !is AudioRepairEngine.RepairResult.Clean
        )
        // The 60 bytes of trailing junk can never be decoded by any player; excising them is
        // safe (never discards audio). As with the interior-gap case, the scanner's chain
        // validation folds the one frame immediately preceding the unresyncable span into the
        // gap too (see comment in repairMp3File_mpeg2_interiorGap_audioAfterGapSurvives) — a
        // pre-existing, documented scanner characteristic, not introduced by this fix.
        assertEquals(AudioRepairEngine.RepairResult.Repaired(60 + MPEG2_FRAME_LEN), result)
        assertArrayEquals(clean.copyOfRange(0, clean.size - MPEG2_FRAME_LEN), testFile.readBytes())
    }

    // ---- #100 follow-up H1: benign trailing padding must not trigger repair/false "corrupted" ----

    @Test
    fun repairMp3File_mpeg2_benignTrailingZeroPadding_isNotTouched() {
        val testFile = tempFolder.newFile("trailing_padding.mp3")

        // Simulates an unused flash-sector tail: homogeneous filler after clean audio. This must
        // never be reported as damage, and the engine must never rewrite the file over it.
        val fullData = mpeg2Stream(10) + ByteArray(37) { 0x00 }
        testFile.writeBytes(fullData)

        val result = AudioRepairEngine.repairMp3File(testFile)

        assertTrue(
            "benign homogeneous trailing padding must be reported Clean, not Repaired, got $result",
            result is AudioRepairEngine.RepairResult.Clean
        )
        assertArrayEquals(
            "the engine must not rewrite a file whose only 'defect' is benign trailing padding",
            fullData,
            testFile.readBytes()
        )
    }

    // ---- Boolean-return regression: renameTo/move failure must not be reported as success ----
    //
    // #100 follow-up H2: this used to force the failure via testFile.setWritable(false), which
    // relies on File.renameTo/Files.move refusing to overwrite a read-only *target file*. That's
    // Windows/NTFS-specific behavior — POSIX rename(2) only cares about the containing
    // directory's write permission, not the target file's mode — so on CI's ubuntu-latest
    // runner the move quietly succeeded, both assertions below failed, and this test provided
    // no coverage at all for the platform the app actually ships on. The final atomic-replace
    // step is now injectable so the failure is forced deterministically on any platform.

    @Test
    fun repairMp3File_whenOverwriteFails_reportsFailureNotSuccess() {
        val testFile = tempFolder.newFile("locked_target.mp3")
        val before = mpeg2Stream(5, fill = 0x11)
        val junk = ByteArray(20) { 0x00 }
        val after = mpeg2Stream(5, fill = 0x22)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        var atomicReplaceInvoked = false
        val result = AudioRepairEngine.repairMp3File(testFile) { _, _ ->
            atomicReplaceInvoked = true
            throw java.io.IOException("simulated overwrite failure")
        }

        assertTrue(
            "the injected failure must actually be exercised, not skipped for another reason",
            atomicReplaceInvoked
        )
        assertTrue(
            "overwrite failure must surface as Failed, not silently report success, got $result",
            result is AudioRepairEngine.RepairResult.Failed
        )
        // And the original bytes must still be intact — a failed repair must not corrupt
        // or partially write over the only copy of the recording.
        assertArrayEquals(
            "original file must be untouched when the overwrite step fails",
            fullData,
            testFile.readBytes()
        )
    }
}
