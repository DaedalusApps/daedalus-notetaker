package com.daedalus.notes.util

/**
 * Shared filename allowlist. Recording filenames come from the FW920 over BLE, from
 * `adb shell am broadcast` extras, and from backup JSON — all attacker-influenced, and all get
 * used to build [java.io.File] paths or look up records directly. This was independently
 * duplicated at three call sites (MainActivity's REPAIR_FILE handler, RecordingViewModel's BLE
 * sync, BackupManager's import) with nothing keeping them in lockstep; see #99.
 */
object SafeFilename {
    private val ALLOWED_CHARS = Regex("[A-Za-z0-9._-]+")

    /** True if [name] is non-blank and contains only letters, digits, `.`, `_`, `-`. */
    fun isSafe(name: String): Boolean = name.isNotBlank() && name.matches(ALLOWED_CHARS)
}
