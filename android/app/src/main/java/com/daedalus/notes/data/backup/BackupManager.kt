package com.daedalus.notes.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.data.model.TodoItem
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reusable backup export/import logic, usable from a ViewModel or a WorkManager worker
 * without any UI dependency. All JSON build/parse logic is expressed as plain suspend
 * methods testable under Robolectric with an in-memory Room DB.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {

    private val repo = RecordingRepository(db.recordingDao())

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

            val settings = JSONObject()
            SETTINGS_KEYS.forEach { key ->
                if (prefs.contains(key)) {
                    settings.put(key, prefs.all[key])
                }
            }
            put("settings", settings)
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
        @Suppress("UNUSED_VARIABLE")
        val backupVersion = root.optInt("backupVersion", 1)

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
                isLocal = obj.optBoolean("isLocal", existing?.isLocal ?: false)
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
            if (!existingNorms.add(norm)) continue // duplicate: already present

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

    private fun applySettings(settings: JSONObject?) {
        if (settings == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        SETTINGS_KEYS.forEach { key ->
            if (!settings.has(key)) return@forEach
            when (val value = settings.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "daedalus_prefs"

        private val SETTINGS_KEYS = listOf(
            "use_bluetooth_mic",
            "auto_process",
            "custom_prompt",
            "todo_lookback_hours",
            "backup_interval_hours",
            "backup_max_count"
        )

        internal fun normalizeTodoText(s: String): String =
            s.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
