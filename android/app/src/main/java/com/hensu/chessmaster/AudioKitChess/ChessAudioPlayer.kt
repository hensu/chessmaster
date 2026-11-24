// AudioKitChess — Chessmaster (Android)
// GPL-3.0-or-later
//
// All bundled sounds are original synthesized audio (no third-party assets).

package com.hensu.chessmaster

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

enum class SoundEffect(val rawValue: String) {
    move("move"),
    capture("capture"),
    castle("castle"),
    check("check"),
    promote("promote"),
    gameStart("game-start"),
    gameEnd("game-end"),
    lowTime("low-time");

    companion object {
        fun fromRaw(raw: String): SoundEffect? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The bundled sound for an effect (res/raw; "game-start" is `game_start` there). */
private val SoundEffect.resource: Int
    get() = when (this) {
        SoundEffect.move -> R.raw.move
        SoundEffect.capture -> R.raw.capture
        SoundEffect.castle -> R.raw.castle
        SoundEffect.check -> R.raw.check
        SoundEffect.promote -> R.raw.promote
        SoundEffect.gameStart -> R.raw.game_start
        SoundEffect.gameEnd -> R.raw.game_end
        SoundEffect.lowTime -> R.raw.low_time
    }

/**
 * Low-latency sound playback: every effect is preloaded into a `SoundPool`
 * with a small pool of streams (a player per play has audible lag for rapid
 * move sequences). Background music loops on its own `MediaPlayer` with
 * independent volume.
 */
class ChessAudioPlayer {
    private var soundsEnabledState by mutableStateOf(Defaults.standard.boolOrNull("audio.sounds") ?: true)
    private var musicEnabledState by mutableStateOf(Defaults.standard.boolOrNull("audio.music") ?: false)

    var soundsEnabled: Boolean
        get() = soundsEnabledState
        set(value) {
            soundsEnabledState = value
            Defaults.standard.set("audio.sounds", value)
        }
    var musicEnabled: Boolean
        get() = musicEnabledState
        set(value) {
            musicEnabledState = value
            Defaults.standard.set("audio.music", value)
            if (value) startMusic() else stopMusic()
        }

    private var pool: SoundPool? = null
    private val effectSamples = mutableMapOf<SoundEffect, Int>()
    private val loadedSamples = mutableSetOf<Int>()
    /** An effect asked for while its sample was still loading: played the moment it is ready. */
    private var pendingSample: Int? = null
    private var music: MediaPlayer? = null
    /** Music is meant to be playing (it pauses while the app is in the background). */
    private var musicPlaying = false
    private var prepared = false

    fun play(effect: SoundEffect) {
        if (!soundsEnabled) return
        prepareIfNeeded()
        val sample = effectSamples[effect] ?: return
        if (sample in loadedSamples) {
            pool?.play(sample, 1f, 1f, 1, 0, 1f)
        } else {
            pendingSample = sample
        }
    }

    fun startMusicIfEnabled() {
        if (musicEnabled) startMusic()
    }

    // MARK: - Setup

    private fun prepareIfNeeded() {
        if (prepared) return
        prepared = true

        // Like the iOS ambient category: plays alongside other apps' audio (no audio focus is
        // requested) and stops when the app leaves the foreground.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        pool.setOnLoadCompleteListener { soundPool, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedSamples.add(sampleId)
            if (pendingSample == sampleId) {
                pendingSample = null
                if (soundsEnabled) soundPool.play(sampleId, 1f, 1f, 1, 0, 1f)
            }
        }
        for (effect in SoundEffect.entries) {
            val sample = runCatching { pool.load(App.context, effect.resource, 1) }.getOrNull() ?: continue
            effectSamples[effect] = sample
        }
        this.pool = pool

        music = runCatching { MediaPlayer.create(App.context, R.raw.music_loop) }.getOrNull()?.apply {
            isLooping = true
            setVolume(0.5f, 0.5f)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (musicPlaying) runCatching { music?.start() }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (musicPlaying) runCatching { music?.pause() }
            }
        })
    }

    private fun startMusic() {
        prepareIfNeeded()
        val music = music ?: return
        if (musicPlaying) return
        musicPlaying = true
        runCatching { music.start() }
    }

    private fun stopMusic() {
        val music = music ?: return
        musicPlaying = false
        runCatching {
            if (music.isPlaying) music.pause()
            music.seekTo(0)
        }
    }
}
