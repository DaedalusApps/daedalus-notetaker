package com.daedalus.notes

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structural guard against the class of bug in #99: MainActivity's debug-only ADB
 * BroadcastReceiver has a `when` branch (a "handled" action) with no matching entry in the
 * dynamic IntentFilter built in onCreate (a "registered" action), so the branch can never run.
 * A third, separately-maintained action list on the manifest's `.AdbReceiver` declaration can
 * drift from both.
 *
 * This test does not hardcode the expected action sets — it parses them out of the actual
 * production source files (MainActivity.kt and AndroidManifest.xml) so a future handler added
 * without a matching registration fails this test for the right reason, instead of the test
 * only ever checking itself.
 *
 * The one intentional exception is `REPAIR_FILE`: AudioRepairEngine.repairMp3File destructively
 * truncates audio with no backup (see the #99 follow-up audit), so that handler must stay
 * reachable in source (for a future fix) but unreachable via the receiver — i.e. "quarantined"
 * rather than registered. [QUARANTINED_ACTIONS] names that exception explicitly so it can only
 * ever be this one action, on purpose, not an accident.
 */
class AdbActionRegistrationTest {

    private val moduleRoot: File by lazy { findModuleRoot() }
    private val mainActivitySource: String by lazy {
        File(moduleRoot, "src/main/java/com/daedalus/notes/MainActivity.kt").readText()
    }

    /** Actions the dynamic receiver's `when (intent?.action)` block has a branch for. */
    private val handledActions: Set<String> by lazy {
        extractReceiverWhenBlock(mainActivitySource).let { whenBlock ->
            WHEN_BRANCH_ACTION.findAll(whenBlock).map { it.groupValues[1] }.toSet()
        }
    }

    /** Actions registered on the dynamic IntentFilter built in onCreate. */
    private val registeredDynamicActions: Set<String> by lazy {
        extractIntentFilterBlock(mainActivitySource).let { filterBlock ->
            ADD_ACTION_CALL.findAll(filterBlock).map { it.groupValues[1] }.toSet()
        }
    }

    /** Actions on the manifest's `.AdbReceiver` `<intent-filter>`. */
    private val registeredManifestActions: Set<String> by lazy {
        parseManifestAdbReceiverActions(File(moduleRoot, "src/main/AndroidManifest.xml"))
    }

    /** The only action allowed to be handled but deliberately not registered. See class doc. */
    private val quarantinedActions: Set<String> = setOf("com.daedalus.notes.REPAIR_FILE")

    @Test
    fun `every handled action except the quarantined set is registered on the dynamic IntentFilter`() {
        val expectedRegistered = handledActions - quarantinedActions
        assertEquals(
            "Handled actions minus the quarantined set must equal the dynamic IntentFilter's " +
                "registered actions. A handler with no registration is unreachable (the #99 bug); " +
                "an action registered without being handled is also a mismatch.",
            expectedRegistered,
            registeredDynamicActions
        )
    }

    @Test
    fun `manifest AdbReceiver action set matches the dynamic receiver's registered set`() {
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver <intent-filter> must list exactly the actions " +
                "the dynamic receiver registers, or delivery via -n can silently diverge from " +
                "what MainActivity actually handles.",
            registeredDynamicActions,
            registeredManifestActions
        )
    }

    @Test
    fun `REPAIR_FILE is quarantined and not registered anywhere`() {
        assertTrue(
            "com.daedalus.notes.REPAIR_FILE" in quarantinedActions
        )
        assertTrue(
            "REPAIR_FILE must not be on the dynamic IntentFilter (AudioRepairEngine is unsafe; " +
                "see class doc)",
            "com.daedalus.notes.REPAIR_FILE" !in registeredDynamicActions
        )
        assertTrue(
            "REPAIR_FILE must not be on the manifest's AdbReceiver intent-filter either",
            "com.daedalus.notes.REPAIR_FILE" !in registeredManifestActions
        )
    }

    companion object {
        private val WHEN_BRANCH_ACTION = Regex(""""(com\.daedalus\.notes\.[A-Z_]+)"\s*->""")
        private val ADD_ACTION_CALL = Regex("""addAction\("(com\.daedalus\.notes\.[A-Z_]+)"\)""")

        /** Slices out just the adbReceiver's `onReceive` `when` block, so a match can't
         *  accidentally pick up an unrelated `when` elsewhere in the file. */
        private fun extractReceiverWhenBlock(source: String): String {
            val start = source.indexOf("when (intent?.action)")
            require(start >= 0) { "Could not find 'when (intent?.action)' in MainActivity.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Slices out just the `IntentFilter().apply { ... }` block built in onCreate. */
        private fun extractIntentFilterBlock(source: String): String {
            val start = source.indexOf("IntentFilter().apply")
            require(start >= 0) { "Could not find 'IntentFilter().apply' in MainActivity.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Returns the text between a `{` at [openBraceIndex] and its matching `}`. */
        private fun balancedBraceBlock(source: String, openBraceIndex: Int): String {
            var depth = 0
            for (i in openBraceIndex until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(openBraceIndex, i + 1)
                    }
                }
            }
            error("Unbalanced braces starting at index $openBraceIndex")
        }

        private fun parseManifestAdbReceiverActions(manifestFile: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as Element
                if (receiver.getAttribute("android:name") != ".AdbReceiver") continue
                val actions = receiver.getElementsByTagName("action")
                return (0 until actions.length)
                    .map { (actions.item(it) as Element).getAttribute("android:name") }
                    .toSet()
            }
            error("Could not find a <receiver android:name=\".AdbReceiver\"> in AndroidManifest.xml")
        }

        /** Walks up from the test JVM's working directory to find the `:app` module root
         *  (the directory containing `src/main/java/com/daedalus/notes/MainActivity.kt`). */
        private fun findModuleRoot(): File {
            var dir: File? = File(".").canonicalFile
            repeat(6) {
                val candidate = dir?.let { File(it, "src/main/java/com/daedalus/notes/MainActivity.kt") }
                if (candidate != null && candidate.exists()) return dir!!
                dir = dir?.parentFile
            }
            error("Could not locate the :app module root (looked for src/main/java/com/daedalus/notes/MainActivity.kt) from ${File(".").canonicalPath}")
        }
    }
}
