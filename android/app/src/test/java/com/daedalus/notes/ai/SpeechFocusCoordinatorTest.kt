package com.daedalus.notes.ai

import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

/**
 * [AndroidSpeechService] needs a real [android.speech.tts.TextToSpeech] to unit-test speak()/
 * stop() end-to-end, which isn't reliably driveable under Robolectric — so the audio-focus
 * coordination (#57) is extracted into [SpeechFocusCoordinator] and tested at that seam instead.
 */
class SpeechFocusCoordinatorTest {

    @Test
    fun beforeSpeak_requestsFocus() {
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.beforeSpeak()

        verify(exactly = 1) { focusManager.request() }
    }

    @Test
    fun onSpeakingChanged_falseAfterNaturalCompletion_abandonsFocus() {
        // Simulates AndroidSpeechService's onStart -> onDone utterance-progress path.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.beforeSpeak()
        coordinator.onSpeakingChanged(true)
        coordinator.onSpeakingChanged(false)

        verifyOrder {
            focusManager.request()
            focusManager.abandon()
        }
    }

    @Test
    fun onSpeakingChanged_falseWithoutPriorTrue_stillAbandonsFocus() {
        // Simulates AndroidSpeechService.stop(): setSpeaking(false) is forced even though the
        // flushed utterance's progress-listener callback may never arrive.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.beforeSpeak()
        coordinator.onSpeakingChanged(false)

        verify(exactly = 1) { focusManager.abandon() }
    }

    @Test
    fun onSpeakingChanged_falseTwice_abandonsFocusOnlyOnce() {
        // Double-stop / stop-then-shutdown: abandon must not be re-issued for focus already
        // released.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.beforeSpeak()
        coordinator.onSpeakingChanged(false)
        coordinator.onSpeakingChanged(false)

        verify(exactly = 1) { focusManager.abandon() }
    }

    @Test
    fun onSpeakingChanged_falseAfterSpeakFailure_abandonsFocus() {
        // Simulates AndroidSpeechService's deprecated onError callback path.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.beforeSpeak()
        coordinator.onSpeakingChanged(false)

        verify(exactly = 1) { focusManager.abandon() }
    }

    @Test
    fun onSpeakingChanged_falseWithoutBeforeSpeak_doesNotAbandonFocus() {
        // No focus was ever requested (e.g. isAvailable was false so speak() returned early) —
        // abandon must not be called for focus never held.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(false)

        verify(exactly = 0) { focusManager.abandon() }
    }
}
