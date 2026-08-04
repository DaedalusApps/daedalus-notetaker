package com.daedalus.notes.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "SpeechService"
private const val UTTERANCE_ID = "daedalus_reply"

/** A selectable voice for the engine's current language, labeled in stable order. */
data class VoiceInfo(val id: String, val label: String)

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

    /** Sets the speaking rate (1.0 = normal). */
    fun setSpeechRate(rate: Float)

    /** Voices available for the engine's current/default language only, "Voice 1"/"Voice 2"…. */
    fun availableVoices(): List<VoiceInfo>

    /**
     * Selects the voice with the given [VoiceInfo.id]; an empty id restores the engine's default
     * voice. Returns false if the id is unknown.
     */
    fun setVoice(id: String): Boolean

    /** Speaks [text] regardless of any caller-side conversation state — used for previews. */
    fun preview(text: String)

    /** True while an utterance is currently being spoken. */
    val isSpeaking: Boolean

    /**
     * Registers a callback invoked whenever [isSpeaking] changes. May be called from a non-main
     * thread (TextToSpeech's UtteranceProgressListener callbacks do not arrive on the main
     * thread) — callers must not assume main-thread delivery.
     */
    fun setOnSpeakingChangedListener(listener: (Boolean) -> Unit)
}

/** [SpeechService] backed by Android's built-in [TextToSpeech] engine. */
class AndroidSpeechService(context: Context) : SpeechService {

    @Volatile
    override var isAvailable: Boolean = false
        private set

    private var tts: TextToSpeech? = null

    // The engine's voice as it was at init, so picking "system default" (an empty id) can put it
    // back — without it, deselecting a custom voice would leave the custom voice speaking until
    // the process restarted while the UI claimed the default was in use.
    @Volatile
    private var defaultVoice: Voice? = null

    // Rate/voice requested before the engine finished initializing; applied once it has. Volatile
    // because they are written by the caller and read from the engine's init callback.
    @Volatile
    private var pendingRate: Float? = null

    @Volatile
    private var pendingVoiceId: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed with status $status")
                return@TextToSpeech
            }
            val result = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            isAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isAvailable) {
                Log.w(TAG, "TextToSpeech language unavailable: $result")
                return@TextToSpeech
            }
            defaultVoice = tts?.voice ?: tts?.defaultVoice
            pendingRate?.let { tts?.setSpeechRate(it) }
            pendingVoiceId?.let { applyVoice(it) }
        }
    }

    override fun speak(text: String) {
        if (!isAvailable) return
        tts?.speak(text, QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun setSpeechRate(rate: Float) {
        if (isAvailable) tts?.setSpeechRate(rate) else pendingRate = rate
    }

    override fun availableVoices(): List<VoiceInfo> {
        val engine = tts
        if (engine == null || !isAvailable) return emptyList()
        val voices = engine.voices ?: return emptyList()
        val language = engine.voice?.locale ?: engine.defaultVoice?.locale ?: Locale.getDefault()
        return voices
            .filter { it.locale == language && !it.isNetworkConnectionRequired }
            .map { it.name }
            .sorted()
            .mapIndexed { index, name -> VoiceInfo(id = name, label = "Voice ${index + 1}") }
    }

    override fun setVoice(id: String): Boolean {
        if (tts == null || !isAvailable) {
            pendingVoiceId = id
            return true
        }
        return applyVoice(id)
    }

    /** Applies [id] to a live engine; an empty id restores the voice captured at init. */
    private fun applyVoice(id: String): Boolean {
        val engine = tts ?: return false
        val voice =
            if (id.isEmpty()) defaultVoice
            else engine.voices?.firstOrNull { it.name == id }
        engine.voice = voice ?: return false
        return true
    }

    override fun preview(text: String) = speak(text)

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    @Volatile
    private var speakingChangedListener: ((Boolean) -> Unit)? = null

    override fun setOnSpeakingChangedListener(listener: (Boolean) -> Unit) {
        speakingChangedListener = listener
    }

    override fun stop() {
        tts?.stop()
    }

    // stop() first: shutdown() releases the engine binding but does not reliably cut off an
    // utterance already handed to it. Dropping the reference makes a second shutdown — or a
    // stop()/speak() arriving after it — a no-op instead of a call into a dead engine.
    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isAvailable = false
    }
}
