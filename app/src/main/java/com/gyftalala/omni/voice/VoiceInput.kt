package com.gyftalala.omni.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

interface SpeechInput {
    val onDevice: Boolean
    fun start(language: String, transcript: (String) -> Unit, amplitude: (Float) -> Unit, done: (String?) -> Unit)
    fun stop()
    fun cancel()
}

/** One user-started recording. Never restart the microphone in the background. */
class VoiceInput(private val context: Context) : SpeechInput {
    override val onDevice: Boolean get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available: Boolean get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var finishCurrent: ((String?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun start(language: String, transcript: (String) -> Unit, amplitude: (Float) -> Unit, done: (String?) -> Unit) {
        cancel()
        val token = generation
        if (!available) { done("Speech recognition is unavailable. Enable a speech service in Android Settings, or type your message."); return }
        try {
            val input = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = input
            fun finish(error: String?) { if (token == generation) { cancel(); done(error) } }
            finishCurrent = ::finish
            input.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { if (token == generation) amplitude(((rmsdB + 2) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    handler.postDelayed({ finish("Speech service took too long. Review the transcript or try again.") }, 8000)
                }
                override fun onError(error: Int) = finish(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear speech detected. Try again or type your message."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service could not connect. Check your connection or type your message."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech model for this language is unavailable. Try another language or download its model in Android speech settings."
                    else -> "Speech recognition stopped. Try again or type your message."
                })
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(transcript)
                    finish(null)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (token == generation) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(transcript)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            input.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
            handler.postDelayed({ if (token == generation) stop() }, 60000)
        } catch (_: Exception) { cancel(); done("Could not start speech recognition. Try again or type your message.") }
    }
    override fun stop() {
        recognizer?.stopListening()
        val token = generation
        handler.postDelayed({ if (token == generation) finishCurrent?.invoke("Speech service took too long. Review the transcript or try again.") }, 10000)
    }
    override fun cancel() {
        generation++
        finishCurrent = null
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
    }
}
