package com.daedalus.notes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.ai.AI_TEXT_BUDGET_DEFAULT
import com.daedalus.notes.ai.normalizeTodoText
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.data.model.TodoItem
import com.daedalus.notes.ui.screens.TODO_LOOKBACK_HOURS_DEFAULT
import com.daedalus.notes.viewmodel.MAX_RECORDING_MINUTES_DEFAULT
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    private lateinit var context: Context

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

    private fun prefs() =
        context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean prefs store for every test.
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun v2ExportRoundTrip_restoresRecordingsTodosAndSettings() = runBlocking {
        val source = newDb()
        source.recordingDao().upsert(
            Recording(filename = "note1.mp3", title = "First", transcript = "hello", category = 2, deviceSerial = "K9THA22775")
        )
        source.recordingDao().upsert(
            Recording(filename = "note2.mp3", title = "Second", transcript = "world", topics = listOf("a", "b"))
        )
        source.todoDao().insert(TodoItem(text = "Buy milk", isDone = false, sourceFilename = "note1.mp3"))
        source.todoDao().insert(TodoItem(text = "Call Bob", isDone = true, isAiGenerated = true))

        prefs().edit()
            .putBoolean("use_bluetooth_mic", true)
            .putBoolean("auto_process", true)
            .putString("custom_prompt", "my prompt")
            .putInt("backup_max_count", 7)
            .putInt("max_recording_minutes", 60)
            .putInt("ai_text_budget_chars", 9_000)
            .putBoolean("conversation_tts_enabled", true)
            .putFloat("conversation_tts_rate", 1.5f)
            .putString("conversation_tts_voice", "Voice 2")
            .putBoolean("conversation_instant_send", true)
            .putBoolean("conversation_auto_listen", true)
            .commit()

        val json = BackupManager(context, source).buildBackupJson()
        assertEquals(2, json.getInt("backupVersion"))
        assertTrue(json.has("exportedAt"))
        assertEquals(2, json.getJSONArray("recordings").length())
        assertEquals(2, json.getJSONArray("todos").length())
        source.close()

        // Fresh DB + wiped prefs simulate a restore on another install.
        prefs().edit().clear().commit()
        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(json)

        assertEquals(2, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(setOf("note1.mp3", "note2.mp3"), recordings.map { it.filename }.toSet())
        assertEquals("First", recordings.first { it.filename == "note1.mp3" }.title)
        assertEquals("K9THA22775", recordings.first { it.filename == "note1.mp3" }.deviceSerial)
        assertEquals(null, recordings.first { it.filename == "note2.mp3" }.deviceSerial)

        val todos = target.todoDao().getAll()
        assertEquals(setOf("Buy milk", "Call Bob"), todos.map { it.text }.toSet())
        assertTrue(todos.first { it.text == "Call Bob" }.isDone)

        assertTrue(prefs().getBoolean("use_bluetooth_mic", false))
        assertTrue(prefs().getBoolean("auto_process", false))
        assertEquals("my prompt", prefs().getString("custom_prompt", null))
        assertEquals(7, prefs().getInt("backup_max_count", 0))
        assertEquals(60, prefs().getInt("max_recording_minutes", 0))
        assertEquals(9_000, prefs().getInt("ai_text_budget_chars", 0))
        assertTrue(prefs().getBoolean("conversation_tts_enabled", false))
        assertEquals(1.5f, prefs().getFloat("conversation_tts_rate", -1f))
        assertEquals("Voice 2", prefs().getString("conversation_tts_voice", null))
        assertTrue(prefs().getBoolean("conversation_instant_send", false))
        assertTrue(prefs().getBoolean("conversation_auto_listen", false))
        target.close()
    }

    // A corrupt/hand-edited backup must not restore a speech rate the TTS engine silently ignores
    // (non-numbers parse as NaN; 0 and absurd values are rejected by setSpeechRate).
    @Test
    fun v2Import_outOfRangeOrNonNumericTtsRate_isClampedToTheOfferedRange() = runBlocking {
        val db = newDb()
        val manager = BackupManager(context, db)

        manager.importFromJson(backupWithTtsRate("fast"))
        assertEquals(1.0f, prefs().getFloat("conversation_tts_rate", -1f))

        manager.importFromJson(backupWithTtsRate(0.0))
        assertEquals(0.75f, prefs().getFloat("conversation_tts_rate", -1f))

        manager.importFromJson(backupWithTtsRate(99.0))
        assertEquals(2.0f, prefs().getFloat("conversation_tts_rate", -1f))

        db.close()
    }

    private fun backupWithTtsRate(rate: Any): JSONObject = JSONObject().apply {
        put("backupVersion", 2)
        put("recordings", JSONArray())
        put("settings", JSONObject().apply { put("conversation_tts_rate", rate) })
    }

    @Test
    fun v1Import_recordingsOnly_importsWithoutError() = runBlocking {
        val v1 = JSONObject().apply {
            put("backupVersion", 1)
            put("exportedAt", 111L)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "legacy.mp3")
                    put("title", "Legacy")
                    put("transcript", "old transcript")
                })
            })
            // No "todos" and no "settings" keys.
        }

        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(v1)

        assertEquals(1, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(listOf("legacy.mp3"), recordings.map { it.filename })
        assertTrue(target.todoDao().getAll().isEmpty())
        target.close()
    }

    @Test
    fun import_skipsInvalidFilenames_pathTraversalSafe() = runBlocking {
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply { put("filename", "../evil.mp3"); put("title", "Bad") })
                put(JSONObject().apply { put("filename", "good.mp3"); put("title", "Good") })
            })
        }

        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(json)

        assertEquals(1, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(listOf("good.mp3"), recordings.map { it.filename })
        target.close()
    }

    @Test
    fun todoImport_isIdempotent_dedupByNormalizedText() = runBlocking {
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "Buy Milk!"); put("isDone", false); put("createdAt", 1L) })
                put(JSONObject().apply { put("text", "Call   Bob"); put("isDone", false); put("createdAt", 2L) })
            })
        }

        val target = newDb()
        val bm = BackupManager(context, target)
        bm.importFromJson(json)
        bm.importFromJson(json) // second import with slightly different-cased duplicate text

        // "buy milk" already exists; re-importing must not duplicate.
        val todos = target.todoDao().getAll()
        assertEquals(2, todos.size)
        target.close()
    }

    // Every settings key is written only from a UI save/toggle callback, so a backup taken before
    // the user touches a control used to omit that setting entirely — a fresh install exported
    // `settings: {}`. The export must carry the *effective* value: stored if present, else the
    // documented default that the readers use.
    @Test
    fun buildBackupJson_untouchedSettings_exportsEffectiveDefaults() = runBlocking {
        val db = newDb()

        val settings = BackupManager(context, db).buildBackupJson().getJSONObject("settings")

        assertFalse(settings.getBoolean("use_bluetooth_mic"))
        assertFalse(settings.getBoolean("auto_process"))
        assertEquals(TODO_LOOKBACK_HOURS_DEFAULT, settings.getLong("todo_lookback_hours"))
        assertEquals(BackupPrefs.DEFAULT_INTERVAL_HOURS, settings.getLong(BackupPrefs.INTERVAL_HOURS))
        assertEquals(BackupPrefs.DEFAULT_MAX_COUNT, settings.getInt(BackupPrefs.MAX_COUNT))
        assertEquals(MAX_RECORDING_MINUTES_DEFAULT, settings.getInt("max_recording_minutes"))
        assertEquals(AI_TEXT_BUDGET_DEFAULT, settings.getInt("ai_text_budget_chars"))
        assertFalse(settings.getBoolean("conversation_tts_enabled"))
        assertEquals(1.0, settings.getDouble("conversation_tts_rate"), 0.0001)
        assertFalse(settings.getBoolean("conversation_instant_send"))
        assertFalse(settings.getBoolean("conversation_auto_listen"))
        db.close()
    }

    // Absence of these two is meaningful state — custom_prompt absent means "use the built-in
    // DEFAULT_PROMPT", conversation_tts_voice absent means "system default voice". Exporting a
    // fabricated default would convert an unset state into an explicit setting on restore.
    @Test
    fun buildBackupJson_untouchedNullableSettings_stayAbsent() = runBlocking {
        val db = newDb()

        val settings = BackupManager(context, db).buildBackupJson().getJSONObject("settings")

        assertFalse(settings.has("custom_prompt"))
        assertFalse(settings.has("conversation_tts_voice"))
        db.close()
    }

    @Test
    fun buildBackupJson_storedSettings_winOverDefaults() = runBlocking {
        val db = newDb()
        prefs().edit()
            .putBoolean("use_bluetooth_mic", true)
            .putLong("todo_lookback_hours", 24L)
            .putInt("ai_text_budget_chars", 9_000)
            .putFloat("conversation_tts_rate", 1.5f)
            .putString("conversation_tts_voice", "Voice 2")
            .commit()

        val settings = BackupManager(context, db).buildBackupJson().getJSONObject("settings")

        assertTrue(settings.getBoolean("use_bluetooth_mic"))
        assertEquals(24L, settings.getLong("todo_lookback_hours"))
        assertEquals(9_000, settings.getInt("ai_text_budget_chars"))
        assertEquals(1.5, settings.getDouble("conversation_tts_rate"), 0.0001)
        assertEquals("Voice 2", settings.getString("conversation_tts_voice"))
        db.close()
    }

    // The Long keys must round-trip as Long: a bare Kotlin literal is an Int, and storing an Int
    // under these keys makes the readers' getLong() throw ClassCastException.
    @Test
    fun buildBackupJson_defaultLongKeys_roundTripAsLongNotInt() = runBlocking {
        val db = newDb()
        val json = BackupManager(context, db).buildBackupJson()

        prefs().edit().clear().commit()
        BackupManager(context, db).importFromJson(json)

        assertEquals(TODO_LOOKBACK_HOURS_DEFAULT, prefs().getLong("todo_lookback_hours", -1L))
        assertEquals(
            BackupPrefs.DEFAULT_INTERVAL_HOURS,
            prefs().getLong(BackupPrefs.INTERVAL_HOURS, -1L)
        )
        db.close()
    }

    // A split recording's parts hold their own Gemma analysis, which the parent's rollup does
    // not contain. Exporting parents only silently drops it from every backup.
    @Test
    fun splitRecording_partsAndTheirAnalysis_surviveExportAndRestore() = runBlocking {
        val source = newDb()
        source.recordingDao().upsert(
            Recording(filename = "long1", title = "Long meeting", transcript = "p1 p2")
        )
        source.recordingDao().upsert(
            Recording(
                filename = "long1_p1", title = "Part one", shortSummary = "first half",
                mindMap = "map1", topics = listOf("budget"),
                parentFilename = "long1", partIndex = 1
            )
        )
        source.recordingDao().upsert(
            Recording(
                filename = "long1_p2", title = "Part two", shortSummary = "second half",
                mindMap = "map2", topics = listOf("hiring"),
                parentFilename = "long1", partIndex = 2
            )
        )

        val json = BackupManager(context, source).buildBackupJson()
        assertEquals(3, json.getJSONArray("recordings").length())
        source.close()

        val target = newDb()
        assertEquals(3, BackupManager(context, target).importFromJson(json))

        // The parent alone appears at top level; parts stay nested under it.
        val topLevel = target.recordingDao().getAllFlow().first()
        assertEquals(listOf("long1"), topLevel.map { it.filename })

        val parts = target.recordingDao().getPartsOf("long1")
        assertEquals(listOf("long1_p1", "long1_p2"), parts.map { it.filename })
        assertEquals(listOf(1, 2), parts.map { it.partIndex })
        assertEquals("Part one", parts[0].title)
        assertEquals("first half", parts[0].shortSummary)
        assertEquals("map2", parts[1].mindMap)
        assertEquals(listOf("hiring"), parts[1].topics)
        target.close()
    }

    // Restoring a backup written before parts existed must not promote stored parts to
    // standalone recordings — they would then show up twice, nested and at top level.
    @Test
    fun importWithoutPartKeys_leavesAnExistingRowsPartLinkageIntact() = runBlocking {
        val db = newDb()
        db.recordingDao().upsert(
            Recording(filename = "long1", title = "Long meeting")
        )
        db.recordingDao().upsert(
            Recording(filename = "long1_p1", title = "Part one", parentFilename = "long1", partIndex = 1)
        )

        val oldStyle = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply { put("filename", "long1_p1"); put("title", "Part one") })
            })
        }
        BackupManager(context, db).importFromJson(oldStyle)

        assertEquals(listOf("long1_p1"), db.recordingDao().getPartsOf("long1").map { it.filename })
        assertEquals(listOf("long1"), db.recordingDao().getAllFlow().first().map { it.filename })
        db.close()
    }

    // A part whose parent is absent from the payload can never be reached: every list query
    // filters parentFilename IS NULL, and nothing would ever call getPartsOf() for that name.
    @Test
    fun importWithParentMissingFromPayload_dropsTheLinkageInsteadOfOrphaning() = runBlocking {
        val db = newDb()
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "stray")
                    put("title", "Stray")
                    put("parentFilename", "nosuchparent")
                    put("partIndex", 1)
                })
            })
        }
        BackupManager(context, db).importFromJson(json)

        val stray = db.recordingDao().get("stray")
        assertEquals(null, stray?.parentFilename)
        assertEquals(listOf("stray"), db.recordingDao().getAllFlow().first().map { it.filename })
        db.close()
    }

    // deleteRecording() reads parentFilename as "DB-only row sharing the parent's audio" and skips
    // both the file delete and the FW920 wipe. A tampered backup that re-parents a standalone
    // recording would therefore make its later deletion a silent no-op — the row vanishes from the
    // list while the audio survives on disk and on the device. A decoy entry is enough to satisfy
    // the payload check, so the existing row's own state has to be the deciding factor.
    @Test
    fun importCannotReparentAnExistingStandaloneRecording() = runBlocking {
        val db = newDb()
        db.recordingDao().upsert(
            Recording(filename = "victim", title = "Victim", localPath = "/audio/victim")
        )

        val tampered = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "victim")
                    put("parentFilename", "decoy")
                    put("partIndex", 1)
                })
                put(JSONObject().apply { put("filename", "decoy") })
            })
        }
        BackupManager(context, db).importFromJson(tampered)

        assertEquals(null, db.recordingDao().get("victim")?.parentFilename)
        assertTrue(db.recordingDao().getAllFlow().first().any { it.filename == "victim" })
        db.close()
    }

    // The legitimate restore this must not break: parts absent locally (a fresh install, or after
    // Wipe Local Analysis removed the part rows) still import with their linkage.
    @Test
    fun importRestoresParts_whenTheyDoNotExistLocallyYet() = runBlocking {
        val db = newDb()
        db.recordingDao().upsert(Recording(filename = "long1", title = "Long meeting"))

        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply { put("filename", "long1"); put("title", "Long meeting") })
                put(JSONObject().apply {
                    put("filename", "long1_p1"); put("title", "Part one")
                    put("parentFilename", "long1"); put("partIndex", 1)
                })
            })
        }
        BackupManager(context, db).importFromJson(json)

        assertEquals(listOf("long1_p1"), db.recordingDao().getPartsOf("long1").map { it.filename })
        db.close()
    }

    @Test
    fun settingsRestore_appliesOnlyKeysPresent() = runBlocking {
        // Pre-existing value that must survive an import that omits its key.
        prefs().edit().putBoolean("use_bluetooth_mic", true).commit()

        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("settings", JSONObject().apply { put("auto_process", true) })
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        assertEquals(true, prefs().getBoolean("auto_process", false))
        // Untouched key retains its prior value.
        assertTrue(prefs().getBoolean("use_bluetooth_mic", false))
        assertFalse(prefs().contains("custom_prompt"))
        target.close()
    }

    @Test
    fun settingsRestore_plainJsonIntForLongKey_storedAsLongNoClassCast() = runBlocking {
        // org.json parses `24` as Integer. If applySettings inferred the pref type from
        // the JSON value, backup_interval_hours (written/read as Long everywhere) would be
        // stored as Int and a later getLong() would throw ClassCastException.
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("settings", JSONObject().apply { put("backup_interval_hours", 24) }) // plain int
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        // Must not throw and must round-trip to 24L.
        assertEquals(24L, prefs().getLong("backup_interval_hours", -1L))
        target.close()
    }

    @Test
    fun todoImport_punctuationOnlyTodos_areNotFoldedIntoOne() = runBlocking {
        // normalizeTodoText("?!?") and normalizeTodoText(":)") both reduce to "".
        // Empty normalized forms must bypass dedup so distinct todos still all insert.
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "?!?"); put("isDone", false); put("createdAt", 1L) })
                put(JSONObject().apply { put("text", ":)"); put("isDone", false); put("createdAt", 2L) })
            })
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        val todos = target.todoDao().getAll()
        assertEquals(setOf("?!?", ":)"), todos.map { it.text }.toSet())

        // Normal-text dedup still works alongside the empty-norm bypass.
        val normalJson = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "Buy Milk!"); put("createdAt", 3L) })
                put(JSONObject().apply { put("text", "buy milk"); put("createdAt", 4L) })
            })
        }
        BackupManager(context, target).importFromJson(normalJson)
        assertEquals(1, target.todoDao().getAll().count { normalizeMatchesBuyMilk(it.text) })
        target.close()
    }

    private fun normalizeMatchesBuyMilk(text: String): Boolean =
        normalizeTodoText(text) == "buy milk"

    @Test
    fun runAutoBackup_noFolderConfigured_returnsFailureWithoutThrowing() = runBlocking {
        // backup_folder_uri intentionally absent from prefs.
        val db = newDb()
        val result = BackupManager(context, db).runAutoBackup()

        assertTrue(result.isFailure)
        assertEquals("No backup folder configured", result.exceptionOrNull()?.message)
        assertTrue(prefs().contains("last_backup_error"))
        db.close()
    }

    @Test
    fun runAutoBackup_folderUriNotGranted_returnsFailure() = runBlocking {
        prefs().edit().putString("backup_folder_uri", "content://com.example/tree/fake").commit()
        val db = newDb()
        val result = BackupManager(context, db).runAutoBackup()

        assertTrue(result.isFailure)
        assertTrue(prefs().contains("last_backup_error"))
        db.close()
    }
}
