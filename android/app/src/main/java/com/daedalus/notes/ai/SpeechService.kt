package com.daedalus.notes.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.util.Log
import java.util.Locale

private const val TAG = "SpeechService"
private const val UTTERANCE_ID = "daedalus_reply"

/**
 * Thin seam over [TextToSpeech] so callers (e.g. ConversationViewModel) are unit-testable with a
 * mock, and so TTS init/availability failures are exposed rather than thrown — callers fall back
 * to text-only silently.
 */
interface SpeechService {
    /** True once TTS has finished initializing successfully with a usable language. */
    val isAvailable: Boolean

    fun speak(text: String)
    fun stop()
    fun shutdown()
}

/** [SpeechService] backed by Android's built-in [TextToSpeech] engine. */
class AndroidSpeechService(context: Context) : SpeechService {

    @Volatile
    override var isAvailable: Boolean = false
        private set

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed with status $status")
                return@TextToSpeech
            }
            val result = tts?.setLanguage(Locale.getDefault())
            isAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isAvailable) {
                Log.w(TAG, "TextToSpeech language unavailable: $result")
            }
        }
    }

    override fun speak(text: String) {
        if (!isAvailable) return
        tts?.speak(text, QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        isAvailable = false
    }
}
