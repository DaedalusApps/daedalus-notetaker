package com.daedalus.notes.ai

const val TODO_EXTRACTION_PROMPT = """From the notes below, extract concrete action items and tasks the speaker needs to do.

Return ONLY a bullet list, one task per line starting with "- ". Each task must be short (under 15 words), specific, and actionable. Do not repeat tasks from the "Already tracked" list. If there are no new tasks, return "- none".

Notes:"""

private const val MIN_TODO_LENGTH = 3
private const val MAX_TODO_LENGTH = 200
private const val MAX_TODO_COUNT = 10

private val BULLET_LINE_REGEX = Regex("""^\s*(?:[-*•]|\d+[.)])\s*(?:\[[ xX]?\]\s*)?(.+)""")

private val NONE_SENTINELS = setOf("none", "no new tasks")

fun stripCodeFences(text: String): String {
    // Gemma sometimes wraps output in ```json ... ``` fences — strip them
    return text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
}

fun parseTodoLines(raw: String): List<String> {
    val cleaned = stripCodeFences(raw)
    return cleaned.lines()
        .mapNotNull { line -> BULLET_LINE_REGEX.matchEntire(line)?.groupValues?.get(1)?.trim() }
        .filter { it.isNotEmpty() && normalizeTodoText(it) !in NONE_SENTINELS }
        .filter { it.length in MIN_TODO_LENGTH..MAX_TODO_LENGTH }
        .take(MAX_TODO_COUNT)
}

fun normalizeTodoText(s: String): String =
    s.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

private const val MIN_CONTAINMENT_LENGTH = 8

fun isDuplicateTodo(candidate: String, existing: Collection<String>): Boolean {
    val norm = normalizeTodoText(candidate)
    if (norm.isEmpty()) return false
    return isDuplicateTodoNormalized(norm, existing.map { normalizeTodoText(it) })
}

/**
 * Same dedup rule as [isDuplicateTodo], operating on already-normalized strings so callers
 * that maintain a running normalized list don't have to re-normalize on every check.
 * Exact normalized equality always counts as a duplicate. Containment (either direction)
 * only counts when the SHORTER of the two normalized strings is at least
 * [MIN_CONTAINMENT_LENGTH] chars, so short todos like "buy" or "call" don't suppress every
 * longer todo that happens to contain them.
 */
internal fun isDuplicateTodoNormalized(candidateNorm: String, trackedNorms: List<String>): Boolean {
    if (candidateNorm.isEmpty()) return false
    return trackedNorms.any { existingNorm ->
        if (existingNorm.isEmpty()) return@any false
        if (existingNorm == candidateNorm) return@any true
        val shorterLength = minOf(existingNorm.length, candidateNorm.length)
        shorterLength >= MIN_CONTAINMENT_LENGTH &&
            (existingNorm.contains(candidateNorm) || candidateNorm.contains(existingNorm))
    }
}
