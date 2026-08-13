package com.daedalus.notes.data.model

import java.io.File

/**
 * Detects interior data loss in a finished MP3 file by validating the chain of MPEG
 * frame headers over the raw bytes. This is a pure content-level check — it knows
 * nothing about BLE, transfer chunking, or notification sizes.
 *
 * A frame at `pos` is accepted only if a header also parses at `pos + frameLen` and
 * agrees with it on MPEG version, layer and sample-rate index (or `pos + frameLen`
 * runs past EOF, which is a normal truncated tail, not loss). When the chain breaks,
 * the scanner resyncs byte-by-byte to the next position where a header chains again
 * and records the skipped span as a gap.
 *
 * KNOWN BLIND SPOT: a loss of exactly a whole number of frame lengths leaves every
 * following frame perfectly byte-aligned with what the scanner expects next, so it is
 * indistinguishable from a clean stream and will NOT be detected. This check can only
 * catch losses that are not an exact multiple of the (constant, for CBR streams) frame
 * length.
 *
 * KNOWN BLIND SPOT 2: corruption that replaces payload bytes while preserving the 4-byte
 * frame header is invisible. Only the header chain is validated; payload integrity (audio
 * data between headers) is not checked.
 */
object Mp3FrameScan {

    private data class Header(
        val versionId: Int,
        val layer: Int,
        val srIdx: Int,
        val frameLen: Int,
    )

