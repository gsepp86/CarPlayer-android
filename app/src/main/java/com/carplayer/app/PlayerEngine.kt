package com.carplayer.app

import android.media.AudioDeviceInfo

/**
 * Motore di riproduzione usato da [PlaybackService].
 *
 * L'unica implementazione è [ExoEngine] (AndroidX Media3 / ExoPlayer): decodifica e
 * scrittura audio avvengono su thread interni alla libreria, quindi l'interfaccia
 * non viene mai bloccata dalle operazioni sull'audio.
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
