package com.kimiasistent.app.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Обёртка над системным распознаванием речи (русский язык). */
class VoiceInput(context: Context, private val cb: Callback) : RecognitionListener {

    interface Callback {
        fun onVoiceReady()
        fun onVoiceResult(text: String)
        fun onVoiceError(code: Int)
    }

    private val sr: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null

    fun start() {
        val r = sr
        if (r == null) {
            cb.onVoiceError(-1)
            return
        }
        r.setRecognitionListener(this)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            }
        )
    }

    fun destroy() {
        sr?.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = cb.onVoiceReady()
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) = cb.onVoiceError(error)

    override fun onResults(results: Bundle?) {
        val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!r.isNullOrEmpty()) cb.onVoiceResult(r[0]) else cb.onVoiceError(SpeechRecognizer.ERROR_NO_MATCH)
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
