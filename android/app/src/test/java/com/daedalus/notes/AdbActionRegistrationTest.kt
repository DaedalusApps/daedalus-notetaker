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
 */
class AdbActionRegistrationTest {

    private val moduleRoot: File by lazy { findModuleRoot() }
    private val mainActivitySource: String by lazy {
        File(moduleRoot, "src/main/java/com/daedalus/notes/MainActivity.kt").readText()
    }
    /** Comments stripped so a comment claiming to do the right thing can't satisfy a
     *  `.contains(...)` check that was meant to verify real code. See HIGH-1 in the #99 review. */
    private val strippedMainActivitySource: String by lazy { stripComments(mainActivitySource) }
    private val receiverWhenBlock: String by lazy { extractReceiverWhenBlock(strippedMainActivitySource) }
    private val onCreateSource: String by lazy {
        strippedMainActivitySource.substringAfter("override fun onCreate")
    }

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
        // A comment mentioning AdbActions.REGISTERED must not satisfy this — see HIGH-1 in the
        // #99 review, which broke a `.contains(...)`-only version of this check with exactly
        // that comment plus a hand-typed addAction("...") list underneath it.
        assertTrue(
            "onCreate must build the debug IntentFilter by iterating AdbActions.REGISTERED " +
                "(the single source of truth) rather than a separately hand-typed action list. " +
                "(Checked on comment-stripped source.)",
            onCreateSource.contains("AdbActions.REGISTERED")
        )
    }

    @Test
    fun `MainActivity contains no hand-typed addAction string literal`() {
        // The IntentFilter must be built exclusively from AdbActions.REGISTERED. A hand-typed
        // addAction("com.daedalus.notes.X") anywhere in the file — even alongside a correct
        // AdbActions.REGISTERED loop — reopens the #99 hole: it can register an action that
        // AdbActions never sanctioned, silently drifting the receiver out of sync with the
        // single source of truth the other checks in this file assume it matches.
        val literalAddActionCalls = Regex("""addAction\(\s*"[^"]*"""")
            .findAll(strippedMainActivitySource)
            .map { it.value }
            .toList()
        assertTrue(
            "MainActivity.kt must call addAction(...) only via AdbActions.REGISTERED.forEach — " +
                "found hand-typed literal call(s): $literalAddActionCalls",
            literalAddActionCalls.isEmpty()
        )
    }

    @Test
    fun `the debug IntentFilter registration is gated by BuildConfig DEBUG`() {
        // Deleting the `if (BuildConfig.DEBUG)` wrapper would ship every ADB trigger —
        // including hardware DELETE_FILE — to production users on an exported receiver.
        val debugGateBlock = extractDebugGateBlock(strippedMainActivitySource)
        assertTrue(
            "The BuildConfig.DEBUG-gated block in onCreate must build the filter from " +
                "AdbActions.REGISTERED and register adbReceiver with it.",
            debugGateBlock.contains("AdbActions.REGISTERED") &&
                debugGateBlock.contains("registerReceiver(this, adbReceiver, filter")
        )
    }

    @Test
    fun `the dynamic receiver ignores broadcasts AdbReceiver has not forwarded`() {
        // This only checks the SHAPE of the fix for #99 review MEDIUM-1 (double dispatch): that
        // onReceive checks the "_forwarded" extra and returns early before the when-block, for
        // every action uniformly. Whether this actually prevents a double dispatch depends on
        // Android's broadcast-matching semantics — an implicit `-a ACTION` broadcast reaching
        // both the manifest-registered AdbReceiver and this dynamically registered receiver in
        // parallel — which a plain JVM unit test has no way to exercise (no real broadcast
        // dispatcher, and constructing this receiver at all requires a live MainActivity with
        // its ViewModels, BLE manager and Compose content). That is verified on-device via
        // logcat line counts per trigger, with and without -n.
        val onReceiveBody = extractOnReceiveBody(strippedMainActivitySource)
        val whenIndex = onReceiveBody.indexOf("when (intent")
        assertTrue("Could not find the when-block inside onReceive", whenIndex >= 0)
        val guardSnippet = onReceiveBody.substring(0, whenIndex)
        assertTrue(
            "onReceive must check the \"_forwarded\" extra before dispatching",
            guardSnippet.contains("_forwarded")
        )
        assertTrue(
            "The \"_forwarded\" check must return early (not just log) when the broadcast was " +
                "not forwarded by AdbReceiver, or an implicit -a ACTION broadcast still reaches " +
                "the when-block directly and double-dispatches.",
            guardSnippet.contains("return")
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

    companion object {
        /** "com.daedalus.notes.SET_SPEED" -> "SET_SPEED", matching AdbActions' own naming. */
        private fun constantNameOf(action: String): String = action.substringAfterLast('.')

        /**
         * Strips `//` line comments and `/* */` block comments from Kotlin source so source-text
         * assertions can't be satisfied by a comment instead of real code. Safe for MainActivity.kt
         * specifically: it contains no string literal with `//` inside it (verified by inspection),
         * so a naive "everything after `//` on a line" strip does not corrupt any string.
         */
        private fun stripComments(source: String): String {
            val noBlockComments = source.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            return noBlockComments.lineSequence().joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
        }

        /** Slices out the adbReceiver's whole `onReceive` function body (guard clause and all). */
        private fun extractOnReceiveBody(source: String): String {
            val start = source.indexOf("override fun onReceive")
            require(start >= 0) { "Could not find 'override fun onReceive' in MainActivity.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Slices out just the adbReceiver's `onReceive` `when` block, so a match can't
         *  accidentally pick up an unrelated `when` elsewhere in the file. */
        private fun extractReceiverWhenBlock(source: String): String {
            val start = source.indexOf("when (intent")
            require(start >= 0) { "Could not find the when-block in MainActivity.kt's onReceive" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Slices out the `if (BuildConfig.DEBUG) { ... }` block in onCreate. Throws (failing the
         *  test) if that gate has been removed entirely. */
        private fun extractDebugGateBlock(source: String): String {
            val start = source.indexOf("if (BuildConfig.DEBUG)")
            require(start >= 0) { "Could not find 'if (BuildConfig.DEBUG)' in MainActivity.kt" }
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
