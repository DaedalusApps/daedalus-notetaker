package com.daedalus.notes

import com.daedalus.notes.ble.ParsedResponse
import com.daedalus.notes.ble.parseResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FW920ProtocolTest {

    /** Builds a raw A0 0A 01 [cmd] [len] [payload] notification (CRC omitted; parser ignores it). */
    private fun packet(cmd: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xA0.toByte(), 0x0A, 0x01, cmd.toByte(), payload.size.toByte()) + payload

    private fun statusPayload(isRecording: Boolean): ByteArray {
        val payload = ByteArray(13)
        payload[9] = 75            // battery %
        payload[12] = if (isRecording) 0x01 else 0x00
        return payload
    }

    @Test
    fun status0x05_reportsRecordingTrue() {
        val parsed = parseResponse(packet(0x05, statusPayload(isRecording = true)))
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(0x05, (parsed as ParsedResponse.Status).cmd)
        assertTrue(parsed.status.isRecording)
    }

    @Test
    fun status0x05_reportsRecordingFalse() {
        val parsed = parseResponse(packet(0x05, statusPayload(isRecording = false)))
        assertTrue(parsed is ParsedResponse.Status)
        assertEquals(false, (parsed as ParsedResponse.Status).status.isRecording)
    }
}
