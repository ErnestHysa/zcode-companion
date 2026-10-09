package com.zcode.android.features.conversation.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * §43.2-11.5 voice input EXPERIMENT (community ask #305 — dictation into the
 * composer). Deliberately the smallest possible surface: platform
 * [SpeechRecognizer] (no new dependency, no network SDK of our own — the
 * platform recognition service may call its own cloud backend, which is the
 * user's device decision, §27), availability-gated so the affordance never
 * renders where recognition is missing, and behind a PORT so the composer UI
 * is testable without a recognizer service.
 *
 * Feasibility findings are recorded in docs/adr/adr-019-phase11-voice-input.md
 * (adopted as experimental; not a product commitment).
 */
interface VoiceInputPort {
    /** Platform availability (device + recognition service). */
    val available: Boolean

    /** True between start() and a terminal callback. */
    val listening: Boolean

    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Int) -> Unit)
    fun stop()
}

class NoVoiceInput : VoiceInputPort {
    override val available = false
    override val listening = false
    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Int) -> Unit) = Unit
    override fun stop() = Unit
}

/** Platform recognizer → port adapter (created lazily; Robolectric shadows tolerate it). */
class PlatformVoiceInput(private val context: android.content.Context) : VoiceInputPort {

    override val available: Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    private var recognizer: SpeechRecognizer? = null

    override val listening: Boolean
        get() = recognizer != null

    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Int) -> Unit) {
        if (!available || recognizer != null) return
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                teardown()
                onError(error)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                teardown()
                if (!text.isNullOrBlank()) onFinal(text)
            }
        })
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    override fun stop() {
        recognizer?.stopListening()
        teardown()
    }

    private fun teardown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
