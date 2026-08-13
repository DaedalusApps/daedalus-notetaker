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
 * [AdbActions] is the single source of truth both MainActivity and this test read from. This
 * test does not just assert that object equals itself — it checks that MainActivity's `when`
 * block actually has a branch referencing every [AdbActions.HANDLED] constant, that onCreate
 * actually builds its IntentFilter from [AdbActions.REGISTERED] rather than a hand-typed list,
 * and that AndroidManifest.xml (a separate file, parsed independently) lists exactly
 * [AdbActions.REGISTERED]. A handler added without updating [AdbActions], or a manifest edited
 * out of step with it, fails one of these for a real reason.
 *
 * The one intentional exception is `REPAIR_FILE`: AudioRepairEngine.repairMp3File destructively
 * truncates audio with no backup (see the #99 follow-up audit), so that handler must stay
 * reachable in source (for a future fix) but unreachable via the receiver — "quarantined" via
 * [AdbActions.QUARANTINED] rather than silently dropped.
 */
class AdbActionRegistrationTest {

    private val moduleRoot: File by lazy { findModuleRoot() }
    private val mainActivitySource: String by lazy {
        File(moduleRoot, "src/main/java/com/daedalus/notes/MainActivity.kt").readText()
    }
    private val receiverWhenBlock: String by lazy { extractReceiverWhenBlock(mainActivitySource) }
    private val onCreateSource: String by lazy { mainActivitySource.substringAfter("override fun onCreate") }

    private val registeredManifestActions: Set<String> by lazy {
        parseManifestAdbReceiverActions(File(moduleRoot, "src/main/AndroidManifest.xml"))
    }

    @Test
    fun `every AdbActions HANDLED constant has a when branch in MainActivity`() {
        val missingBranches = AdbActions.HANDLED.filterNot { action ->
            receiverWhenBlock.contains("AdbActions.${constantNameOf(action)} ->")
        }
        assertTrue(
            "MainActivity's adbReceiver when-block has no branch for: $missingBranches. " +
                "Every action in AdbActions.HANDLED must be dispatched via its AdbActions " +
                "constant so it stays covered by this check.",
            missingBranches.isEmpty()
        )
    }

    @Test
    fun `when block has no stray branches outside AdbActions HANDLED`() {
        val branchCount = Regex("""AdbActions\.[A-Z0-9_]+\s*->""").findAll(receiverWhenBlock).count()
        assertEquals(
            "The when-block's branch count must match AdbActions.HANDLED.size exactly — a " +
                "branch using a raw string literal instead of an AdbActions constant would be " +
                "invisible to the previous check.",
            AdbActions.HANDLED.size,
            branchCount
        )
    }

    @Test
    fun `onCreate builds the dynamic IntentFilter from AdbActions REGISTERED`() {
        assertTrue(
            "onCreate must build the debug IntentFilter by iterating AdbActions.REGISTERED " +
                "(the single source of truth) rather than a separately hand-typed action list.",
            onCreateSource.contains("AdbActions.REGISTERED")
        )
    }

    @Test
    fun `manifest AdbReceiver action set matches AdbActions REGISTERED`() {
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver <intent-filter> must list exactly " +
                "AdbActions.REGISTERED, or delivery via -n can silently diverge from what " +
                "MainActivity actually handles.",
            AdbActions.REGISTERED.toSet(),
            registeredManifestActions
        )
    }

    @Test
    fun `REPAIR_FILE is quarantined and not registered anywhere`() {
        assertTrue(AdbActions.REPAIR_FILE in AdbActions.QUARANTINED)
        assertTrue(
            "REPAIR_FILE must not be on AdbActions.REGISTERED (AudioRepairEngine is unsafe; " +
                "see class doc)",
            AdbActions.REPAIR_FILE !in AdbActions.REGISTERED
        )
        assertTrue(
            "REPAIR_FILE must not be on the manifest's AdbReceiver intent-filter either",
            AdbActions.REPAIR_FILE !in registeredManifestActions
        )
    }

    companion object {
        /** "com.daedalus.notes.SET_SPEED" -> "SET_SPEED", matching AdbActions' own naming. */
        private fun constantNameOf(action: String): String = action.substringAfterLast('.')

        /** Slices out just the adbReceiver's `onReceive` `when` block, so a match can't
         *  accidentally pick up an unrelated `when` elsewhere in the file. */
        private fun extractReceiverWhenBlock(source: String): String {
            val start = source.indexOf("when (intent?.action)")
            require(start >= 0) { "Could not find 'when (intent?.action)' in MainActivity.kt" }
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
