package com.daedalus.notes

/**
 * Single source of truth for the debug-only ADB test-harness action set.
 *
 * [HANDLED] is every action MainActivity's dynamic receiver has a `when` branch for.
 * [REGISTERED] is the subset actually wired onto the dynamic IntentFilter (and mirrored in
 * AndroidManifest.xml's `.AdbReceiver` declaration) — [HANDLED] minus [QUARANTINED]. Building
 * the IntentFilter from [REGISTERED] instead of a hand-typed list makes "a handler with no
 * registration" (see #99) impossible to express by omission.
 */
object AdbActions {
    const val SYNC = "com.daedalus.notes.SYNC"
    const val PROBE = "com.daedalus.notes.PROBE"
    const val PROBE2 = "com.daedalus.notes.PROBE2"
    const val PROBE_DELETE = "com.daedalus.notes.PROBE_DELETE"
    const val PROBE_UPLOAD = "com.daedalus.notes.PROBE_UPLOAD"
    const val START_RECORDING = "com.daedalus.notes.START_RECORDING"
    const val STOP_RECORDING = "com.daedalus.notes.STOP_RECORDING"
    const val ANALYZE = "com.daedalus.notes.ANALYZE"
    const val REDOWNLOAD = "com.daedalus.notes.REDOWNLOAD"
    const val DELETE_FILE = "com.daedalus.notes.DELETE_FILE"
    const val ADD_CALENDAR = "com.daedalus.notes.ADD_CALENDAR"
    const val REPAIR_FILE = "com.daedalus.notes.REPAIR_FILE"
    const val SET_SPEED = "com.daedalus.notes.SET_SPEED"
    const val FORMAT_SPEAKER = "com.daedalus.notes.FORMAT_SPEAKER"
    const val SEARCH_FTS = "com.daedalus.notes.SEARCH_FTS"

    /**
     * Withheld from [REGISTERED] on purpose: AudioRepairEngine.repairMp3File truncates audio
     * after the first detected gap and overwrites the original file with no backup — a data-loss
     * bug found in a parallel #99 follow-up audit. Arming this trigger would let
     * `adb shell am broadcast` destroy a user's only copy of a recording. Remove an action from
     * here only in the same change that fixes the underlying engine.
     */
    val QUARANTINED: Set<String> = setOf(REPAIR_FILE)

    /** Every action the dynamic receiver's `when` block handles. */
    val HANDLED: List<String> = listOf(
        SYNC, PROBE, PROBE2, PROBE_DELETE, PROBE_UPLOAD, START_RECORDING, STOP_RECORDING,
        ANALYZE, REDOWNLOAD, DELETE_FILE, ADD_CALENDAR, REPAIR_FILE, SET_SPEED, FORMAT_SPEAKER,
        SEARCH_FTS
    )

    /** Actions registered on the dynamic IntentFilter and mirrored in the manifest. */
    val REGISTERED: List<String> = HANDLED.filterNot { it in QUARANTINED }
}
