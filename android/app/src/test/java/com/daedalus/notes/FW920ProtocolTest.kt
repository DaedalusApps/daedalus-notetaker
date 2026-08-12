package com.daedalus.notes

import com.daedalus.notes.ble.ParsedResponse
import com.daedalus.notes.ble.parseResponse
import com.daedalus.notes.ble.buildPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FW920ProtocolTest {

    private fun statusPayload(isRecording: Boolean): ByteArray {
        val payload = ByteArray(13)
        payload[9] = 75            // battery %
        payload[12] = if (isRecording) 0x01 else 0x00
        return payload
    }

    @Test
    fun status0x05_reportsRecordingTrue() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = true)))
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(0x05, (parsed as ParsedResponse.Status).cmd)
        assertTrue(parsed.status.isRecording)
    }

    @Test
    fun status0x05_reportsRecordingFalse() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = false)))
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(false, (parsed as ParsedResponse.Status).status.isRecording)
    }

    @Test
    fun audioChunkStartingWithA00A_parsedAsAudioChunk() {
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        val parsed = parseResponse(data)
        assertTrue(parsed is ParsedResponse.AudioChunk)
    }

    @Test
    fun rawLogcatPacket0x0A_checkParsing() {
        val raw0A = byteArrayOf(
            0xA0.toByte(), 0x0A.toByte(), 0x01.toByte(), 0x0A.toByte(), 0x14.toByte(),
            0x00.toByte(), 0x32.toByte(), 0x30.toByte(), 0x32.toByte(), 0x36.toByte(),
            0x30.toByte(), 0x38.toByte(), 0x31.toByte(), 0x32.toByte(), 0x31.toByte(),
            0x30.toByte(), 0x32.toByte(), 0x37.toByte(), 0x34.toByte(), 0x36.toByte(),
            0x00.toByte(), 0x09.toByte(), 0x2B.toByte(), 0x72.toByte(), 0x01.toByte(),
            0x10.toByte(), 0xB7.toByte()
        )
        val parsed = parseResponse(raw0A)
        assertTrue("Expected FileList, got $parsed", parsed is ParsedResponse.FileList)
        val entry = (parsed as ParsedResponse.FileList).entry
        assertEquals("20260812102746", entry?.filename)
        assertEquals(24259337L, entry?.sizeBytes)
    }

    @Test
    fun validControlPacket_parsedAsCommand() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = true)))
        assertTrue(parsed is ParsedResponse.Status)
    }

    // --- File list parsing tests ---

    /** Legacy 14-char filename (e.g. "20260806130549") parses correctly. */
    @Test
    fun fileList_legacy14CharFilename_parsedCorrectly() {
        // Payload: [flag=0x01] [14-byte filename] [separator=0x00] [4-byte LE size]
        val name = "20260806130549"  // 14 chars
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + 14 + 1 + 4)
        payload[0] = 0x01
        nameBytes.copyInto(payload, 1)
        // separator at payload[15] = 0x00 (already zero)
        // size = 62680 = 0x0000F4E8 LE
        payload[16] = 0xE8.toByte()
        payload[17] = 0xF4.toByte()
        payload[18] = 0x00
        payload[19] = 0x00

        val parsed = parseResponse(buildPacket(0x0A, payload))
        assertTrue("Expected FileList, got $parsed", parsed is ParsedResponse.FileList)
        val entry = (parsed as ParsedResponse.FileList).entry
        assertEquals("20260806130549", entry?.filename)
        assertEquals(62696L, entry?.sizeBytes)
    }

    /** New-style longer filename (e.g. "Note-20260812102746", 19 chars) parses correctly. */
    @Test
    fun fileList_longerFilename_parsedCorrectly() {
        // Payload: [flag=0x01] [19-byte filename] [separator=0x00] [4-byte LE size]
        val name = "Note-20260812102746"  // 19 chars
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + 19 + 1 + 4)
        payload[0] = 0x01
        nameBytes.copyInto(payload, 1)
        // separator at payload[20] = 0x00
        // size = 100000 = 0x000186A0 LE
        payload[21] = 0xA0.toByte()
        payload[22] = 0x86.toByte()
        payload[23] = 0x01
        payload[24] = 0x00

        val parsed = parseResponse(buildPacket(0x0A, payload))
        assertTrue("Expected FileList, got $parsed", parsed is ParsedResponse.FileList)
        val entry = (parsed as ParsedResponse.FileList).entry
        assertEquals("Note-20260812102746", entry?.filename)
        assertEquals(100000L, entry?.sizeBytes)
    }

    /** End-of-list (short payload) still returns null entry. */
    @Test
    fun fileList_endOfList_returnsNull() {
        // Short payload = end of list
        val payload = byteArrayOf(0x00)
        val parsed = parseResponse(buildPacket(0x0A, payload))
        assertTrue("Expected FileList, got $parsed", parsed is ParsedResponse.FileList)
        val entry = (parsed as ParsedResponse.FileList).entry
        assertEquals(null, entry)
    }

    /** buildDeleteFile preserves full filename for names longer than 14 chars. */
    @Test
    fun buildDeleteFile_longerFilename_notTruncated() {
        val pkt = com.daedalus.notes.ble.buildDeleteFile("Note-20260812102746")
        // Packet: A0 0A 01 0D [len] [payload...] [CRC hi] [CRC lo]
        val len = pkt[4].toInt() and 0xFF
        val payload = pkt.copyOfRange(5, 5 + len)
        val name = payload.toString(Charsets.US_ASCII).trimEnd(' ')
        assertEquals("Note-20260812102746", name)
    }
}
