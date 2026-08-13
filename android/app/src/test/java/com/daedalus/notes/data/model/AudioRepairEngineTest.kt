package com.daedalus.notes.data.model

import org.junit.Assert.assertArrayEquals
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

        private fun mpeg2Frame(fill: Byte = 0x11): ByteArray =
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

    @Test
    fun repairMp3File_mpeg1_cleanFile_isByteIdentical() {
        val testFile = tempFolder.newFile("test_recording.mp3")
        val frame1 = mpeg1Frame()
        val frame2 = mpeg1Frame()
        val fullData = frame1 + frame2
        testFile.writeBytes(fullData)

        val repaired = AudioRepairEngine.repairMp3File(testFile)

        assertTrue("clean chained stream should report success", repaired)
        assertArrayEquals("clean file bytes must be untouched", fullData, testFile.readBytes())
    }

    // ---- CATASTROPHIC BUG: interior gap must not discard audio after it ----
    //
    // NOTE ON TEST STRATEGY: this JVM's java.io.File.renameTo() silently fails to overwrite
    // an *existing* destination on Windows/NTFS (a well-known java.io.File cross-platform
    // wart — Windows MoveFileEx here is not called with MOVEFILE_REPLACE_EXISTING). That
    // means on this host the buggy engine's final `tempFile.renameTo(inputFile)` step never
    // actually lands, and the original file is left untouched no matter what cleanBytes
    // contained — masking the truncation bug if we only inspect the final file. The engine
    // unconditionally writes its computed "clean" bytes to `<name>.tmp` BEFORE attempting
    // that rename, so we inspect the .tmp file directly to observe the actual (buggy) byte
    // computation independent of this platform rename quirk. (On real Android/Linux devices,
    // POSIX rename() overwrites atomically and the final file itself would show the same
    // truncation — this is confirmed later via the real-device cross-check.)
    @Test
    fun repairMp3File_mpeg2_interiorGap_audioAfterGapSurvives() {
        val testFile = tempFolder.newFile("interior_gap.mp3")

        val before = mpeg2Stream(10, fill = 0x11)
        val junk = ByteArray(50) { 0x00 } // never 0xFF, no accidental resync
        val after = mpeg2Stream(10, fill = 0x22)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        AudioRepairEngine.repairMp3File(testFile)
        val tempOutput = File(testFile.parentFile, "${testFile.name}.tmp")
        val resultBytes = if (tempOutput.exists()) tempOutput.readBytes() else testFile.readBytes()

        val afterFramesPresent = indexOfSubArray(resultBytes, after) >= 0
        assertTrue(
            "audio after the interior gap was discarded (catastrophic data loss) — " +
                "repaired output is ${resultBytes.size} bytes, original was ${fullData.size}",
            afterFramesPresent
        )
        assertTrue("audio before the gap was discarded", indexOfSubArray(resultBytes, before) >= 0)
    }

    // ---- CATASTROPHIC BUG: leading gap with no ID3 tag must not zero the file ----
    // See NOTE above: inspects the .tmp file the engine writes before its rename step, to
    // observe the buggy byte computation independent of this host's renameTo quirk.
    @Test
    fun repairMp3File_mpeg2_leadingGapNoId3_fileNotReducedToZero() {
        val testFile = tempFolder.newFile("leading_gap.mp3")

        val junk = ByteArray(30) { 0x00 } // no ID3 tag at all
        val audio = mpeg2Stream(10)
        val fullData = junk + audio
        testFile.writeBytes(fullData)

        AudioRepairEngine.repairMp3File(testFile)
        val tempOutput = File(testFile.parentFile, "${testFile.name}.tmp")
        val resultBytes = if (tempOutput.exists()) tempOutput.readBytes() else testFile.readBytes()

        assertTrue(
            "repaired output must not be empty — entire recording was deleted " +
                "(size=${resultBytes.size}, original was ${fullData.size})",
            resultBytes.isNotEmpty()
        )
        assertTrue(
            "repaired output must retain the audio frames that followed the leading junk",
            indexOfSubArray(resultBytes, audio) >= 0
        )
    }

    // ---- CRITICAL: backup must exist before the original is overwritten ----
    @Test
    fun repairMp3File_repair_leavesRecoverableBackupOfOriginal() {
        val testFile = tempFolder.newFile("needs_backup.mp3")
        val before = mpeg2Stream(5)
        val junk = ByteArray(20) { 0x00 }
        val after = mpeg2Stream(5)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        AudioRepairEngine.repairMp3File(testFile)

        val backupFile = File(testFile.parentFile, "${testFile.name}.bak")
        assertTrue("expected a backup file preserving the pre-repair bytes", backupFile.exists())
        assertArrayEquals(
            "backup must contain the exact original bytes, including the audio the repair excised",
            fullData,
            if (backupFile.exists()) backupFile.readBytes() else ByteArray(0)
        )
    }

    // ---- HIGH: unresyncable trailing corruption must be detected, not reported clean ----
    @Test
    fun repairMp3File_mpeg2_unresyncableTrailingCorruption_isDetectedNotClean() {
        val testFile = tempFolder.newFile("trailing_corrupt.mp3")

        val clean = mpeg2Stream(10)
        // Corrupt junk that can never resync to a valid header before EOF (no 0xFF byte at all).
        val trailingJunk = ByteArray(60) { 0x00 }
        val fullData = clean + trailingJunk
        testFile.writeBytes(fullData)

        val scan = Mp3FrameScan.scan(fullData)
        assertTrue(
            "unresyncable trailing corruption must be counted as a gap by the scanner, was reported clean (gapCount=0)",
            scan.gapCount > 0
        )
    }

    // ---- Boolean-return regression: renameTo failure must not be reported as success ----
    @Test
    fun repairMp3File_whenRenameFails_doesNotReportSuccess() {
        val testFile = tempFolder.newFile("locked_target.mp3")
        val before = mpeg2Stream(5)
        val junk = ByteArray(20) { 0x00 }
        val after = mpeg2Stream(5)
        val fullData = before + junk + after
        testFile.writeBytes(fullData)

        // Make the destination un-replaceable: File.renameTo fails on Windows/NTFS when the
        // target is read-only, so the engine's rename silently fails while still returning true.
        testFile.setWritable(false)
        try {
            val repaired = AudioRepairEngine.repairMp3File(testFile)
            assertTrue(
                "rename/overwrite failure must not be reported as a successful repair",
                !repaired
            )
        } finally {
            testFile.setWritable(true)
        }
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
