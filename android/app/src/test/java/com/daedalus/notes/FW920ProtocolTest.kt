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
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = true)), isAudioChannel = false)
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(0x05, (parsed as ParsedResponse.Status).cmd)
        assertTrue(parsed.status.isRecording)
    }

    @Test
    fun status0x05_reportsRecordingFalse() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = false)), isAudioChannel = false)
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(false, (parsed as ParsedResponse.Status).status.isRecording)
    }

    @Test
    fun audioChunkStartingWithA00A_parsedAsAudioChunk() {
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        val parsed = parseResponse(data, isAudioChannel = true)
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
        val parsed = parseResponse(raw0A, isAudioChannel = false)
        assertTrue("Expected FileList, got $parsed", parsed is ParsedResponse.FileList)
        val entry = (parsed as ParsedResponse.FileList).entry
        assertEquals("20260812102746", entry?.filename)
        assertEquals(24259337L, entry?.sizeBytes)
    }

    @Test
    fun validControlPacket_parsedAsCommand() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = true)), isAudioChannel = false)
        assertTrue(parsed is ParsedResponse.Status)
    }

    // --- #96: audio chunks beginning A0 0A must not parse as control -------------------------

    /**
     * A 244-byte raw-MP3 audio notification whose bytes happen to satisfy the old length
     * heuristic (byte[4] == 237 -> 5 + 237 + 2 == 244) must still be treated as audio when it
     * arrives on the audio channel, not misparsed as a CMD 0x0B control packet.
     */
    @Test
    fun audioChunkOnAudioChannel_matchingAckHeuristic_parsedAsAudioChunk() {
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        data[2] = 0x01
        data[3] = 0x0B          // looks like CMD 0x0B (download ack)
        data[4] = 237.toByte()  // 5 + 237 + 2 == 244 -> satisfies the old length heuristic

        val parsed = parseResponse(data, isAudioChannel = true)
        assertTrue("Expected AudioChunk, got $parsed", parsed is ParsedResponse.AudioChunk)
    }

    /** The same bytes, received on the control channel while idle, are still a control packet. */
    @Test
    fun sameBytesOnControlChannel_parsedAsControl() {
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        data[2] = 0x01
        data[3] = 0x0B
        data[4] = 237.toByte()

        val parsed = parseResponse(data, isAudioChannel = false)
        assertTrue("Expected Ack, got $parsed", parsed is ParsedResponse.Ack)
        assertEquals(0x0B, (parsed as ParsedResponse.Ack).cmd)
    }

    /**
     * The end-of-file Ack(0x0B) — sent on the control channel — must still parse as a control
     * Ack even while a transfer is in progress. This is the regression that would hurt most:
     * misrouting this packet would make downloadFile() hang until timeout.
     */
    @Test
    fun eofAck_onControlChannel_stillParsedDuringTransfer() {
        val eofAck = buildPacket(0x0B)
        val parsed = parseResponse(eofAck, isAudioChannel = false)
        assertTrue("Expected Ack, got $parsed", parsed is ParsedResponse.Ack)
        assertEquals(0x0B, (parsed as ParsedResponse.Ack).cmd)
    }

    /**
     * A sub-2-byte tail chunk on the audio channel (e.g. the final byte of a block) must still
     * be treated as audio, not dropped by the too-short-for-a-header check that only applies to
     * the control-parsing path.
     */
    @Test
    fun oneByteChunkOnAudioChannel_parsedAsAudioChunk() {
        val data = byteArrayOf(0x7F)
        val parsed = parseResponse(data, isAudioChannel = true)
        assertTrue("Expected AudioChunk, got $parsed", parsed is ParsedResponse.AudioChunk)
        assertEquals(1, (parsed as ParsedResponse.AudioChunk).data.size)
    }

    /** The same too-short buffer on the control channel is still discarded as unparseable. */
    @Test
    fun oneByteChunkOnControlChannel_returnsNull() {
        val data = byteArrayOf(0x7F)
        val parsed = parseResponse(data, isAudioChannel = false)
        assertEquals(null, parsed)
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

        val parsed = parseResponse(buildPacket(0x0A, payload), isAudioChannel = false)
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

        val parsed = parseResponse(buildPacket(0x0A, payload), isAudioChannel = false)
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
        val parsed = parseResponse(buildPacket(0x0A, payload), isAudioChannel = false)
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

    // --- #116: single-byte length field must never desync from the actual payload -------------

    /**
     * buildPacket() encodes the payload length as `payload.size.toByte()`. For a payload larger
     * than 255 bytes this silently wraps (e.g. 300 -> 44), producing a packet whose declared
     * length byte no longer matches the number of payload bytes actually written to the wire —
     * a mid-session GATT protocol desync with no exception thrown. buildPacket must refuse to
     * build such a packet instead of silently corrupting the length field.
     */
    @Test
    fun buildPacket_payloadOver255Bytes_throwsInsteadOfCorruptingLengthByte() {
        val oversizedPayload = ByteArray(300) { 0x41 }
        try {
            com.daedalus.notes.ble.buildPacket(0x0D, oversizedPayload)
            org.junit.Assert.fail(
                "buildPacket silently accepted a 300-byte payload; " +
                    "the length byte cannot represent this and will desync the wire protocol"
            )
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * Imported recordings keep their original extensions and are not constrained to the FW920's
     * 14-digit naming scheme, so a filename can legitimately be much longer than 14 characters —
     * and, unclamped, long enough to push buildDeleteFile's payload past 255 bytes. When that
     * happens the declared length byte (read back the same way a real FW920 would: unsigned,
     * `and 0xFF`) must equal the number of payload bytes actually sent, or the device desyncs
     * mid-session without either side raising an error.
     */
    @Test
    fun buildDeleteFile_pathologicallyLongFilename_declaredLengthMatchesActualPayload() {
        val longName = "N".repeat(300)
        val pkt = com.daedalus.notes.ble.buildDeleteFile(longName)

        val declaredLen = pkt[4].toInt() and 0xFF
        val actualPayloadLen = pkt.size - 5 - 2 // header(5) .. payload .. crc(2)

        assertEquals(
            "declared length byte must equal the actual payload length actually written",
            actualPayloadLen,
            declaredLen
        )
    }

    /** Same invariant for downloadFile's CMD 0x0B packet construction (buildDownloadFile). */
    @Test
    fun buildDownloadFile_pathologicallyLongFilename_declaredLengthMatchesActualPayload() {
        val longName = "N".repeat(300)
        val pkt = com.daedalus.notes.ble.buildDownloadFile(longName)

        val declaredLen = pkt[4].toInt() and 0xFF
        val actualPayloadLen = pkt.size - 5 - 2

        assertEquals(
            "declared length byte must equal the actual payload length actually written",
            actualPayloadLen,
            declaredLen
        )
    }

    // --- #116 finding 1: pin buildDownloadFile's actual payload, not just buildPacket's -------
    // arithmetic. Mirrors buildDeleteFile_longerFilename_notTruncated above but checks every
    // field buildDownloadFile itself is responsible for: opcode, no truncation, space padding,
    // the 4 trailing zero bytes, and .mp3-suffix stripping.

    /** buildDownloadFile preserves full filename for names longer than 14 chars (no truncation). */
    @Test
    fun buildDownloadFile_longerFilename_notTruncated() {
        val pkt = com.daedalus.notes.ble.buildDownloadFile("Note-20260812102746")
        val len = pkt[4].toInt() and 0xFF
        val payload = pkt.copyOfRange(5, 5 + len)
        val name = payload.copyOfRange(0, payload.size - 4).toString(Charsets.US_ASCII).trimEnd(' ')
        assertEquals("Note-20260812102746", name)
    }

    /** buildDownloadFile uses opcode 0x0B. */
    @Test
    fun buildDownloadFile_usesOpcode0x0B() {
        val pkt = com.daedalus.notes.ble.buildDownloadFile("20260812102746")
        assertEquals(0x0B, pkt[3].toInt() and 0xFF)
    }

    /** buildDownloadFile's payload ends with 4 trailing zero bytes after the filename field. */
    @Test
    fun buildDownloadFile_appendsFourTrailingZeroBytes() {
        val pkt = com.daedalus.notes.ble.buildDownloadFile("20260812102746")
        val len = pkt[4].toInt() and 0xFF
        val payload = pkt.copyOfRange(5, 5 + len)
        val trailing = payload.copyOfRange(payload.size - 4, payload.size)
        assertEquals(listOf<Byte>(0, 0, 0, 0), trailing.toList())
    }

    /** buildDownloadFile pads short filenames to 14 chars with ASCII spaces, not NUL. */
    @Test
    fun buildDownloadFile_padsShortFilenameWithSpaces() {
        val pkt = com.daedalus.notes.ble.buildDownloadFile("AB")
        val len = pkt[4].toInt() and 0xFF
        val payload = pkt.copyOfRange(5, 5 + len)
        // name field = payload minus the 4 trailing zero bytes = 14-byte padded "AB"
        val nameField = payload.copyOfRange(0, payload.size - 4)
        assertEquals(14, nameField.size)
        val expected = "AB".padEnd(14, ' ').toByteArray(Charsets.US_ASCII)
        assertEquals(expected.toList(), nameField.toList())
    }

    /** buildDownloadFile strips a trailing ".mp3" suffix before building the payload. */
    @Test
    fun buildDownloadFile_stripsMp3Suffix() {
        val withSuffix = com.daedalus.notes.ble.buildDownloadFile("20260812102746.mp3")
        val withoutSuffix = com.daedalus.notes.ble.buildDownloadFile("20260812102746")
        assertTrue(withSuffix.contentEquals(withoutSuffix))

        val len = withSuffix[4].toInt() and 0xFF
        val payload = withSuffix.copyOfRange(5, 5 + len)
        val name = payload.copyOfRange(0, payload.size - 4).toString(Charsets.US_ASCII).trimEnd(' ')
        assertEquals("20260812102746", name)
    }
}
