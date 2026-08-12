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
    fun validControlPacket_parsedAsCommand() {
        val parsed = parseResponse(buildPacket(0x05, statusPayload(isRecording = true)))
        assertTrue(parsed is ParsedResponse.Status)
    }
}
