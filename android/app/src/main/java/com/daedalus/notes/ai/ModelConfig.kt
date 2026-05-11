package com.daedalus.notes.ai

import android.content.Context
import java.io.File

data class LocalModel(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val filename: String,
    val sizeBytes: Long
)

val AVAILABLE_MODELS = listOf(
    LocalModel(
        id          = "gemma4_e4b",
        displayName = "Gemma 4 E4B",
        description = "Best quality, ~2.5 GB. Optimized for S24 Ultra NPU.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-litert-lm.bin",
        filename    = "gemma4_e4b.bin",
        sizeBytes   = 2_500_000_000L
    ),
    LocalModel(
        id          = "gemma3_4b",
        displayName = "Gemma 3 4B (Lighter)",
        description = "Smaller, ~1.5 GB. Works on most Android phones.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-3-4B-it-litert-lm/resolve/main/gemma-3-4B-it-litert-lm.bin",
        filename    = "gemma3_4b.bin",
        sizeBytes   = 1_500_000_000L
    )
)

const val DEFAULT_MODEL_ID = "gemma4_e4b"
const val PREFS_MODEL_ID   = "local_model_id"

fun modelsDir(context: Context): File =
    File(context.filesDir, "models").also { it.mkdirs() }

fun modelFile(context: Context, modelId: String): File {
    val model = AVAILABLE_MODELS.find { it.id == modelId } ?: AVAILABLE_MODELS[0]
    return File(modelsDir(context), model.filename)
}

fun selectedModel(context: Context): LocalModel {
    val prefs = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
    val id = prefs.getString(PREFS_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
    return AVAILABLE_MODELS.find { it.id == id } ?: AVAILABLE_MODELS[0]
}
