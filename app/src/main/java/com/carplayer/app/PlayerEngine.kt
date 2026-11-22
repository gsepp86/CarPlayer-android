package com.carplayer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager

/**
 * Motore di riproduzione intercambiabile. Due implementazioni:
 * - [MpEngine]: MediaPlayer (quello usato fino alla 1.5);
 * - [PcmEngine]: MediaCodec + AudioTrack, come fanno i lettori del Play Store.
 *
 * Sulle autoradio RK3326 il decoder MediaPlayer spesso non manda audio alle casse
 * (play acceso, ma silenzio) mentre un AudioTrack con PCM funziona: per questo il
 * motore PCM è quello preferito e MediaPlayer resta solo come ripiego.
 */
interface PlayerEngine {
    var onPrepared: (() -> Unit)?
    var onCompletion: (() -> Unit)?
    var onError: ((String) -> Unit)?

    fun load(path: String, startMs: Int)
    fun start(): Boolean
    fun pause()
    fun seekTo(ms: Int)
    fun setVolume(v: Float)
    fun setPreferredDevice(d: AudioDeviceInfo?)
    val positionMs: Int
    val durationMs: Int
    val isPlaying: Boolean
    fun release()
}

/** MediaPlayer, il motore storico. */
class MpEngine(private val ctx: Context) : PlayerEngine {

    private val mp = MediaPlayer()

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    init {
        mp.setOnPreparedListener { onPrepared?.invoke() }
        mp.setOnCompletionListener { onCompletion?.invoke() }
        mp.setOnErrorListener { _, what, extra ->
            onError?.invoke("MediaPlayer $what/$extra")
            true
        }
    }

    override fun load(path: String, startMs: Int) {
        mp.reset()
        mp.setAudioAttributes(attrs)
        mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setDataSource(path)
        mp.prepareAsync()
    }

    override fun start(): Boolean = try {
        mp.start()
        true
    } catch (e: Exception) {
        false
    }

    override fun pause() {
        try { if (mp.isPlaying) mp.pause() } catch (e: Exception) { }
    }

    override fun seekTo(ms: Int) {
        try { mp.seekTo(ms) } catch (e: Exception) { }
    }

    override fun setVolume(v: Float) {
        try { mp.setVolume(v, v) } catch (e: Exception) { }
    }

    override fun setPreferredDevice(d: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT >= 28 && d != null) {
            try { mp.setPreferredDevice(d) } catch (e: Exception) { }
        }
    }

    override val positionMs: Int
        get() = try { mp.currentPosition } catch (e: Exception) { 0 }

    override val durationMs: Int
        get() = try { mp.duration.coerceAtLeast(0) } catch (e: Exception) { 0 }

    override val isPlaying: Boolean
        get() = try { mp.isPlaying } catch (e: Exception) { false }

    override fun release() {
        try { mp.release() } catch (e: Exception) { }
    }
}
