package com.blaze.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Speech in (Android's recognizer) and speech out (TextToSpeech). Main thread only. */
object Voice {
    enum class State { IDLE, LISTENING, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial
    /** Mic level 0..1 while listening, for the pulsing ring. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Set by the app (MainActivity / assistant session) to ask the chat screen to start listening. */
    val listenRequests = MutableStateFlow(0)

    /** True when the current turn started by voice, so the reply is read aloud. */
    @Volatile var lastTurnByVoice = false

    private lateinit var app: Context
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { _state.value = State.SPEAKING }
                    override fun onDone(id: String?) {
                        _state.value = State.IDLE
                        // Hands-free: after answering a spoken question, listen again for the follow-up.
                        if (com.blaze.assistant.agent.Settings.bool("voice_loop", false) && lastTurnByVoice && id?.startsWith("blaze-") == true) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                listen({ t -> if (t.isNotBlank()) com.blaze.assistant.agent.Agent.send(t, byVoice = true) }, { })
                            }, 250)
                        } else WakeWordService.resume(app)
                    }
                    @Deprecated("old api") override fun onError(id: String?) { _state.value = State.IDLE; WakeWordService.resume(app) }
                })
            }
        }
    }

    fun available() = SpeechRecognizer.isRecognitionAvailable(app)

    /** Listen once. [onText] gets the final words (blank when nothing was heard). */
    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        stopSpeaking()
        cancelListening()
        if (!available()) { onError("Speech recognition isn't available on this phone."); return }
        WakeWordService.pause(app)  // only one app can hold the mic
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        _partial.value = ""
        _state.value = State.LISTENING
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { _level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _partial.value = it }
            }
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish(); onText(text)
            }
            override fun onError(code: Int) {
                finish()
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onText("")
                else onError(when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Blaze needs microphone permission."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a connection right now."
                    else -> "Couldn't hear that (code $code)."
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        r.startListening(i)
    }

    private fun finish() {
        _state.value = State.IDLE; _level.value = 0f
        recognizer?.destroy(); recognizer = null
        WakeWordService.resume(app)
    }

    fun cancelListening() {
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        if (_state.value == State.LISTENING) { _state.value = State.IDLE; WakeWordService.resume(app) }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        WakeWordService.pause(app)
        val clean = text.replace(Regex("[*_`#>]"), "").take(3900)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "blaze-${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        if (_state.value == State.SPEAKING) { _state.value = State.IDLE; WakeWordService.resume(app) }
    }
}
