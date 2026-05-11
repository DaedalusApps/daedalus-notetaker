package com.daedalus.notes.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalLlmService(private val context: Context) {

    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    val isReady: Boolean get() = inference != null

    suspend fun ensureLoaded(modelId: String = selectedModel(context).id) {
        if (loadedModelId == modelId && inference != null) return
        withContext(Dispatchers.IO) {
            inference?.close()
            inference = null
            val file = modelFile(context, modelId)
            if (!file.exists()) error("Model not downloaded: ${file.absolutePath}")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(4096)
                .setMaxTopK(40)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            loadedModelId = modelId
        }
    }

    suspend fun generate(systemPrompt: String, userText: String): String =
        withContext(Dispatchers.IO) {
            val llm = inference ?: error("Model not loaded — call ensureLoaded() first")
            val prompt = buildString {
                append("<start_of_turn>system\n")
                append(systemPrompt)
                append("<end_of_turn>\n")
                append("<start_of_turn>user\n")
                append(userText)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
            llm.generateResponse(prompt)
        }

    fun close() {
        inference?.close()
        inference = null
        loadedModelId = null
    }
}
