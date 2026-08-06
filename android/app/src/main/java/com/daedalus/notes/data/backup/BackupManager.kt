package com.daedalus.notes.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.daedalus.notes.ai.AI_TEXT_BUDGET_DEFAULT
import com.daedalus.notes.ai.normalizeTodoText
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.data.model.TodoItem
import com.daedalus.notes.ui.screens.TODO_LOOKBACK_HOURS_DEFAULT
import com.daedalus.notes.viewmodel.MAX_RECORDING_MINUTES_DEFAULT
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable backup export/import logic, usable from a ViewModel or a WorkManager worker
 * without any UI dependency. All JSON build/parse logic is expressed as plain suspend
 * methods testable under Robolectric with an in-memory Room DB.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context),
    private val repo: RecordingRepository = RecordingRepository(db.recordingDao())
) {

    /** Builds the v2 backup payload: recordings + todos + settings. */
    suspend fun buildBackupJson(): JSONObject {
        val recordings = repo.allRecordings.first()
        val todos = db.todoDao().getAll()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return JSONObject().apply {
            put("backupVersion", 2)
            put("exportedAt", System.currentTimeMillis())

            val array = JSONArray()
            recordings.forEach { r ->
                val obj = JSONObject().apply {
                    put("filename", r.filename)
                    put("localPath", r.localPath)
                    put("sizeBytes", r.sizeBytes)
                    put("transcript", r.transcript)
                    put("summary", r.summary)
                    put("mindMap", r.mindMap)
                    put("category", r.category)
                    put("createdAt", r.createdAt)
                    put("title", r.title)
                    put("shortSummary", r.shortSummary)
                    put("durationMillis", r.durationMillis)
                    put("isLocal", r.isLocal)
                    r.deviceSerial?.let { put("deviceSerial", it) }

                    val topicsArr = JSONArray()
                    r.topics.forEach { topicsArr.put(it) }
                    put("topics", topicsArr)

                    r.embedding?.let { emb ->
                        val embArr = JSONArray()
                        emb.forEach { embArr.put(it.toDouble()) }
                        put("embedding", embArr)
                    }
                }
                array.put(obj)
            }
            put("recordings", array)

            val todosArr = JSONArray()
            todos.forEach { t ->
                todosArr.put(JSONObject().apply {
                    put("text", t.text)
                    put("isDone", t.isDone)
                    put("createdAt", t.createdAt)
                    put("sourceFilename", t.sourceFilename)
                    put("isAiGenerated", t.isAiGenerated)
                })
            }
            put("todos", todosArr)

            put("settings", buildSettingsJson(prefs))
        }
    }

    /** Writes the pretty-printed v2 payload to [uri] via the content resolver. */
    suspend fun exportToUri(uri: Uri) {
        val json = buildBackupJson()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    /** Reads a backup from [uri] and imports it. Returns the number of recordings imported. */
    suspend fun importFromUri(uri: Uri): Int {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw Exception("Could not open backup file")
        return importFromJson(JSONObject(jsonStr))
    }

    /**
     * Imports a parsed backup payload. Supports both v1 (recordings only) and v2
     * (recordings + todos + settings). Returns the number of recordings imported.
     */
    suspend fun importFromJson(root: JSONObject): Int {
        val array = root.optJSONArray("recordings") ?: throw Exception("Backup JSON is missing recordings list")
        var importedCount = 0

        val currentRecordingsDir = File(context.getExternalFilesDir(null), "Recordings")

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val filename = obj.optString("filename", "")

            // Security validation: prevent directory traversal via filename characters
            if (filename.isBlank() || !filename.matches(Regex("[A-Za-z0-9._-]+")) || filename == "." || filename == "..") {
                Log.w("BackupManager", "Skipping invalid filename in backup: $filename")
                continue
            }

            val originalLocalPath = obj.optString("localPath", "")
            val fileInCurrentDir = File(currentRecordingsDir, filename)

            // Security validation: prevent directory traversal via resolved paths
            val isOriginalPathSafe = if (originalLocalPath.isNotEmpty()) {
                try {
                    val originalFile = File(originalLocalPath).canonicalFile
                    val appFilesDir = context.getExternalFilesDir(null)?.canonicalFile
                    appFilesDir != null && originalFile.path.startsWith(appFilesDir.path + File.separator) && originalFile.exists()
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            val resolvedLocalPath = when {
                fileInCurrentDir.exists() -> fileInCurrentDir.absolutePath
                isOriginalPathSafe -> originalLocalPath
                else -> ""
            }

            val topicsArr = obj.optJSONArray("topics")
            val topics = mutableListOf<String>()
            if (topicsArr != null) {
                for (j in 0 until topicsArr.length()) {
                    val topic = topicsArr.optString(j, "")
                    if (topic.isNotEmpty()) {
                        topics.add(topic)
                    }
                }
            }

            val embArr = obj.optJSONArray("embedding")
            val embedding = if (embArr != null) {
                FloatArray(embArr.length()) { j -> embArr.optDouble(j, 0.0).toFloat() }
            } else {
                null
            }

            val existing = repo.get(filename)
            val recording = (existing ?: Recording(filename = filename)).copy(
                localPath = resolvedLocalPath.ifBlank { existing?.localPath ?: "" },
                sizeBytes = obj.optLong("sizeBytes", existing?.sizeBytes ?: 0L),
                transcript = obj.optString("transcript", existing?.transcript ?: ""),
                summary = obj.optString("summary", existing?.summary ?: ""),
                mindMap = obj.optString("mindMap", existing?.mindMap ?: ""),
                category = obj.optInt("category", existing?.category ?: 1),
                createdAt = obj.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()),
                title = obj.optString("title", existing?.title ?: ""),
                shortSummary = obj.optString("shortSummary", existing?.shortSummary ?: ""),
                topics = topics,
                durationMillis = obj.optLong("durationMillis", existing?.durationMillis ?: 0L),
                embedding = embedding ?: existing?.embedding,
                isLocal = obj.optBoolean("isLocal", existing?.isLocal ?: false),
                // Absent in older (pre-#83) backups — fall back to whatever is already there
                // rather than wiping provenance on restore.
                deviceSerial = if (obj.has("deviceSerial") && !obj.isNull("deviceSerial"))
                    obj.getString("deviceSerial") else existing?.deviceSerial
            )

            repo.save(recording)
            importedCount++
        }

        importTodos(root.optJSONArray("todos"))
        applySettings(root.optJSONObject("settings"))

        return importedCount
    }

    private suspend fun importTodos(todosArr: JSONArray?) {
        if (todosArr == null) return
        val existingNorms = db.todoDao().getAll()
            .map { normalizeTodoText(it.text) }
            .toMutableSet()

        for (i in 0 until todosArr.length()) {
            val obj = todosArr.optJSONObject(i) ?: continue
            val text = obj.optString("text", "")
            if (text.isBlank()) continue
            val norm = normalizeTodoText(text)
            // A normalized form of "" (punctuation/emoji-only todos) carries no
            // identity, so skip dedup for it and always insert rather than folding
            // every such todo into a single "" bucket.
            if (norm.isNotEmpty() && !existingNorms.add(norm)) continue // duplicate: already present

            db.todoDao().insert(
                TodoItem(
                    text = text,
                    isDone = obj.optBoolean("isDone", false),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    sourceFilename = if (obj.isNull("sourceFilename") || !obj.has("sourceFilename")) null else obj.optString("sourceFilename", ""),
                    isAiGenerated = obj.optBoolean("isAiGenerated", false)
                )
            )
        }
    }

    /**
     * Runs an automatic backup to the configured folder (SAF tree URI stored in prefs),
     * then applies retention by deleting the oldest backups beyond `backup_max_count`.
     * Never throws; failures are reported via the returned Result and recorded in
     * the `last_backup_error` pref.
     */
    suspend fun runAutoBackup(): Result<Unit> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val storedUri = prefs.getString(BackupPrefs.FOLDER_URI, null)
            if (storedUri.isNullOrBlank()) {
                return failAutoBackup(prefs, BackupPrefs.ERR_NO_FOLDER)
            }

            val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == storedUri && it.isWritePermission
            }
            val uri = Uri.parse(storedUri)
            val dir = if (hasWriteGrant) DocumentFile.fromTreeUri(context, uri) else null
            if (dir == null || !dir.exists() || !dir.canWrite()) {
                return failAutoBackup(prefs, BackupPrefs.ERR_FOLDER_NOT_ACCESSIBLE)
            }

            // Build the payload BEFORE creating the file so a build failure never
            // leaves a zero-byte backup whose newest-timestamp name would survive
            // retention (and fail on import) while deleting a good older backup.
            val payload = buildBackupJson().toString(2)

            val filename = "daedalus_backup_" +
                SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".json"
            val file = dir.createFile("application/json", filename)
                ?: return failAutoBackup(prefs, "Could not create backup file")

            val stream = context.contentResolver.openOutputStream(file.uri)
            if (stream == null) {
                file.delete()
                return failAutoBackup(prefs, "Could not open backup file for writing")
            }
            try {
                stream.use { out -> out.write(payload.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                file.delete()
                return failAutoBackup(prefs, e.message ?: "Could not write backup file")
            }

            val maxCount = prefs.getInt(BackupPrefs.MAX_COUNT, BackupPrefs.DEFAULT_MAX_COUNT)
            val names = dir.listFiles().mapNotNull { it.name }
            val toDelete = selectBackupsToDelete(names, maxCount)
            dir.listFiles().filter { it.name in toDelete }.forEach { it.delete() }

            prefs.edit()
                .putLong(BackupPrefs.LAST_BACKUP_TIME, System.currentTimeMillis())
                .remove(BackupPrefs.LAST_BACKUP_ERROR)
                .apply()

            return Result.success(Unit)
        } catch (e: Exception) {
            return failAutoBackup(prefs, e.message ?: "Unknown backup error")
        }
    }

    private fun failAutoBackup(prefs: android.content.SharedPreferences, message: String): Result<Unit> {
        prefs.edit().putString(BackupPrefs.LAST_BACKUP_ERROR, message).apply()
        return Result.failure(Exception(message))
    }

    /**
     * Exports the *effective* value of every setting — the stored value if the user has touched
     * the control, otherwise the same default its readers use. Every key is written only from a
     * UI save/toggle callback, so a `prefs.contains(key)` filter would omit whole settings from a
     * backup and silently leave the target device's values alone on restore.
     *
     * Per-key typed getters, deliberately verbose: each line carries the key's canonical type and
     * default, and the type must match its readers exactly (see [applySettings] — a bare Kotlin
     * literal is an Int, and storing an Int under a Long key makes a later getLong() throw).
     * Does not mutate SharedPreferences.
     */
    private fun buildSettingsJson(prefs: SharedPreferences) = JSONObject().apply {
        put("use_bluetooth_mic", prefs.getBoolean("use_bluetooth_mic", false))
        put("auto_process", prefs.getBoolean("auto_process", false))
        put("conversation_tts_enabled", prefs.getBoolean("conversation_tts_enabled", false))
        put("conversation_instant_send", prefs.getBoolean("conversation_instant_send", false))
        put("conversation_auto_listen", prefs.getBoolean("conversation_auto_listen", false))
        put("conversation_tts_rate", prefs.getFloat("conversation_tts_rate", 1.0f).toDouble())
        put("todo_lookback_hours", prefs.getLong("todo_lookback_hours", TODO_LOOKBACK_HOURS_DEFAULT))
        put(BackupPrefs.INTERVAL_HOURS, prefs.getLong(BackupPrefs.INTERVAL_HOURS, BackupPrefs.DEFAULT_INTERVAL_HOURS))
        put(BackupPrefs.MAX_COUNT, prefs.getInt(BackupPrefs.MAX_COUNT, BackupPrefs.DEFAULT_MAX_COUNT))
        put("max_recording_minutes", prefs.getInt("max_recording_minutes", MAX_RECORDING_MINUTES_DEFAULT))
        put("ai_text_budget_chars", prefs.getInt("ai_text_budget_chars", AI_TEXT_BUDGET_DEFAULT))

        // These two stay present-only: their absence is meaningful state. custom_prompt absent
        // means "use the built-in DEFAULT_PROMPT", conversation_tts_voice absent means "system
        // default voice" — exporting a fabricated default would turn an unset state into an
        // explicit setting on restore.
        if (prefs.contains("custom_prompt")) {
            put("custom_prompt", prefs.getString("custom_prompt", null))
        }
        if (prefs.contains("conversation_tts_voice")) {
            put("conversation_tts_voice", prefs.getString("conversation_tts_voice", null))
        }
    }

    /**
     * Applies restored settings with a typed whitelist. Each key is read and written
     * with its canonical SharedPreferences type — never inferred from the JSON value's
     * runtime type. org.json parses `24` as Integer, so type-sniffing would store an
     * interval/lookback key as Int and make a later getLong() throw ClassCastException.
     */
    private fun applySettings(settings: JSONObject?) {
        if (settings == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (settings.has("use_bluetooth_mic")) editor.putBoolean("use_bluetooth_mic", settings.optBoolean("use_bluetooth_mic"))
        if (settings.has("auto_process")) editor.putBoolean("auto_process", settings.optBoolean("auto_process"))
        if (settings.has("conversation_tts_enabled")) editor.putBoolean("conversation_tts_enabled", settings.optBoolean("conversation_tts_enabled"))
        if (settings.has("conversation_instant_send")) editor.putBoolean("conversation_instant_send", settings.optBoolean("conversation_instant_send"))
        if (settings.has("conversation_auto_listen")) editor.putBoolean("conversation_auto_listen", settings.optBoolean("conversation_auto_listen"))
        // A hand-edited or corrupt backup can carry a non-number (optDouble -> NaN) or an absurd
        // rate; both are ignored by TextToSpeech.setSpeechRate, which would leave a restored
        // install seemingly mute. Clamp to the range the speed picker offers (0.75x..2x).
        if (settings.has("conversation_tts_rate")) {
            val rate = settings.optDouble("conversation_tts_rate", 1.0).toFloat()
            editor.putFloat("conversation_tts_rate", if (rate.isNaN()) 1.0f else rate.coerceIn(0.75f, 2.0f))
        }
        if (settings.has("conversation_tts_voice")) editor.putString("conversation_tts_voice", settings.optString("conversation_tts_voice"))
        if (settings.has("custom_prompt")) editor.putString("custom_prompt", settings.optString("custom_prompt"))
        if (settings.has("todo_lookback_hours")) editor.putLong("todo_lookback_hours", settings.optLong("todo_lookback_hours"))
        if (settings.has(BackupPrefs.INTERVAL_HOURS)) editor.putLong(BackupPrefs.INTERVAL_HOURS, settings.optLong(BackupPrefs.INTERVAL_HOURS))
        if (settings.has(BackupPrefs.MAX_COUNT)) editor.putInt(BackupPrefs.MAX_COUNT, settings.optInt(BackupPrefs.MAX_COUNT))
        if (settings.has("max_recording_minutes")) editor.putInt("max_recording_minutes", settings.optInt("max_recording_minutes"))
        if (settings.has("ai_text_budget_chars")) editor.putInt("ai_text_budget_chars", settings.optInt("ai_text_budget_chars"))

        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "daedalus_prefs"

        private val BACKUP_FILENAME_REGEX = Regex("daedalus_backup_.*\\.json")

        /**
         * Given all filenames in the backup folder, returns the names of backups that
         * should be deleted to keep at most [maxCount] backups. Non-matching filenames
         * are ignored entirely (neither counted nor deleted). Oldest backups (by ascending
         * lexicographic sort, since names embed a sortable timestamp) are selected first.
         * [maxCount] <= 0 is coerced to 1 (always keep at least the newest backup).
         */
        fun selectBackupsToDelete(names: List<String>, maxCount: Int): List<String> {
            val effectiveMax = maxCount.coerceAtLeast(1)
            val backups = names.filter { it.matches(BACKUP_FILENAME_REGEX) }.sorted()
            val overflow = backups.size - effectiveMax
            if (overflow <= 0) return emptyList()
            return backups.take(overflow)
        }
    }
}
