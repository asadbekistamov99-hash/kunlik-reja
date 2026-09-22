package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    object Processing : VoiceState()
    data class Speaking(val message: String) : VoiceState()
    data class Error(val errorMessage: String) : VoiceState()
}

class JarvisVoiceManager(private val context: Context) : RecognitionListener, TextToSpeech.OnInitListener {

    private val TAG = "JarvisVoiceManager"

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    var onCommandRecognized: ((String) -> Unit)? = null

    init {
        initSpeechRecognizer()
        initTextToSpeech()
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@JarvisVoiceManager)
            }
        } else {
            Log.w(TAG, "Speech recognition not available on this device")
        }
    }

    private var pendingSpeechText: String? = null

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var result = textToSpeech?.setLanguage(Locale("uz"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = textToSpeech?.setLanguage(Locale("ru"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.setLanguage(Locale.getDefault())
                }
            }
            textToSpeech?.setSpeechRate(1.05f)
            textToSpeech?.setPitch(1.0f)
            isTtsReady = true

            textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    _voiceState.value = VoiceState.Idle
                }
                override fun onError(utteranceId: String?) {
                    _voiceState.value = VoiceState.Idle
                }
            })

            pendingSpeechText?.let {
                speak(it)
                pendingSpeechText = null
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    fun startListening() {
        stopSpeaking()
        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "uz")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis sizni eshitmoqda...")
        }
        try {
            _recognizedText.value = ""
            _voiceState.value = VoiceState.Listening
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}")
            _voiceState.value = VoiceState.Error("Ovoz tanib olishni boshlashda xatolik yuz berdi")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        _voiceState.value = VoiceState.Idle
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (isTtsReady && textToSpeech != null) {
            _voiceState.value = VoiceState.Speaking(text)
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_UTTERANCE_${System.currentTimeMillis()}")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_UTTERANCE_${System.currentTimeMillis()}")
        } else {
            pendingSpeechText = text
        }
    }

    fun stopSpeaking() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
    }

    fun setProcessingState() {
        _voiceState.value = VoiceState.Processing
    }

    fun setIdleState() {
        _voiceState.value = VoiceState.Idle
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }

    // RecognitionListener Callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        _voiceState.value = VoiceState.Listening
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _voiceState.value = VoiceState.Processing
    }

    override fun onError(error: Int) {
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Ovoz tushunilmadi, qaytadan gapiring"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ovoz eshitilmadi"
            SpeechRecognizer.ERROR_AUDIO -> "Audio yozishda xatolik"
            else -> "Ovozli buyruq xatosi ($error)"
        }
        _voiceState.value = VoiceState.Error(errorMsg)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        _recognizedText.value = text
        if (text.isNotBlank()) {
            _voiceState.value = VoiceState.Processing
            onCommandRecognized?.invoke(text)
        } else {
            _voiceState.value = VoiceState.Idle
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        matches?.firstOrNull()?.let {
            _recognizedText.value = it
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
