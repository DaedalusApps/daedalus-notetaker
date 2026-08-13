package com.daedalus.notes.data

import com.daedalus.notes.data.db.Converters
import com.daedalus.notes.data.db.RecordingDao
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class RecordingRepository(private val dao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = dao.getAllFlow()

    fun search(query: String): Flow<List<Recording>> {
        val ftsQuery = buildFtsMatchQuery(query) ?: return flowOf(emptyList())
        return dao.searchFtsFlow(ftsQuery)
    }

    companion object {
        // #101: FTS4's MATCH treats `"`, `*`, `-`, `^` and the words AND/OR/NEAR as query
        // syntax, and its default tokenizer is whole-token/prefix, not substring, based (unlike
        // the old `LIKE '%q%'` scan). To make every possible user-typed query safe AND to keep
        // as much of the old search feel as practical, we extract only the alphanumeric "words"
        // from the raw input and rebuild the MATCH string ourselves -- nothing the user types can
        // reach FTS as an operator.
        //
        // Behaviour changes versus the old LIKE scan (documented per #101, not silently shipped):
        //  - Each word becomes a *prefix* match (`word*`): "init" still finds "initiative"
        //    (whole-word prefix preserved), but a mid-word fragment like "native" no longer
        //    finds "alternative" -- FTS4 has no substring index, only tokens/prefixes. This is
        //    an accepted, intentional narrowing; there is no FTS4 way to fully preserve it.
        //  - Multiple words are ANDed as separate tokens (all must appear, in any order, in the
        //    combined filename+transcript+summary text) rather than required as one contiguous
        //    substring -- a relaxation, not a regression.
        //  - A query with no alphanumeric characters (blank, or punctuation only) has no tokens
        //    to search for; it short-circuits to an empty result without hitting the DB, instead
        //    of the old behaviour of an empty/near-empty LIKE pattern matching everything.
        internal fun buildFtsMatchQuery(query: String): String? {
            val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "$it*" }
        }
    }

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
