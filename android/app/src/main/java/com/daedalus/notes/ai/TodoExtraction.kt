package com.daedalus.notes.ai

const val TODO_EXTRACTION_PROMPT = """From the notes below, extract concrete action items and tasks the speaker needs to do.

Return ONLY a bullet list, one task per line starting with "- ". Each task must be short (under 15 words), specific, and actionable. Do not repeat tasks from the "Already tracked" list. If there are no new tasks, return "- none".

Notes:"""

private const val MIN_TODO_LENGTH = 3
private const val MAX_TODO_LENGTH = 200
private const val MAX_TODO_COUNT = 10

private val BULLET_LINE_REGEX = Regex("""^\s*(?:[-*•]|\d+[.)])\s*(?:\[[ xX]?\]\s*)?(.+)""")

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
        .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        .filter { it.length in MIN_TODO_LENGTH..MAX_TODO_LENGTH }
        .take(MAX_TODO_COUNT)
}

fun normalizeTodoText(s: String): String =
    s.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

fun isDuplicateTodo(candidate: String, existing: Collection<String>): Boolean {
    val norm = normalizeTodoText(candidate)
    if (norm.isEmpty()) return false
    return existing.any { existingText ->
        val existingNorm = normalizeTodoText(existingText)
        existingNorm.isNotEmpty() && (existingNorm == norm || existingNorm.contains(norm) || norm.contains(existingNorm))
    }
}
