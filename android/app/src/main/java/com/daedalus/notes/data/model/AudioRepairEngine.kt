package com.daedalus.notes.data.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AudioRepairEngine {

    /**
     * Outcome of a repair attempt. Distinguishes "nothing wrong", "fixed it", "declined to
     * touch the file" and "tried and failed" — a plain Boolean cannot tell those apart, and
     * this file may be the user's only copy of the recording.
     */
    sealed class RepairResult {
        /** File already had a clean, unbroken frame chain. Not touched. */
        object Clean : RepairResult()

        /** Corrupted byte spans were excised; [bytesRemoved] were dropped. All valid frame bytes were kept. */
        data class Repaired(val bytesRemoved: Int) : RepairResult()

        /** Declined to modify the file because a safe repair could not be guaranteed. Not touched. */
        data class Refused(val reason: String) : RepairResult()

        /** A repair was attempted but did not complete safely. */
        data class Failed(val reason: String) : RepairResult()
    }

    /**
     * Scans an MP3 file for valid frame headers. If corrupted/unresyncable spans are found
     * between otherwise-valid frame chains, excises exactly those byte ranges and re-writes
     * the file with everything else intact.
     *
     * MP3 frames are independently decodable, so a gap between two valid frame chains does not
     * require discarding the audio that follows it — only the unresyncable bytes themselves are
     * removed, and every byte belonging to a valid frame (or a leading ID3 tag) is preserved.
     *
     * Invariant: the repaired file never contains less valid audio than the original. The
     * original bytes are backed up (`<name>.bak`) before the file is overwritten, and the
     * overwrite is an atomic move so a crash mid-write cannot corrupt or lose the original.
     */
    fun repairMp3File(inputFile: File): RepairResult {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            return RepairResult.Refused("file missing or empty")
        }

        val bytes = inputFile.readBytes()
        if (bytes.size < 10) {
            return RepairResult.Refused("file too small to contain a valid MP3 stream")
        }

        val scan = Mp3FrameScan.scan(bytes)
        if (scan.framesOk == 0) {
            return RepairResult.Refused("no valid MP3 frames found; refusing to guess")
        }

        if (scan.gapCount == 0) {
            return RepairResult.Clean
        }

        // Keep every byte NOT covered by a recorded gap. Ranges are ascending and
        // non-overlapping by construction (Mp3FrameScan.scan walks the file left to right).
        val cleanOut = ByteArrayOutputStream(bytes.size)
        var cursor = 0
        for (range in scan.gapRanges) {
            val start = range.first.toInt().coerceIn(cursor, bytes.size)
            val end = (range.last + 1).toInt().coerceIn(start, bytes.size)
            cleanOut.write(bytes, cursor, start - cursor)
            cursor = end
        }
        cleanOut.write(bytes, cursor, bytes.size - cursor)
        val cleanBytes = cleanOut.toByteArray()

        if (cleanBytes.size >= bytes.size) {
            // Nothing was actually excised. Shouldn't happen when gapCount > 0, but if it
            // does, refuse rather than perform a no-op overwrite of the original.
            return RepairResult.Refused("gaps reported but no corrupt bytes identified")
        }

        val tempFile = File(inputFile.parentFile, "${inputFile.name}.tmp")
        val backupFile = File(inputFile.parentFile, "${inputFile.name}.bak")

        try {
            FileOutputStream(tempFile).use { out -> out.write(cleanBytes) }
        } catch (e: IOException) {
            tempFile.delete()
            return RepairResult.Failed("could not write repaired temp file: ${e.message}")
        }

        try {
            inputFile.copyTo(backupFile, overwrite = true)
        } catch (e: IOException) {
            tempFile.delete()
            return RepairResult.Failed("could not back up original before repair: ${e.message}")
        }

        try {
            Files.move(
                tempFile.toPath(),
                inputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            tempFile.delete()
            return RepairResult.Failed(
                "rename of repaired file failed (${e.message}); original left untouched, backup at ${backupFile.name}"
            )
        }

        return RepairResult.Repaired(bytes.size - cleanBytes.size)
    }
}
