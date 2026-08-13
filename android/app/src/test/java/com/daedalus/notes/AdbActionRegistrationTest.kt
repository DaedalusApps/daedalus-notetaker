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
 *
 * [AdbActions] is the single source of truth both MainActivity and this test read from. This
 * test does not just assert that object equals itself — it checks that MainActivity's `when`
 * block actually has a branch referencing every [AdbActions.HANDLED] constant, and that onCreate
 * actually builds its IntentFilter from [AdbActions.REGISTERED] rather than a hand-typed list.
 * A handler added without updating [AdbActions] fails one of these for a real reason.
 *
 * This file also guards the #104 fix: AndroidManifest.xml's `.AdbReceiver` declaration must stay
 * `android:exported="true"` (adb shell needs that) but must carry no `<intent-filter>` (an
 * intent-filter would let any other installed app reach it via an IMPLICIT broadcast, including
 * `com.daedalus.notes.DELETE_FILE` which wipes a recording off FW920 hardware), and the dynamic
 * receiver registered in onCreate must use `ContextCompat.RECEIVER_NOT_EXPORTED` since it is only
 * ever reached via `.AdbReceiver`'s in-package forward. This closes both the implicit-broadcast
 * path (no intent-filter) and the explicit-component residual (`.AdbReceiver` now also declares
 * `android:permission="android.permission.DUMP"`, so only a sender holding that
 * signature|privileged|development permission — i.e. adb shell — can deliver to it at all, by
 * either broadcast form).
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

    private val adbReceiverElement: Element by lazy {
        parseManifestAdbReceiverElement(File(moduleRoot, "src/main/AndroidManifest.xml"))
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
    fun `the debug IntentFilter registration uses RECEIVER_NOT_EXPORTED`() {
        // #104: this receiver is only ever reached via AdbReceiver's in-package forward
        // (context.sendBroadcast(Intent(action).setPackage(context.packageName))), which IS
        // delivered to RECEIVER_NOT_EXPORTED receivers. Registering it RECEIVER_EXPORTED lets any
        // third-party app bypass .AdbReceiver entirely and broadcast an IMPLICIT
        // com.daedalus.notes.DELETE_FILE with a spoofed "_forwarded"=true extra straight at it.
        // NOT_EXPORTED closes that implicit path; it does not close the explicit-component path
        // through .AdbReceiver itself, which remains open on debug builds (tracked in #104).
        val debugGateBlock = extractDebugGateBlock(strippedMainActivitySource)
        assertTrue(
            "The BuildConfig.DEBUG-gated block in onCreate must register adbReceiver with " +
                "ContextCompat.RECEIVER_NOT_EXPORTED, not RECEIVER_EXPORTED — this receiver is " +
                "only ever reached via AdbReceiver's same-package forward, and exporting it lets " +
                "any app on the device trigger hardware DELETE_FILE directly.",
            debugGateBlock.contains("ContextCompat.RECEIVER_NOT_EXPORTED")
        )
        assertTrue(
            "The BuildConfig.DEBUG-gated block must not reference ContextCompat.RECEIVER_EXPORTED " +
                "at all, even alongside a correct RECEIVER_NOT_EXPORTED reference — any use of the " +
                "EXPORTED constant here re-exports the receiver to every app on the device.",
            !debugGateBlock.contains("ContextCompat.RECEIVER_EXPORTED")
        )
    }

    @Test
    fun `the dynamic receiver ignores broadcasts AdbReceiver has not forwarded`() {
        // This only checks the SHAPE of the fix for #99 review MEDIUM-1 (double dispatch): that
        // onReceive checks the "_forwarded" extra and returns early before the when-block, for
        // every action uniformly. The original double-dispatch scenario (an implicit `-a ACTION`
        // broadcast matching both the manifest-registered AdbReceiver and this dynamically
        // registered receiver in parallel) is now structurally impossible — the manifest carries
        // no intent-filter — but the guard stays load-bearing: it is the only thing enforcing that
        // this receiver acts solely on AdbReceiver's in-package forward, not a security control.
        // A plain JVM unit test can't exercise real broadcast dispatch (no dispatcher, and
        // constructing this receiver requires a live MainActivity with its ViewModels, BLE
        // manager and Compose content); that is verified on-device via logcat line counts.
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
    fun `manifest AdbReceiver declares no intent-filter`() {
        // #104: an <intent-filter> makes .AdbReceiver reachable via IMPLICIT broadcast from any
        // other installed app — including com.daedalus.notes.DELETE_FILE, which reaches
        // BleManager.deleteFile(filename) and wipes a recording off FW920 hardware. It buys the
        // ADB harness nothing: every documented invocation uses the explicit
        // `-n com.daedalus.notes/.AdbReceiver` form, which bypasses filter matching entirely for
        // a manifest-declared receiver. This closes only the implicit path — .AdbReceiver stays
        // exported="true", so an EXPLICIT-component broadcast from another app on a debug build
        // still reaches it and forwards on to the hardware delete. See #104.
        val intentFilterCount = adbReceiverElement.getElementsByTagName("intent-filter").length
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must have no <intent-filter>. An intent-filter " +
                "reopens implicit third-party delivery to a receiver that can trigger hardware " +
                "file deletion (com.daedalus.notes.DELETE_FILE); adb shell only ever uses the " +
                "explicit -n component form, which does not need one.",
            0,
            intentFilterCount
        )
    }

    @Test
    fun `manifest AdbReceiver stays exported for adb shell delivery`() {
        // adb shell runs as uid 2000; a non-exported manifest receiver has never been reachable
        // from another uid, including adb shell's, on any Android version. This pins the intent
        // so a future edit doesn't silently break the whole harness while fixing #104.
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must keep android:exported=\"true\" — without " +
                "it, adb shell's explicit -n broadcasts stop being delivered and the whole ADB " +
                "test harness breaks.",
            "true",
            adbReceiverElement.getAttribute("android:exported")
        )
    }

    @Test
    fun `manifest AdbReceiver requires DUMP permission from the sender`() {
        // #104: exported="true" alone still lets any app on a debug build deliver an
        // EXPLICIT-component broadcast straight at .AdbReceiver, which forwards in-package to a
        // dynamic receiver that reaches BleManager.deleteFile and wipes a recording off FW920
        // hardware. android:permission requires the SENDER hold the named permission — DUMP is
        // signature|privileged|development, so com.android.shell (adb shell, uid 2000) holds it
        // but a normal third-party app cannot be granted it without adb access it would already
        // have to have.
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must declare " +
                "android:permission=\"android.permission.DUMP\". Without it, any app on a debug " +
                "build can deliver an EXPLICIT-component broadcast to a receiver that reaches " +
                "hardware file deletion (com.daedalus.notes.DELETE_FILE) via the in-package forward.",
            "android.permission.DUMP",
            adbReceiverElement.getAttribute("android:permission")
        )
    }

    @Test
    fun `DELETE_FILE, REDOWNLOAD and PROBE_DELETE branches guard filename with SafeFilename`() {
        // #104: filenames in these broadcasts are attacker-influenced (see SafeFilename's own
        // KDoc) and each branch reaches destructive hardware/local-file operations
        // (BleManager.deleteFile, RecordingViewModel.redownloadAndAnalyze which deletes the local
        // file before re-downloading, and BleManager.probeDeleteCmds which brute-forces CMD
        // 0x0D-0x17 at the FW920 with the filename until the file disappears — see MEDIUM-1 in
        // the follow-up review). Extract each branch's own text rather than searching the whole
        // when-block so a SafeFilename reference in some other branch can't satisfy this.
        val deleteFileBranch = extractWhenBranch(receiverWhenBlock, "AdbActions.DELETE_FILE")
        val redownloadBranch = extractWhenBranch(receiverWhenBlock, "AdbActions.REDOWNLOAD")
        val probeDeleteBranch = extractWhenBranch(receiverWhenBlock, "AdbActions.PROBE_DELETE")
        assertTrue(
            "The AdbActions.DELETE_FILE branch must guard its filename with SafeFilename.isSafe " +
                "before reaching BleManager.deleteFile.",
            deleteFileBranch.contains("SafeFilename")
        )
        assertTrue(
            "The AdbActions.REDOWNLOAD branch must guard its filename with SafeFilename.isSafe " +
                "before reaching redownloadAndAnalyze, which deletes the local file before " +
                "re-downloading.",
            redownloadBranch.contains("SafeFilename")
        )
        assertTrue(
            "The AdbActions.PROBE_DELETE branch must guard its filename with SafeFilename.isSafe " +
                "before reaching probeDeleteCmds, which brute-forces CMD 0x0D-0x17 at the FW920 " +
                "with the filename.",
            probeDeleteBranch.contains("SafeFilename")
        )
    }

    @Test
    fun `no variant manifest other than src main reintroduces AdbReceiver`() {
        // #104: this test's other manifest checks only parse src/main/AndroidManifest.xml. A
        // src/debug/AndroidManifest.xml (exactly the variant this debug-only receiver lives in)
        // or a library manifest could declare its own <receiver android:name=".AdbReceiver">
        // with an <intent-filter>, which the manifest merger would combine into the installed
        // app, reopening implicit third-party delivery — while `manifest AdbReceiver declares no
        // intent-filter` above keeps passing because it never looks past src/main.
        val srcDir = File(moduleRoot, "src")
        val offendingManifests = srcDir.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filterNot { it.canonicalFile == File(moduleRoot, "src/main/AndroidManifest.xml").canonicalFile }
            .filter { manifestDeclaresAdbReceiver(it) }
            .map { it.path }
            .toList()
        assertTrue(
            "Found AndroidManifest.xml file(s) other than src/main declaring .AdbReceiver: " +
                "$offendingManifests. A variant or library manifest can merge an <intent-filter> " +
                "back onto .AdbReceiver via manifest merger and defeat the #104 guard, even though " +
                "src/main/AndroidManifest.xml itself has none.",
            offendingManifests.isEmpty()
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

        /** Slices out a single `AdbActions.X -> { ... }` branch's body from a `when`-block's text,
         *  so a check for one branch can't be satisfied by a match anywhere else in the block. */
        private fun extractWhenBranch(whenBlock: String, branchLabel: String): String {
            val labelStart = whenBlock.indexOf("$branchLabel ->")
            require(labelStart >= 0) { "Could not find branch '$branchLabel ->' in the when-block" }
            val openBrace = whenBlock.indexOf('{', labelStart)
            require(openBrace >= 0) { "Branch '$branchLabel' has no '{' body" }
            return balancedBraceBlock(whenBlock, openBrace)
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

        /** Locates the `<receiver android:name=".AdbReceiver">` element in AndroidManifest.xml so
         *  tests can assert on its shape directly (exported attribute, absence of intent-filter)
         *  rather than on a scraped action list — see #104. */
        private fun parseManifestAdbReceiverElement(manifestFile: File): Element {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as Element
                if (receiver.getAttribute("android:name") == ".AdbReceiver") return receiver
            }
            error("Could not find a <receiver android:name=\".AdbReceiver\"> in AndroidManifest.xml")
        }

        /** True if [manifestFile] declares a `<receiver android:name=".AdbReceiver">`. Used by
         *  the LOW-2 guard to catch a variant manifest reintroducing it outside src/main. */
        private fun manifestDeclaresAdbReceiver(manifestFile: File): Boolean {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as Element
                if (receiver.getAttribute("android:name") == ".AdbReceiver") return true
            }
            return false
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
