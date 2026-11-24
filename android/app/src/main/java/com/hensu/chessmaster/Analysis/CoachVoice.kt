// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Speaks the coach's notes during game review (on-device TTS — nothing
 * leaves the phone). On by default; the player can mute it from the
 * analysis screen or Profile → Sound.
 */
class CoachVoice {
    private var enabledState by mutableStateOf(Defaults.standard.boolOrNull(key) ?: true)

    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            Defaults.standard.set(key, value)
            if (!value) stop()
        }

    var speaking by mutableStateOf(false)
        private set

    /** Created on the first `speak` (binding the TTS service at launch would cost every start). */
    private var synthesizer: TextToSpeech? = null
    private var ready = false
    /** Asked for before the engine finished starting; spoken as soon as it is ready. */
    private var pending: String? = null
    /** Only the latest utterance may clear `speaking` (a stopped one reports late). */
    private var utterance = 0
    private val main = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager
        get() = App.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Coach voice yields to other audio: other apps duck while it talks.
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()

    fun speak(text: String) {
        if (!enabled || App.arguments.contains("--uitest")) return
        val tts = synthesizer
        if (tts == null) {
            pending = text
            speaking = true
            start()
            return
        }
        if (!ready) {
            pending = text
            speaking = true
            return
        }
        tts.stop()
        runCatching { audioManager.requestAudioFocus(focusRequest) }
        utterance += 1
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "coach-$utterance")
    }

    fun stop() {
        pending = null
        utterance += 1
        synthesizer?.stop()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        speaking = false
    }

    private fun start() {
        synthesizer = TextToSpeech(App.context) { status ->
            main.post {
                val tts = synthesizer ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    pending = null
                    speaking = false
                    return@post
                }
                tts.setAudioAttributes(audioAttributes)
                tts.setSpeechRate(0.95f)
                tts.setPitch(1.02f)
                val voice = preferredVoice(tts)
                if (voice != null) tts.voice = voice else tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
                })
                ready = true
                val text = pending
                pending = null
                if (text != null) speak(text) else speaking = false
            }
        }
    }

    /** didFinish / didCancel. */
    private fun finished(utteranceId: String?) {
        main.post {
            if (utteranceId != "coach-$utterance") return@post
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            speaking = false
        }
    }

    companion object {
        private const val key = "coach.voiceEnabled"

        /**
         * The best-quality installed English voice (users with a high-quality
         * voice downloaded get it automatically).
         */
        private fun preferredVoice(tts: TextToSpeech): Voice? {
            val english = runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                // en-US first within a quality tier, then a stable order.
                .sortedWith(compareBy({ if (it.locale.country == "US") 0 else 1 }, { it.name }))
            return english.firstOrNull { it.quality >= Voice.QUALITY_VERY_HIGH }
                ?: english.firstOrNull { it.quality >= Voice.QUALITY_HIGH }
                ?: english.firstOrNull { it.locale == Locale.US }
        }
    }
}
