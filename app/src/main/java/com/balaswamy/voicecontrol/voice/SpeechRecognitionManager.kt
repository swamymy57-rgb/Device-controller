package com.balaswamy.voicecontrol.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechRecognitionManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context)
        else null
    private var destroyed = false
    private var listening = false

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                listener.onError(errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf(String::isNotBlank)
                if (result != null) listener.onResult(result)
                else listener.onError("Speech was not recognized. Try again.")
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun startListening() {
        if (destroyed) {
            listener.onError("Speech recognition has been stopped.")
            return
        }
        if (recognizer == null) {
            listener.onError("Speech recognition is not available on this device.")
            return
        }
        if (listening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        try {
            recognizer.startListening(intent)
        } catch (exception: SecurityException) {
            listening = false
            listener.onError("Microphone permission is required to listen.")
        } catch (exception: RuntimeException) {
            listening = false
            listener.onError("Could not start speech recognition: ${exception.localizedMessage ?: "unknown error"}")
        }
    }

    fun stopListening() {
        if (destroyed || !listening) return
        listening = false
        recognizer?.stopListening()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again shortly."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service failed."
        else -> "Speech recognition failed (error $error)."
    }
}
