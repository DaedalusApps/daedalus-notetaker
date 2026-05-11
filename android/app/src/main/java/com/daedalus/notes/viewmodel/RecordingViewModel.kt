package com.daedalus.notes.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.CATEGORIES
import com.daedalus.notes.ai.ClaudeService
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = RecordingRepository(db.recordingDao())
    private val prefs = application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    val recordings: StateFlow<List<Recording>> = repo.allRecordings
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _currentNote = MutableStateFlow<Recording?>(null)
    val currentNote: StateFlow<Recording?> = _currentNote

    fun loadNote(filename: String) {
        viewModelScope.launch {
            _currentNote.value = repo.get(filename) ?: Recording(filename = filename)
        }
    }

    fun analyze(filename: String, categoryId: Int = 1, transcript: String = "") {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val apiKey = prefs.getString("claude_api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    _isProcessing.value = false
                    return@launch
                }
                val category = CATEGORIES.find { it.id == categoryId } ?: CATEGORIES[0]
                val service = ClaudeService(apiKey)
                val useTranscript = transcript.ifBlank {
                    "No transcript available. Please describe the recording content."
                }
                val summary = service.summarize(useTranscript, category.systemPrompt)
                val mindMap = service.generateMindMap(useTranscript)
                repo.updateSummary(filename, summary, mindMap)
                _currentNote.value = repo.get(filename)
            } catch (e: Exception) {
                // Log error — surface via a dedicated error StateFlow if needed
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun exportMarkdown(filename: String): String {
        val note = _currentNote.value ?: return ""
        return buildString {
            appendLine("# $filename")
            appendLine()
            appendLine("## Transcript")
            appendLine(note.transcript.ifBlank { "No transcript" })
            appendLine()
            appendLine("## Summary")
            appendLine(note.summary.ifBlank { "No summary" })
            appendLine()
            appendLine("## Mind Map")
            appendLine(note.mindMap.ifBlank { "No mind map" })
        }
    }

    fun importFromDevice(filename: String, sizeBytes: Long) {
        viewModelScope.launch {
            val existing = repo.get(filename)
            if (existing == null) {
                repo.save(Recording(filename = filename, sizeBytes = sizeBytes))
            }
            loadNote(filename)
        }
    }
}
