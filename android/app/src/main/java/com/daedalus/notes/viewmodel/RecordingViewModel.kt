package com.daedalus.notes.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.CATEGORIES
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.selectedModel
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val MINDMAP_PROMPT =
    "Create a mind map for this content. Return a nested Markdown bullet list with the main topic as root and key themes as branches. Be concise, max 3 levels deep."

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AppDatabase.getInstance(application)
    private val repo = RecordingRepository(db.recordingDao())
    private val llm  = LocalLlmService(application)

    val recordings: StateFlow<List<Recording>> = repo.allRecordings
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

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
            _aiError.value = null
            try {
                val modelId  = selectedModel(getApplication()).id
                llm.ensureLoaded(modelId)
                val category = CATEGORIES.find { it.id == categoryId } ?: CATEGORIES[0]
                val text = transcript.ifBlank {
                    "No transcript provided. Generate a placeholder summary noting that no audio content was available."
                }
                val summary = llm.generate(category.systemPrompt, text)
                val mindMap = llm.generate(MINDMAP_PROMPT, text)
                repo.updateSummary(filename, summary, mindMap)
                _currentNote.value = repo.get(filename)
            } catch (e: Exception) {
                _aiError.value = e.message ?: "AI error"
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
            if (existing == null) repo.save(Recording(filename = filename, sizeBytes = sizeBytes))
            loadNote(filename)
        }
    }

    override fun onCleared() {
        super.onCleared()
        llm.close()
    }
}
