package com.daedalus.notes.util

/**
 * Shared filename allowlist. Recording filenames come from the FW920 over BLE, from
 * `adb shell am broadcast` extras, and from backup JSON — all attacker-influenced, and all get
 * used to build [java.io.File] paths or look up records directly. This was independently
 * duplicated at multiple call sites (RecordingViewModel's BLE sync, BackupManager's import) with
 * nothing keeping them in lockstep; see #99. (A third call site, MainActivity's REPAIR_FILE
 * handler, was deleted along with AudioRepairEngine — see #100's final resolution.)
 *
 * `.`, `..`, and any other name consisting solely of dots (e.g. `....`) are rejected even though
 * they pass the character allowlist below: they are never a legitimate recording or import name
 * (BLE names are 14-digit timestamps; imports carry a real extension) and are path-traversal
 * shaped. This used to be layered on separately by BackupManager alone (see LOW-1 in the #104
 * residual review); it now lives here so every call site gets it uniformly.
 */
object SafeFilename {
    private val ALLOWED_CHARS = Regex("[A-Za-z0-9._-]+")
    private val DOTS_ONLY = Regex("\\.+")

    /**
     * True if [name] is non-blank, contains only letters, digits, `.`, `_`, `-`, and is not
     * composed entirely of dots (`.`, `..`, `....`, etc).
     */
    fun isSafe(name: String): Boolean =
        name.isNotBlank() && name.matches(ALLOWED_CHARS) && !name.matches(DOTS_ONLY)
}
