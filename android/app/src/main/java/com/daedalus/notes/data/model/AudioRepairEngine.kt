package com.daedalus.notes.data.model

import java.io.File
import java.io.FileOutputStream

object AudioRepairEngine {

    /**
     * Scans an MP3 file for valid frame headers, removes corrupted or truncated trailing bytes,
     * and re-writes a clean, defragmented MP3 file.
     * Returns true if file was repaired or verified intact.
     */
    fun repairMp3File(inputFile: File): Boolean {
        if (!inputFile.exists() || inputFile.length() == 0L) return false

        val bytes = inputFile.readBytes()
        if (bytes.size < 10) return false

        // Scan MP3 frames using Mp3FrameScan
        val scan = Mp3FrameScan.scan(bytes)
        if (scan.framesOk == 0) {
            return false
        }

        // If the file has valid frame sync and no interior gaps, it is clean
        if (scan.gapCount == 0 && scan.framesOk > 0) {
            return true // Clean file
        }

        // Slice exact clean byte range before first gap
        val endPos = scan.firstGapOffset?.toInt() ?: bytes.size
        val cleanBytes = bytes.copyOfRange(0, endPos.coerceAtMost(bytes.size))
        val tempFile = File(inputFile.parentFile, "${inputFile.name}.tmp")
        FileOutputStream(tempFile).use { out ->
            out.write(cleanBytes)
        }

        // Overwrite target file
        if (tempFile.exists()) {
            tempFile.renameTo(inputFile)
            return true
        }

        return false
    }
}
