package com.daedalus.notes

import com.daedalus.notes.util.SafeFilename
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SafeFilename.isSafe] is a pure character allowlist ([A-Za-z0-9._-]+), which lets `.`, `..`,
 * and any run of dots (`....`) pass — they are all path-traversal shaped, and none is ever a
 * legitimate recording filename (BLE names are 14-digit timestamps, imports carry a real
 * extension). See LOW-1 in the #99-family review: BackupManager used to layer its own
 * `&& filename != "." && filename != ".."` on top of [SafeFilename.isSafe] because the shared
 * allowlist didn't reject them, leaving every other call site (MainActivity's DELETE_FILE,
 * REDOWNLOAD, PROBE_DELETE branches) without that protection. The rejection now lives in
 * [SafeFilename] itself so every call site gets it uniformly.
 */
class SafeFilenameTest {

    @Test
    fun `rejects dot-only names`() {
        assertFalse(SafeFilename.isSafe("."))
        assertFalse(SafeFilename.isSafe(".."))
        assertFalse(SafeFilename.isSafe("...."))
    }

    @Test
    fun `accepts legitimate recording and import filenames`() {
        // 14-digit BLE device filenames (see project_ble_protocol notes).
        assertTrue(SafeFilename.isSafe("20260812102746"))
        // Extensionless names.
        assertTrue(SafeFilename.isSafe("recording_01"))
        // Imported filenames with extensions.
        assertTrue(SafeFilename.isSafe("foo.mp3"))
        assertTrue(SafeFilename.isSafe("bar.m4a"))
    }
}
