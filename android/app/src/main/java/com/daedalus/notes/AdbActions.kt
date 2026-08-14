package com.daedalus.notes

/**
 * Single source of truth for the debug-only ADB test-harness action set.
 *
 * [HANDLED] is every action MainActivity's dynamic receiver has a `when` branch for.
 * [REGISTERED] is the subset actually wired onto the dynamic IntentFilter (and mirrored in
 * AndroidManifest.xml's `.AdbReceiver` declaration). Building the IntentFilter from
 * [REGISTERED] instead of a hand-typed list makes "a handler with no registration" (see #99)
 * impossible to express by omission.
 *
 * REPAIR_FILE previously lived here, quarantined (present in [HANDLED], withheld from
 * [REGISTERED]) pending a fix for #100. Two adversarial cold reviews of AudioRepairEngine's fix
 * each found a further data-integrity problem (the first forced a `.bak`-collision and scanner
 * rework; the second found an unbounded homogeneous-run carve-out and a missing excision
 * ceiling), after which the engine was deleted rather than repaired further — see #100's final
 * resolution. There is no longer a quarantined action to track.
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
    const val SET_SPEED = "com.daedalus.notes.SET_SPEED"
    const val FORMAT_PARAGRAPHS = "com.daedalus.notes.FORMAT_PARAGRAPHS"
    const val SEARCH_FTS = "com.daedalus.notes.SEARCH_FTS"
    const val DB_PRAGMA = "com.daedalus.notes.DB_PRAGMA"

    /** Every action the dynamic receiver's `when` block handles. */
    val HANDLED: List<String> = listOf(
        SYNC, PROBE, PROBE2, PROBE_DELETE, PROBE_UPLOAD, START_RECORDING, STOP_RECORDING,
        ANALYZE, REDOWNLOAD, DELETE_FILE, ADD_CALENDAR, SET_SPEED, FORMAT_PARAGRAPHS,
        SEARCH_FTS, DB_PRAGMA
    )

    /**
     * Actions registered on the dynamic IntentFilter in MainActivity.onCreate — the sole source
     * of truth for that filter (the manifest's `.AdbReceiver` declaration carries no
     * intent-filter of its own as of #104). Equal to [HANDLED] today (nothing is currently
     * withheld), but the two stay separate names on purpose: they are conceptually distinct sets
     * — every branch that exists vs. every action actually wired up — [AdbActionRegistrationTest]
     * asserts against both independently, and a future action that needs quarantining (as
     * REPAIR_FILE once did) only has to be removed from this list, not have the whole
     * handled/registered distinction reintroduced.
     */
    val REGISTERED: List<String> = HANDLED
}
