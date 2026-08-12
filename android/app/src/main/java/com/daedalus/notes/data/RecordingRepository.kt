package com.daedalus.notes.data

import com.daedalus.notes.data.db.Converters
import com.daedalus.notes.data.db.RecordingDao
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val dao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = dao.getAllFlow()

    fun search(query: String): Flow<List<Recording>> = dao.searchFlow(query)

    suspend fun get(filename: String): Recording? = dao.get(filename)

    suspend fun save(recording: Recording) = dao.upsert(recording)

    /** Re-reads before writing: analysis writes to this row throughout a run. */
    suspend fun updateAnalysisFailed(filename: String, failed: Boolean) {
        val r = dao.get(filename) ?: return
        if (r.analysisFailed != failed) dao.upsert(r.copy(analysisFailed = failed))
    }

    suspend fun updateTranscript(filename: String, transcript: String) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(transcript = transcript))
    }

    suspend fun delete(recording: Recording) = dao.delete(recording)

    suspend fun countOtherSharingPath(path: String, filename: String): Int = dao.countOtherSharingPath(path, filename)

    suspend fun getPartsOf(filename: String): List<Recording> = dao.getPartsOf(filename)

    val parentsWithParts: Flow<List<String>> = dao.parentsWithPartsFlow()

    suspend fun deletePartsOf(filename: String) = dao.deletePartsOf(filename)

    suspend fun allForBackup(): List<Recording> = dao.getAllForBackup()

    suspend fun getPendingDeletes(): List<Recording> = dao.getPendingDeletes()

    suspend fun markPendingDelete(filename: String) = dao.updatePendingDelete(filename, true)

    suspend fun updateTitleAndSummary(filename: String, title: String, shortSummary: String) =
        dao.updateTitleAndSummary(filename, title, shortSummary)

    suspend fun updateEmbedding(filename: String, embedding: FloatArray) {
        val bytes = Converters().fromFloatArray(embedding) ?: return
        dao.updateEmbeddingBytes(filename, bytes)
    }

    fun semanticSearch(
        queryEmbedding: FloatArray,
        candidates: List<Recording>,
        topK: Int = 5,
        minScore: Float = Float.NEGATIVE_INFINITY
    ): List<Recording> {
        return candidates
            .mapNotNull { r ->
                val emb = r.embedding ?: return@mapNotNull null
                val score = cosineSimilarity(queryEmbedding, emb)
                r to score
            }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    suspend fun updateSummary(
        filename: String,
        summary: String,
        mindMap: String,
        title: String = "",
        shortSummary: String = "",
        topics: List<String> = emptyList()
    ) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(
            summary = summary,
            mindMap = mindMap,
            title = title,
            shortSummary = shortSummary,
            topics = topics
        ))
    }

    suspend fun wipeAllAnalysis() = dao.wipeAllAnalysis()
}
