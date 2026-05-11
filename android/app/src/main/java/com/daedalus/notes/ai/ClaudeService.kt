package com.daedalus.notes.ai

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface ClaudeApi {
    @POST("v1/messages")
    @Headers(
        "anthropic-version: 2023-06-01",
        "content-type: application/json"
    )
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Body body: ClaudeRequest
    ): ClaudeResponse
}

data class ClaudeRequest(
    val model: String = "claude-sonnet-4-6",
    val max_tokens: Int = 4096,
    val system: String,
    val messages: List<Map<String, String>>
)

data class ClaudeResponse(val content: List<Map<String, String>>)

class ClaudeService(private val apiKey: String) {

    private val api: ClaudeApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ClaudeApi::class.java)
    }

    suspend fun summarize(transcript: String, categoryPrompt: String): String {
        val request = ClaudeRequest(
            system = categoryPrompt,
            messages = listOf(
                mapOf("role" to "user", "content" to transcript)
            )
        )
        val response = api.createMessage(apiKey, request)
        return response.content.firstOrNull()?.get("text") ?: ""
    }

    suspend fun generateMindMap(transcript: String): String {
        val systemPrompt = """You are a mind map generator. Given a transcript, produce a JSON graph
            |suitable for rendering as a Mermaid mind map diagram.
            |Return JSON: {"center": str, "branches": [{"label": str, "children": [str]}]}""".trimMargin()

        val request = ClaudeRequest(
            system = systemPrompt,
            messages = listOf(
                mapOf("role" to "user", "content" to transcript)
            )
        )
        val response = api.createMessage(apiKey, request)
        return response.content.firstOrNull()?.get("text") ?: ""
    }
}
