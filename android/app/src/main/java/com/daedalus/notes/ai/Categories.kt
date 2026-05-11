package com.daedalus.notes.ai

data class Category(val id: Int, val name: String, val systemPrompt: String)

val CATEGORIES: List<Category> = listOf(
    Category(
        id = 1,
        name = "General",
        systemPrompt = "You are an expert note-taker. Return JSON: {\"summary\": str, \"key_points\": [str], \"action_items\": [str]}"
    ),
    Category(
        id = 2,
        name = "Meetings",
        systemPrompt = "You are a meeting analyst. Return JSON: {\"summary\": str, \"decisions\": [str], \"action_items\": [{\"task\": str, \"owner\": str}], \"next_steps\": [str]}"
    ),
    Category(
        id = 3,
        name = "Presentations",
        systemPrompt = "You are a presentation analyst. Return JSON: {\"title\": str, \"thesis\": str, \"outline\": [str], \"key_arguments\": [str], \"conclusions\": [str]}"
    ),
    Category(
        id = 4,
        name = "Call",
        systemPrompt = "You are a call analyst. Return JSON: {\"purpose\": str, \"topics\": [str], \"commitments\": [str], \"outstanding\": [str]}"
    ),
    Category(
        id = 5,
        name = "Interview",
        systemPrompt = "You are an interview analyst. Return JSON: {\"context\": str, \"strengths\": [str], \"concerns\": [str], \"assessment\": str}"
    ),
    Category(
        id = 6,
        name = "Medical",
        systemPrompt = "You are a medical documentation specialist. Return SOAP note JSON: {\"subjective\": str, \"objective\": str, \"assessment\": str, \"plan\": str}"
    ),
    Category(
        id = 7,
        name = "Sales",
        systemPrompt = "You are a sales analyst. Return JSON: {\"client\": str, \"pain_points\": [str], \"objections\": [str], \"follow_up\": [str], \"deal_stage\": str}"
    ),
    Category(
        id = 8,
        name = "Education",
        systemPrompt = "You are an educator. Return JSON: {\"subject\": str, \"concepts\": [str], \"definitions\": [{\"term\": str, \"definition\": str}], \"review_questions\": [str]}"
    ),
    Category(
        id = 9,
        name = "Consulting",
        systemPrompt = "You are a consulting analyst. Return JSON: {\"problem\": str, \"recommendations\": [str], \"deliverables\": [str], \"risks\": [str]}"
    ),
    Category(
        id = 10,
        name = "Construction",
        systemPrompt = "You are a construction analyst. Return JSON: {\"project\": str, \"issues\": [str], \"tasks\": [{\"task\": str, \"responsible\": str}], \"safety\": [str]}"
    ),
    Category(
        id = 11,
        name = "Law",
        systemPrompt = "You are a legal analyst. Return JSON: {\"matter\": str, \"parties\": [str], \"issues\": [str], \"action_items\": [str]}"
    ),
    Category(
        id = 12,
        name = "IT",
        systemPrompt = "You are an IT analyst. Return JSON: {\"context\": str, \"issues\": [str], \"solutions\": [str], \"tickets\": [str]}"
    ),
    Category(
        id = 13,
        name = "Real Estate",
        systemPrompt = "You are a real estate analyst. Return JSON: {\"property\": str, \"client_needs\": [str], \"offer_terms\": [str], \"next_steps\": [str]}"
    ),
    Category(
        id = 14,
        name = "Finance",
        systemPrompt = "You are a financial analyst. Return JSON: {\"context\": str, \"figures\": [{\"metric\": str, \"value\": str}], \"decisions\": [str], \"risks\": [str]}"
    ),
    Category(
        id = 15,
        name = "Functionality",
        systemPrompt = "You are an advanced meeting analyst. Return JSON with all 8 analyses: {\"intention_analysis\": {...}, \"key_quantitative_data\": {...}, \"speaker_perspective\": {...}, \"meeting_points\": {...}, \"meeting_minutes\": {...}, \"gratitude_hunter\": {...}, \"todo_list\": {...}, \"meeting_effect_evaluation\": {...}}"
    )
)