    // bitrate table [versionGroup][layerNum] -> kbps indexed by bitrateIdx (0..15); null = invalid
    private val BITRATE_TABLE: Map<Pair<String, Int>, List<Int?>> = mapOf(
        ("V1" to 1) to listOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, null),
        ("V1" to 2) to listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, null),
        ("V1" to 3) to listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, null),
        ("V2" to 1) to listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, null),
        ("V2" to 2) to listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, null),
        ("V2" to 3) to listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, null),
    )

    // sample rate table [versionId] -> Hz indexed by srIdx (0..3, 3=invalid); null entry = reserved version
    private val SAMPLE_RATE_TABLE: Map<Int, List<Int?>?> = mapOf(
        3 to listOf(44100, 48000, 32000, null), // MPEG1
        2 to listOf(22050, 24000, 16000, null), // MPEG2
        0 to listOf(11025, 12000, 8000, null),  // MPEG2.5
        1 to null,                              // reserved
    )

    /** Returns (id3Present, totalTagSizeIncludingHeader). */
    private fun parseId3(data: ByteArray): Pair<Boolean, Int> {
        if (data.size >= 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            val b5 = data[5].toInt() and 0xFF
            val b6 = data[6].toInt() and 0xFF
            val b7 = data[7].toInt() and 0xFF
            val b8 = data[8].toInt() and 0xFF
            val b9 = data[9].toInt() and 0xFF
            val size = ((b6 and 0x7F) shl 21) or ((b7 and 0x7F) shl 14) or ((b8 and 0x7F) shl 7) or (b9 and 0x7F)
            var total = size + 10
            if (b5 and 0x10 != 0) total += 10
            return true to total
        }
        return false to 0
    }

    private fun parseHeader(data: ByteArray, pos: Int): Header? {
        if (pos + 4 > data.size) return null
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF

        if (b0 != 0xFF) return null
        if (b1 and 0xE0 != 0xE0) return null

        val versionId = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        val bitrateIdx = (b2 shr 4) and 0x0F
        val srIdx = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01

        if (versionId == 1) return null // reserved
        if (layer == 0) return null // reserved
        if (bitrateIdx == 0 || bitrateIdx == 15) return null // free / invalid
        if (srIdx == 3) return null // invalid

        val versionGroup = if (versionId == 3) "V1" else "V2"
        val layerNum = when (layer) { 1 -> 3; 2 -> 2; 3 -> 1; else -> return null }

        val bitrateKbps = BITRATE_TABLE[versionGroup to layerNum]?.get(bitrateIdx) ?: return null
        val bitrate = bitrateKbps * 1000

        val srList = SAMPLE_RATE_TABLE[versionId] ?: return null
        val sampleRate = srList[srIdx] ?: return null

        val frameLen = if (layerNum == 1) {
            (12 * bitrate / sampleRate + padding) * 4
        } else {
            val samplesPerFrame = if (layerNum == 3 && versionId != 3) 576 else 1152
            samplesPerFrame / 8 * bitrate / sampleRate + padding
        }
        if (frameLen <= 4) return null

        return Header(versionId, layer, srIdx, frameLen)
    }

    private fun headersAgree(h1: Header, h2: Header): Boolean =
        h1.versionId == h2.versionId && h1.layer == h2.layer && h1.srIdx == h2.srIdx

    /**
     * Returns the accepted header at [pos], or null. A header is accepted if a header
     * also parses at pos+frameLen and agrees on version/layer/srIdx, or if pos+frameLen
     * runs to/past [limit] (truncated tail or start of a recognized trailer, accepted
     * without a successor). [limit] defaults to the end of [data] but is passed as the
     * start of a recognized trailing tag when one is present, so the last real audio
     * frame isn't penalized for being followed by non-audio metadata instead of EOF.
     */
    private fun chains(data: ByteArray, pos: Int, limit: Int = data.size): Header? {
        val h = parseHeader(data, pos) ?: return null
        val nextPos = pos + h.frameLen
        if (nextPos >= limit) return h
        val h2 = parseHeader(data, nextPos) ?: return null
        return if (headersAgree(h, h2)) h else null
    }

    /** True if a 128-byte ID3v1 tag ("TAG" + 125 bytes) occupies the last 128 bytes of [data]. */
    private fun hasTrailingId3v1(data: ByteArray): Boolean {
        if (data.size < 128) return false
        val tagStart = data.size - 128
        return data[tagStart] == 'T'.code.toByte() &&
            data[tagStart + 1] == 'A'.code.toByte() &&
            data[tagStart + 2] == 'G'.code.toByte()
    }

    fun scan(bytes: ByteArray): Mp3ScanResult {
        val size = bytes.size
        val (id3Present, id3Size) = parseId3(bytes)
        val audioStart = if (id3Present) id3Size else 0
        // A trailing ID3v1 tag is legitimate metadata, not corruption: exclude it from the
        // scanned region entirely (symmetric with the leading ID3v2 header above) so the last
        // real audio frame isn't flagged as an unresyncable gap merely for being followed by
        // "TAG..." bytes instead of true EOF.
        val audioEnd = if (hasTrailingId3v1(bytes)) size - 128 else size

        var startPos = -1
        var p = audioStart
        while (p + 4 <= audioEnd) {
            if (chains(bytes, p, audioEnd) != null) { startPos = p; break }
            p++
        }

        if (startPos < 0) {
            return Mp3ScanResult(0, 0, 0L, null, size.toLong())
        }

        var framesOk = 0
        var gapCount = 0
        var gapBytesTotal = 0L
        var firstGapOffset: Long? = null
        val gapRanges = mutableListOf<LongRange>()

        // Bytes skipped to reach the first accepted frame are leading loss.
        if (startPos > audioStart) {
            gapCount++
            gapBytesTotal += (startPos - audioStart).toLong()
            firstGapOffset = audioStart.toLong()
            gapRanges.add(audioStart.toLong() until startPos.toLong())
        }

        fun resyncFrom(brokenPos: Int): Int? {
            var scanP = brokenPos + 1
            while (scanP + 4 <= audioEnd) {
                if (chains(bytes, scanP, audioEnd) != null) return scanP
                scanP++
            }
            return null
        }

        // resyncPos == null means corruption ran unresynced all the way to audioEnd: the gap
        // covers [brokenPos, audioEnd) and is not a normal truncated tail.
        fun recordGap(brokenPos: Int, resyncPos: Int?) {
            gapCount++
            val gapEnd = resyncPos ?: audioEnd
            gapBytesTotal += (gapEnd - brokenPos).toLong()
            if (firstGapOffset == null) firstGapOffset = brokenPos.toLong()
            gapRanges.add(brokenPos.toLong() until gapEnd.toLong())
        }

        var pos = startPos
        while (true) {
            val h = chains(bytes, pos, audioEnd)
            if (h == null) {
                // Defensive: shouldn't happen since pos was validated to chain already.
                val resyncPos = resyncFrom(pos)
                recordGap(pos, resyncPos)
                if (resyncPos != null) {
                    pos = resyncPos
                    continue
                } else {
                    break
                }
            }

            framesOk++
            val nextPos = pos + h.frameLen
            if (nextPos >= audioEnd) break // truncated tail (or recognized trailer), done

            if (chains(bytes, nextPos, audioEnd) != null) {
                pos = nextPos
                continue
            }

            val resyncPos = resyncFrom(nextPos)
            recordGap(nextPos, resyncPos)
            if (resyncPos != null) {
                pos = resyncPos
            } else {
                break
            }
        }

        return Mp3ScanResult(framesOk, gapCount, gapBytesTotal, firstGapOffset, size.toLong(), gapRanges)
    }

    /** Returns an all-zero result for a missing or empty file rather than throwing. */
    fun scan(file: File): Mp3ScanResult {
        if (!file.exists() || file.length() == 0L) {
            return Mp3ScanResult(0, 0, 0L, null, 0L)
        }
        return scan(file.readBytes())
    }
}

data class Mp3ScanResult(
    val framesOk: Int,
    val gapCount: Int,
    val gapBytes: Long,
    val firstGapOffset: Long?,
    val totalSize: Long,
    /** Byte ranges (end-exclusive) of corrupted/unresynced spans, in ascending, non-overlapping order. */
    val gapRanges: List<LongRange> = emptyList(),
) {
    val gapPercent: Double
        get() = if (totalSize == 0L) 0.0 else gapBytes.toDouble() / totalSize.toDouble() * 100.0
}
