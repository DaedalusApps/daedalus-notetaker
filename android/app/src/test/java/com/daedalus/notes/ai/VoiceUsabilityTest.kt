package com.daedalus.notes.ai

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isVoiceUsable] is the pure predicate behind the TTS voice-usability filtering fix (#67): the
 * voice picker was listing voices whose data isn't installed on the device (e.g.
 * `en-us-x-star11-local`); selecting one left every subsequent utterance silently failing
 * engine-side. Extracted from [AndroidSpeechService] (which needs a real [TextToSpeech] and isn't
 * reliably driveable under Robolectric) so the filtering logic itself is unit-testable.
 */
class VoiceUsabilityTest {

    @Test
    fun installedLocalVoice_isUsable() {
        assertTrue(isVoiceUsable(networkRequired = false, features = setOf("some-other-feature")))
    }

    @Test
    fun networkRequiredVoice_isNotUsable() {
        assertFalse(isVoiceUsable(networkRequired = true, features = emptySet()))
    }

    @Test
    fun notInstalledFeatureVoice_isNotUsable() {
        assertFalse(
            isVoiceUsable(
                networkRequired = false,
                features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            )
        )
    }

    @Test
    fun nullFeatures_isUsable() {
        assertTrue(isVoiceUsable(networkRequired = false, features = null))
    }
}
