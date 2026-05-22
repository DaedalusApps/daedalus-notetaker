package com.daedalus.notes.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.CATEGORIES
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.selectedModel
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MINDMAP_PROMPT =
    "Create a mind map for this content. Return a nested Markdown bullet list with the main topic as root and key themes as branches. Be concise, max 3 levels deep."

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AppDatabase.getInstance(application)
    private val repo = RecordingRepository(db.recordingDao())
    private val llm  = LocalLlmService(application)

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    private val _currentNote = MutableStateFlow<Recording?>(null)
    val currentNote: StateFlow<Recording?> = _currentNote

    val allRecordings: StateFlow<List<Recording>> = repo.allRecordings
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncBleFile(filename: String, bleManager: BleManager) {
        viewModelScope.launch {
            _syncProgress.value = "Starting BLE Sync..."
            val file = bleManager.downloadFile(filename) { bytes ->
                _syncProgress.value = "Syncing $filename (${bytes / 1024} KB)..."
            }
            
            if (file != null) {
                val recording = repo.get(filename) ?: Recording(filename = filename)
                repo.save(recording.copy(localPath = file.absolutePath, sizeBytes = file.length()))
                _syncProgress.value = "Sync Complete!"
            } else {
                _aiError.value = "BLE Sync failed or timed out"
            }
            delay(2000)
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun fullAutoSync() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
            
            Log.i("DaedalusSync", "Starting Auto-Sync...")
            _syncProgress.value = "Searching for USB..."
            _aiError.value = null

            withContext(Dispatchers.IO) {
                val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
                val volumes = storageManager.storageVolumes
                
                Log.d("DaedalusSync", "Found ${volumes.size} storage volumes")
                var foundAny = false
                val commonFolders = listOf("RECORD", "Record", "RECORDER", "VOICE", "Voice")

                volumes.forEach { volume ->
                    Log.d("DaedalusSync", "Volume: ${volume.getDescription(context)} (Primary: ${volume.isPrimary}, Emulated: ${volume.isEmulated})")
                    if (volume.isPrimary || volume.isEmulated) return@forEach
                    
                    val mountPath = volume.directory
                    if (mountPath == null) {
                        Log.w("DaedalusSync", "Volume ${volume.getDescription(context)} has no directory path")
                        return@forEach
                    }

                    Log.i("DaedalusSync", "Scanning drive: $mountPath")

                    val recordDir = commonFolders.map { File(mountPath, it) }.find { it.exists() && it.isDirectory }
                    
                    if (recordDir != null) {
                        foundAny = true
                        Log.i("DaedalusSync", "Found recorder folder at: ${recordDir.absolutePath}")
                        val files = recordDir.listFiles()?.filter { it.name.endsWith(".mp3", ignoreCase = true) } ?: emptyList()
                        
                        Log.d("DaedalusSync", "Found ${files.size} MP3 files in recorder")

                        files.forEach { file ->
                            val destFile = File(localDir, file.name)
                            if (destFile.exists() && destFile.length() == file.length()) {
                                Log.d("DaedalusSync", "Skipping existing file: ${file.name}")
                                return@forEach
                            }

                            Log.i("DaedalusSync", "Syncing: ${file.name} (${file.length()} bytes)")
                            _syncProgress.value = "Syncing ${file.name}..."
                            
                            try {
                                file.inputStream().use { input ->
                                    FileOutputStream(destFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                val recording = repo.get(file.name) ?: Recording(filename = file.name)
                                repo.save(recording.copy(localPath = destFile.absolutePath, sizeBytes = destFile.length()))
                            } catch (e: Exception) {
                                Log.e("DaedalusSync", "Error copying ${file.name}", e)
                                _aiError.value = "Failed to copy ${file.name}: ${e.message}"
                            }
                        }
                    }
                }
                
                if (!foundAny) {
                    Log.e("DaedalusSync", "No compatible recorder folder found on any external volume")
                    _aiError.value = "Recorder not found. Ensure USB OTG is connected and has a 'RECORD' folder."
                } else {
                    Log.i("DaedalusSync", "Auto-Sync complete")
                }
            }
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun syncFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }

            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
                    val name = docFile.name ?: "REC_${System.currentTimeMillis()}.mp3"
                    val destFile = File(localDir, name)

                    if (destFile.exists() && destFile.length() == docFile.length()) return@forEach

                    _syncProgress.value = "Syncing $name..."

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        val recording = repo.get(name) ?: Recording(filename = name)
                        repo.save(recording.copy(localPath = destFile.absolutePath, sizeBytes = destFile.length()))
                    } catch (e: Exception) {
                        _aiError.value = "Failed to sync $name: ${e.message}"
                    }
                }
            }
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun loadNote(filename: String) {
        viewModelScope.launch {
            _currentNote.value = repo.get(filename)
        }
    }

    fun analyze(filename: String, categoryId: Int = 1) {
        viewModelScope.launch {
            _isProcessing.value = true
            _aiError.value = null
            try {
                val note = repo.get(filename) ?: run {
                    _aiError.value = "Recording not synced. Use the sync button to download it first."
                    return@launch
                }
                if (note.transcript.isBlank()) {
                    _aiError.value = "No transcript available for analysis"
                    return@launch
                }

                llm.ensureLoaded()
                
                val category = CATEGORIES.find { it.id == categoryId } ?: CATEGORIES[0]
                
                val rawSummary = llm.generate(category.systemPrompt, note.transcript)
                val summary = stripCodeFences(rawSummary)
                val mindMap = llm.generate(MINDMAP_PROMPT, note.transcript)

                repo.save(note.copy(summary = summary, mindMap = mindMap, category = categoryId))
                _currentNote.value = repo.get(filename)
            } catch (e: Exception) {
                Log.e("DaedalusAI", "Analysis failed", e)
                _aiError.value = e.message ?: "Unknown AI error"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun stripCodeFences(text: String): String {
        // Gemma sometimes wraps output in ```json ... ``` fences — strip them
        val stripped = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return stripped
    }

    fun exportMarkdown(filename: String) {
        // ... (existing export logic)
    }

    override fun onCleared() {
        super.onCleared()
        llm.close()
    }
}
