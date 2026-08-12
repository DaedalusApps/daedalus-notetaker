package com.daedalus.notes.data.model

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioRepairEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun repairMp3File_trimsCorruptedTrailingBytes() {
        val testFile = tempFolder.newFile("test_recording.mp3")

        // Construct 2 valid chained MP3 frames (417 bytes each) + 5 bytes junk tail
        val validFrameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val frame1 = ByteArray(417) { 0x55.toByte() }.also { System.arraycopy(validFrameHeader, 0, it, 0, 4) }
        val frame2 = ByteArray(417) { 0x55.toByte() }.also { System.arraycopy(validFrameHeader, 0, it, 0, 4) }

        val junkTail = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44)
        val fullData = frame1 + frame2 + junkTail

        testFile.writeBytes(fullData)

        val repaired = AudioRepairEngine.repairMp3File(testFile)
        assertTrue("Repair should return true for restorable MP3 frame stream", repaired)
    }
}
