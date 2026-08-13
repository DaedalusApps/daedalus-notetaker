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

    private fun matchesAsciiAt(data: ByteArray, pos: Int, marker: String): Boolean {
        if (pos < 0 || pos + marker.length > data.size) return false
        for (i in marker.indices) {
            if (data[pos + i] != marker[i].code.toByte()) return false
        }
        return true
    }

    private fun readInt32LE(data: ByteArray, pos: Int): Long {
        val b0 = data[pos].toLong() and 0xFF
        val b1 = data[pos + 1].toLong() and 0xFF
        val b2 = data[pos + 2].toLong() and 0xFF
        val b3 = data[pos + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * A frame at [pos] can fail chain confirmation solely because its successor isn't a frame at
     * all (not because [pos] itself is damaged). Returns [pos]'s frame length if it parses as a
     * real, standalone, in-bounds header, else 0 — used so that one real trailing frame isn't
     * charged as loss (or excluded from [Mp3ScanResult.framesOk]) just because a gap or trailer
     * follows it.
     */
    private fun standaloneFrameLenAt(data: ByteArray, pos: Int, regionEnd: Int): Int =
        parseHeader(data, pos)?.frameLen?.takeIf { pos + it <= regionEnd } ?: 0

    /**
     * True only if `[start, regionEnd)` is EXACTLY a well-formed, footer-only APEv2 tag —
     * verified against the footer's own size field, not merely "the last 32 bytes spell
     * APETAGEX". The size field is a 32-bit LE integer giving the tag's total on-disk size
     * including the footer itself; requiring `regionEnd - tagSize == start` means the tag must
     * account for the *entire* unresynced span, so real corruption can't hide behind a
     * legitimate-looking footer by sitting in front of it.
     *
     * KNOWN LIMITATION: a header-bearing APEv2 tag (footer PLUS a mirrored 32-byte header
     * immediately before the items) is spec-legal, but its footer's size field excludes that
     * header, so `regionEnd - tagSize` lands 32 bytes short of the header-bearing tag's actual
     * start and this check (correctly) fails to match — such a tag is reported as an unresolved
     * gap rather than recognized. Deliberate: this scanner has no evidence any tool writing to
     * FW920 recordings produces header-bearing tags, and a false "corrupted" signal (a wasted
     * re-fetch) is the safer failure direction than trusting an unverified span as benign — see
     * [isBenignTrailer]'s class doc.
     */
    private fun isBoundedApeV2Footer(data: ByteArray, start: Int, regionEnd: Int): Boolean {
        val footerStart = regionEnd - 32
        if (footerStart < start) return false
        if (!matchesAsciiAt(data, footerStart, "APETAGEX")) return false
        val tagSize = readInt32LE(data, footerStart + 12)
        if (tagSize < 32) return false // must at least cover the footer itself
        val tagStart = regionEnd - tagSize
        return tagStart == start.toLong()
    }

    /**
     * A trailing span that never resyncs to a valid frame before [regionEnd] is either real,
     * unrecoverable damage (a dropped BLE chunk, a truncated transfer) or a recognized non-audio
     * trailer (an APEv2 tag written by other tooling). MP3 audio — including digital silence —
     * is never a constant byte run, so a span is treated as benign only when it is *exactly* a
     * verifiably-bounded tag; a same-value byte run gets no special treatment; a tag format
     * whose size field can't be checked against the actual span isn't special-cased at all. A
     * false "corrupted" signal costs the user a re-fetch; a false "clean" signal hides the loss
     * entirely.
     *
     * ACCEPTED TRADE-OFF: a benign trailer this scanner doesn't recognize (flash-sector padding,
     * an appended ID3v2.4 tag, Lyrics3v2, a header-bearing APEv2 tag — see
     * [isBoundedApeV2Footer]'s KNOWN LIMITATION) is reported as a gap, not treated as benign.
     * This is deliberately the more conservative failure direction: the cost of a false
     * "corrupted" signal is a wasted re-download — `RecordingViewModel.redownloadAndAnalyze`
     * aborts cleanly if the FW920 copy is already gone, and backs up and restores the existing
     * local file if the re-download itself fails — while the cost of a false "clean" signal on
     * real damage is the user never being offered that re-download at all.
     */
    private fun isBenignTrailer(data: ByteArray, brokenPos: Int, regionEnd: Int): Boolean {
        if (brokenPos >= regionEnd) return true
        val start = brokenPos + standaloneFrameLenAt(data, brokenPos, regionEnd)
        if (start >= regionEnd) return true
        return isBoundedApeV2Footer(data, start, regionEnd)
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
            // No frame chains anywhere in the audio region. Two distinct cases:
            //  - The region is too small to even attempt a header parse (< 4 bytes — the same
            //    bound the scan loop above uses): there's nothing decoded because there was
            //    nothing to decode, not because anything was lost. Degenerate input, not damage.
            //  - The region had enough bytes to scan and still nothing chained anywhere: pure
            //    junk, all-zero fill, or a shifted/garbled stream that never happens to resync.
            //    This is total loss and must be reported as a gap — reporting it clean would
            //    hide the one case where the FW920 copy is most likely still recoverable via a
            //    re-fetch.
            val lossBytes = (audioEnd - audioStart).toLong()
            return if (audioEnd - audioStart >= 4) {
                Mp3ScanResult(0, 1, lossBytes, audioStart.toLong(), size.toLong())
            } else {
                Mp3ScanResult(0, 0, 0L, null, size.toLong())
            }
        }

        var framesOk = 0
        var gapCount = 0
        var gapBytesTotal = 0L
        var firstGapOffset: Long? = null

        // Bytes skipped to reach the first accepted frame are leading loss.
        if (startPos > audioStart) {
            gapCount++
            gapBytesTotal += (startPos - audioStart).toLong()
            firstGapOffset = audioStart.toLong()
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
        }

        var pos = startPos
        while (true) {
            val h = chains(bytes, pos, audioEnd)
            if (h == null) {
                // Defensive: shouldn't happen since pos was validated to chain already.
                val resyncPos = resyncFrom(pos)
                if (resyncPos == null) {
                    if (isBenignTrailer(bytes, pos, audioEnd)) {
                        // Not corruption: if pos is itself a real, independently-parseable frame
                        // (only its lookahead confirmation failed, because what follows is
                        // benign trailer rather than another frame), count it — this is just a
                        // truncated tail with trailer bytes after it, not lost audio.
                        if (parseHeader(bytes, pos) != null) framesOk++
                        break
                    }
                    // Real damage — but `pos` can still be one real, independently-parseable
                    // frame that only failed lookahead confirmation because nothing valid
                    // follows it. Count it as audio and start the gap after it, the same way the
                    // benign branch above does, so the reported loss is only the bytes that are
                    // actually unrecoverable.
                    val gapStart = pos + standaloneFrameLenAt(bytes, pos, audioEnd)
                    if (gapStart > pos) framesOk++
                    recordGap(gapStart, null)
                    break
                }
                recordGap(pos, resyncPos)
                pos = resyncPos
                continue
            }

            framesOk++
            val nextPos = pos + h.frameLen
            if (nextPos >= audioEnd) break // truncated tail (or recognized trailer), done

            if (chains(bytes, nextPos, audioEnd) != null) {
                pos = nextPos
                continue
            }

            val resyncPos = resyncFrom(nextPos)
            if (resyncPos == null) {
                if (isBenignTrailer(bytes, nextPos, audioEnd)) {
                    if (parseHeader(bytes, nextPos) != null) framesOk++
                    break
                }
                // Real damage — same boundary-frame carve-out as above: don't charge a real,
                // independently-parseable frame as lost bytes just because it's the last one.
                val gapStart = nextPos + standaloneFrameLenAt(bytes, nextPos, audioEnd)
                if (gapStart > nextPos) framesOk++
                recordGap(gapStart, null)
                break
            }
            recordGap(nextPos, resyncPos)
            pos = resyncPos
        }

        return Mp3ScanResult(framesOk, gapCount, gapBytesTotal, firstGapOffset, size.toLong())
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
) {
    val gapPercent: Double
        get() = if (totalSize == 0L) 0.0 else gapBytes.toDouble() / totalSize.toDouble() * 100.0
}
