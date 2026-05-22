package com.daedalus.notes.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.util.Log

class LocalLlmService(private val context: Context) {

    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    val isReady: Boolean get() = inference != null

    suspend fun ensureLoaded(modelId: String = selectedModel(context).id) {
        if (loadedModelId == modelId && inference != null) return
        withContext(Dispatchers.IO) {
            Log.i("DaedalusAI", "Loading model: $modelId")
            inference?.close()
            inference = null
            val file = modelFile(context, modelId)
            if (!file.exists()) {
                Log.e("DaedalusAI", "Model file not found: ${file.absolutePath}")
                error("Model not downloaded: ${file.absolutePath}")
            }
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(4096)
                    .setMaxTopK(40)
                    .build()
                inference = LlmInference.createFromOptions(context, options)
                loadedModelId = modelId
                Log.i("DaedalusAI", "Model loaded successfully")
            } catch (e: Exception) {
                Log.e("DaedalusAI", "Failed to load MediaPipe inference engine", e)
                throw e
            }
        }
    }

    suspend fun generate(systemPrompt: String, userText: String): String =
        withContext(Dispatchers.IO) {
            val llm = inference ?: error("Model not loaded — call ensureLoaded() first")
            Log.d("DaedalusAI", "Generating response for input (length: ${userText.length})...")
            // Gemma 2B instruction-tuned format: <start_of_turn>user\n...\n<end_of_turn>\n<start_of_turn>model\n
            val prompt = buildString {
                append("<start_of_turn>user\n")
                if (systemPrompt.isNotBlank()) {
                    append(systemPrompt)
                    append("\n\n")
                }
                append(userText)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
            try {
                val response = llm.generateResponse(prompt)
                Log.d("DaedalusAI", "Generation complete (response length: ${response.length})")
                response
            } catch (e: Exception) {
                Log.e("DaedalusAI", "Inference failed", e)
                throw e
            }
        }

    fun close() {
        inference?.close()
        inference = null
        loadedModelId = null
    }
}
