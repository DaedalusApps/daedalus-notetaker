package com.daedalus.notes.ai

import android.content.Context
import java.io.File

data class LocalModel(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val filename: String,
    val sizeBytes: Long,
    val useGpu: Boolean = false
)

val GEMMA3_1B = LocalModel(
    id          = "gemma3_1b",
    displayName = "Gemma 3 1B",
    description = "~555 MB · Newer architecture · Better instruction following",
    downloadUrl = "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma3-1B-it-int4.task",
    filename    = "gemma3-1B-it-int4.task",
    sizeBytes   = 555_000_000L,
    useGpu      = false
)

// Whisper base.en (sherpa-onnx int8 quantized) — downloaded as individual files
const val WHISPER_ENCODER_FILE = "base.en-encoder.int8.onnx"
const val WHISPER_DECODER_FILE = "base.en-decoder.int8.onnx"
const val WHISPER_TOKENS_FILE  = "base.en-tokens.txt"
private const val WHISPER_HF = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main"
const val WHISPER_ENCODER_URL  = "$WHISPER_HF/base.en-encoder.int8.onnx"
const val WHISPER_DECODER_URL  = "$WHISPER_HF/base.en-decoder.int8.onnx"
const val WHISPER_TOKENS_URL   = "$WHISPER_HF/base.en-tokens.txt"
const val WHISPER_TOTAL_BYTES  = 119_000_000L  // ~119 MB combined

fun modelsDir(context: Context): File =
    File(context.filesDir, "models").also { it.mkdirs() }

fun whisperModelDir(context: Context): File =
    File(modelsDir(context), "sherpa-whisper-base")

fun isWhisperReady(context: Context): Boolean {
    val dir = whisperModelDir(context)
    return File(dir, WHISPER_ENCODER_FILE).exists() &&
           File(dir, WHISPER_DECODER_FILE).exists() &&
           File(dir, WHISPER_TOKENS_FILE).exists()
}

fun voskModelDir(context: Context): File =
    File(modelsDir(context), "vosk-model-small-en-us-0.15")

fun modelFile(context: Context): File =
    File(modelsDir(context), GEMMA3_1B.filename)

fun selectedModel(context: Context): LocalModel = GEMMA3_1B
