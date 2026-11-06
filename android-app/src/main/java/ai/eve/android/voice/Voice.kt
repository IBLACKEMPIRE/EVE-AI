package ai.eve.android.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.eve.core.avatar.VisemeMapper
import java.util.Locale

/** Platform speech recognition. Must be created and used on the main thread. */
class SpeechInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(locale: Locale = Locale.getDefault()) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onEnd(errorText(error))
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onFinal(text)
                onEnd(null)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    fun stop() { recognizer?.destroy(); recognizer = null }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
        else -> "Speech recognition error ($code)."
    }
}

/**
 * Platform TTS. Android gives character ranges while speaking (API 26+), which we map to
 * visemes to drive the avatar's mouth — good enough for a placeholder; a cloud TTS with
 * viseme timing replaces it in the production pipeline.
 */
class VoiceOutput(context: Context, private val onSpeaking: (Boolean) -> Unit, private val onMouth: (Float) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var current = ""
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = Locale.getDefault()
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { onSpeaking(true) } }
            override fun onDone(utteranceId: String?) { main.post { onMouth(0f); onSpeaking(false) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { onMouth(0f); onSpeaking(false) } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val chunk = current.substring(start.coerceIn(0, current.length), end.coerceIn(0, current.length))
                val frames = VisemeMapper.fromText(chunk)
                val peak = frames.maxOfOrNull { VisemeMapper.openness(it.viseme) } ?: 0f
                main.post { onMouth(peak) }
                main.postDelayed({ onMouth(peak * 0.3f) }, 120)
            }
        })
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        current = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eve-${System.nanoTime()}")
    }

    fun stop() { tts.stop(); onMouth(0f); onSpeaking(false) }
    fun shutdown() = tts.shutdown()
}
