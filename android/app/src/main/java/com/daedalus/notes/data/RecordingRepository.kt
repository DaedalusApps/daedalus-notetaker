package com.daedalus.notes.data

import com.daedalus.notes.data.db.RecordingDao
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val dao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = dao.getAllFlow()

    suspend fun get(filename: String): Recording? = dao.get(filename)

    suspend fun save(recording: Recording) = dao.upsert(recording)

    suspend fun updateTranscript(filename: String, transcript: String) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(transcript = transcript))
    }

    suspend fun updateSummary(filename: String, summary: String, mindMap: String) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(summary = summary, mindMap = mindMap))
    }
}
