package com.daedalus.notes.data.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
        object Clean : RepairResult() {
            override fun toString() = "Clean"
        }

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
     * removed. Every byte belonging to a confirmed valid frame (or a leading/trailing tag
     * Mp3FrameScan recognizes) is preserved. One caveat: Mp3FrameScan's chain validation
     * confirms a frame only once its *successor* also parses, so the single real frame
     * immediately bordering a gap can be excised along with it (at most one frame length, ~4.6ms
     * for the FW920's format) — see Mp3FrameScan's KNOWN BLIND SPOTS. This is the one case where
     * the repaired file can contain marginally less audio than the original; every other byte
     * outside a recorded gap range is guaranteed to survive untouched.
     *
     * PRECONDITION callers must uphold: this function only rewrites raw audio bytes. It has no
     * Room/database access by design (consistent with #100's original observation that this
     * engine "touches no Room state"), so it cannot recompute `Recording.durationMillis` or any
     * split-part window after excising bytes from the middle of a file. A caller must recompute
     * both (via `AudioUtils.getDurationMillis` and either re-deriving part windows or refusing
     * repair when the recording has parts) before trusting that stored metadata again. As of
     * this writing there is no such caller — `MainActivity`'s `REPAIR_FILE` trigger is
     * deliberately unreachable (#99) — so this is not currently exploitable, but it must be
     * addressed before that trigger (or any other caller) is re-armed.
     *
     * Durability and backup lifecycle: the original bytes are copied to [backupFileFor] and
     * fsync'd *before* the input file is touched, so a crash or a failed backup leaves the
     * original completely untouched. The repaired bytes are written to a temp file, fsync'd,
     * then atomically swapped in with [Files.move] (`ATOMIC_MOVE` + `REPLACE_EXISTING`, which —
     * unlike `File.renameTo` — correctly overwrites an existing destination cross-platform,
     * including on Windows/NTFS). The backup is intentionally kept indefinitely rather than
     * deleted after a successful repair: this engine operates on files that may be the user's
     * only copy, and auto-deleting the one recovery path the moment this code believes it
     * succeeded is exactly the kind of confident-but-wrong behavior that caused #100 in the
     * first place. A caller that wants to reclaim the doubled disk footprint should do so as an
     * explicit, user-visible action after the user has verified the repaired file plays back
     * correctly — not automatically here.
     */
    fun repairMp3File(inputFile: File): RepairResult = repairMp3File(inputFile, ::defaultAtomicReplace)

    /**
     * [atomicReplace] performs the final swap-in of the repaired temp file over [inputFile].
     * Overridable only for tests: the real overwrite-failure modes (a full disk, a permissions
     * problem, a cross-device temp dir) depend on OS/filesystem specifics that don't reproduce
     * the same way — or at all — across Windows and the POSIX CI runner (`rename(2)` cares about
     * the containing directory's permissions, not the target file's mode, so the Windows-style
     * "make the target read-only" trick that works locally is a no-op on Linux CI). Injecting
     * the failure directly is the only way to deterministically exercise this path everywhere.
     */
    internal fun repairMp3File(
        inputFile: File,
        atomicReplace: (source: File, target: File) -> Unit,
    ): RepairResult {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            return RepairResult.Refused("file missing or empty")
        }

        val bytes = try {
            inputFile.readBytes()
        } catch (e: IOException) {
            return RepairResult.Failed("could not read input file: ${e.message}")
        }
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
        val backupFile = backupFileFor(inputFile)
        backupFile.parentFile?.mkdirs()

        // Backup first: cheapest failure mode (e.g. disk full) aborts before any temp artifact
        // or destructive step exists, and the original is never touched if this doesn't land.
        try {
            inputFile.copyTo(backupFile, overwrite = true)
            fsync(backupFile)
        } catch (e: IOException) {
            backupFile.delete()
            return RepairResult.Failed("could not back up original before repair: ${e.message}")
        }
        if (backupFile.length() != inputFile.length()) {
            // A silent short write (no exception, e.g. an external truncation mid-copy) would
            // otherwise leave a backup that looks fine but can't actually recover the original.
            backupFile.delete()
            return RepairResult.Failed("backup verification failed (size mismatch); original left untouched")
        }

        try {
            FileOutputStream(tempFile).use { out ->
                out.write(cleanBytes)
                out.fd.sync()
            }
        } catch (e: IOException) {
            tempFile.delete()
            return RepairResult.Failed("could not write repaired temp file: ${e.message}")
        }

        try {
            atomicReplace(tempFile, inputFile)
        } catch (e: IOException) {
            tempFile.delete()
            return RepairResult.Failed(
                "rename of repaired file failed (${e.message}); original left untouched, backup at ${backupFile.path}"
            )
        }
        fsyncQuietly(inputFile.parentFile)

        return RepairResult.Repaired(bytes.size - cleanBytes.size)
    }

    private fun defaultAtomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /**
     * Backup location for [inputFile]'s pre-repair bytes. Deliberately namespaced into a
     * dedicated subdirectory rather than a same-directory "sibling" filename: RecordingViewModel
     * uses `File(parent, name + ".bak")` for its own pre-re-download backup
     * (RecordingViewModel.kt:700), and two mechanisms writing `overwrite = true` backups to the
     * same path with no existence check can silently destroy each other's only recovery copy
     * (#100 follow-up C1). A distinct directory makes that collision structurally impossible
     * regardless of what naming scheme either side picks for the filename itself.
     */
    internal fun backupFileFor(inputFile: File): File =
        File(File(inputFile.parentFile, ".audio_repair_backups"), "${inputFile.name}.orig")

    private fun fsync(file: File) {
        FileInputStream(file).use { it.fd.sync() }
    }

    /** Best-effort directory fsync so the atomic rename's directory-entry update is durable too. */
    private fun fsyncQuietly(dir: File?) {
        if (dir == null) return
        try {
            FileInputStream(dir).use { it.fd.sync() }
        } catch (_: IOException) {
            // Not all platforms allow opening a directory as a stream (e.g. Windows); this is a
            // best-effort durability improvement, not a correctness requirement.
        }
    }
}
