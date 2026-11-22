package com.carplayer.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Motore unico: AndroidX Media3 ExoPlayer.
 *
 * Rispetto al vecchio MediaPlayer e al PCM scritto a mano:
 * - decodifica e scrittura sull'AudioTrack avvengono su thread interni alla libreria;
 * - l'AudioTrack è gestito da DefaultAudioSink, con buffer e riconnessioni corrette;
 * - nessuna chiamata nativa pesante (creazione/rilascio di AudioTrack) sul thread
 *   dell'interfaccia, che quindi non si blocca mai.
 *
 * È lo stesso percorso PCM che usano i lettori del Play Store, quindi su queste
 * autoradio RK3326 si sente anche quando il MediaPlayer di sistema resta muto.
 */
class ExoEngine(context: Context) : PlayerEngine {

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var prepared = false

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false // il focus lo gestisce il servizio, senza mai mettere in pausa
        )
        .setHandleAudioBecomingNoisy(false)
        .build()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> if (!prepared) {
                    prepared = true
                    onPrepared?.invoke()
                }
                Player.STATE_ENDED -> onCompletion?.invoke()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            prepared = false
            val detail = error.cause?.message ?: error.message ?: ""
            onError?.invoke((error.errorCodeName + ": " + detail).take(180))
        }
    }

    init {
        player.addListener(listener)
    }

    override fun load(path: String, startMs: Int) {
        prepared = false
        val item = MediaItem.fromUri(Uri.fromFile(File(path)))
        player.setMediaItem(item, startMs.coerceAtLeast(0).toLong())
        player.prepare()
    }

    override fun start(): Boolean {
        player.playWhenReady = true
        player.play()
        return true
    }

    override fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    override fun seekTo(ms: Int) {
        player.seekTo(ms.coerceAtLeast(0).toLong())
    }

    override fun setVolume(v: Float) {
        player.volume = v.coerceIn(0f, 1f)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun setPreferredDevice(d: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT >= 23) {
            try { player.setPreferredAudioDevice(d) } catch (e: Exception) { }
        }
    }

    override val positionMs: Int
        get() = try { player.currentPosition.coerceAtLeast(0).toInt() } catch (e: Exception) { 0 }

    override val durationMs: Int
        get() = try {
            val d = player.duration
            if (d == C.TIME_UNSET || d <= 0) 0 else d.toInt()
        } catch (e: Exception) { 0 }

    override val isPlaying: Boolean
        get() = try { player.isPlaying } catch (e: Exception) { false }

    override fun release() {
        prepared = false
        try { player.removeListener(listener) } catch (e: Exception) { }
        try { player.stop() } catch (e: Exception) { }
        try { player.release() } catch (e: Exception) { }
    }
}
