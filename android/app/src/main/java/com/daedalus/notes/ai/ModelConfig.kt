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
 * Hosted on Google's public storage — no authentication required.
 *
 * NOTE: Gemma 4 E4B uses the newer .litertlm format (LiteRT-LM SDK) which
 * is not yet stable with the Android build toolchain. Will be added as an
 * upgrade when LiteRT-LM + KSP compatibility matures.
 */
val AVAILABLE_MODELS = listOf(
    LocalModel(
        id          = "gemma2_2b_cpu",
        displayName = "Gemma 2 2B (CPU)",
        description = "~1.4 GB, runs on any Android phone. Good quality, fast.",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2-2b-it-cpu-int4/float16/1/gemma-2-2b-it-cpu-int4.bin",
        filename    = "gemma2_2b_cpu_int4.bin",
        sizeBytes   = 1_400_000_000L
    ),
    LocalModel(
        id          = "gemma2_2b_gpu",
        displayName = "Gemma 2 2B (GPU/NPU)",
        description = "~2.7 GB, GPU-accelerated. Best for S24 Ultra and similar flagships.",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2-2b-it-gpu-int8/float16/1/gemma-2-2b-it-gpu-int8.bin",
        filename    = "gemma2_2b_gpu_int8.bin",
        sizeBytes   = 2_700_000_000L
    )
)

const val DEFAULT_MODEL_ID = "gemma2_2b_gpu"
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
