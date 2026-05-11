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

/**
 * Models compatible with MediaPipe LLM Inference (tasks-genai .bin format).
 * Hosted on community mirrors to bypass gating requirements.
 */
val AVAILABLE_MODELS = listOf(
    LocalModel(
        id          = "gemma_2b_cpu",
        displayName = "Gemma 1.1 2B (CPU)",
        description = "~1.4 GB, optimized for any Android CPU. Very stable.",
        downloadUrl = "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma-1.1-2b-it-cpu-int4.bin",
        filename    = "gemma-1.1-2b-it-cpu-int4.bin",
        sizeBytes   = 1_346_427_328L
    ),
    LocalModel(
        id          = "gemma_2b_gpu",
        displayName = "Gemma 2B (GPU)",
        description = "~1.4 GB, GPU-accelerated. Best for S24 Ultra performance.",
        downloadUrl = "https://huggingface.co/manjirao/gemma-2b-it-gpu-int4.bin/resolve/main/gemma-2b-it-gpu-int4.bin",
        filename    = "gemma-2b-it-gpu-int4.bin",
        sizeBytes   = 1_354_301_440L
    )
)

const val DEFAULT_MODEL_ID = "gemma_2b_gpu"
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
