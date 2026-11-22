package com.carplayer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * Riproduzione diretta: MediaExtractor + MediaCodec + AudioTrack, lo stesso schema
 * dei lettori del Play Store e del pulsante "Prova audio" (che su queste autoradio
 * si sente). ExoPlayer resta come ripiego.
 *
 * Tutto avviene su un thread dedicato: il thread dell'interfaccia non crea, non
 * rilascia e non attende mai l'audio. Le scritture sull'AudioTrack sono NON
 * bloccanti, così pausa, cambio brano e chiusura fermano il ciclo subito, senza
 * restare appesi all'HAL audio.
 */
class PcmEngine(private val ctx: Context) : PlayerEngine {

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var stop = false
    @Volatile private var prepared = false
    @Volatile private var playing = false
    @Volatile private var duration = 0
    @Volatile private var pos = 0
    @Volatile private var seekTo = -1
    @Volatile private var vol = 1f
    @Volatile private var device: AudioDeviceInfo? = null

    /** Ogni caricamento ha un numero: i thread vecchi si fermano da soli. */
    @Volatile private var generation = 0
    private var worker: Thread? = null

    override val positionMs: Int get() = pos
    override val durationMs: Int get() = duration
    override val isPlaying: Boolean get() = playing && prepared

    override fun load(source: String, startMs: Int) {
        halt()
        val gen = generation
        stop = false
        prepared = false
        playing = false
        duration = 0
        pos = startMs
        seekTo = startMs
        val w = Thread({ run(source, startMs, gen) }, "pcm")
        worker = w
        w.start()
    }

    override fun start(): Boolean {
        playing = true
        return true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(ms: Int) {
        pos = ms
        seekTo = ms
    }

    override fun setVolume(v: Float) {
        vol = v
    }

    override fun setPreferredDevice(d: AudioDeviceInfo?) {
        device = d
    }

    override fun release() {
        halt()
    }

    /** Ferma il thread senza mai attenderlo dal thread chiamante. */
    private fun halt() {
        stop = true
        playing = false
        prepared = false
        generation++
        try { worker?.interrupt() } catch (e: Exception) { }
        worker = null
    }

    private fun alive(gen: Int) = !stop && gen == generation

    // ---------------------------------------------------------------- thread audio

    private fun run(source: String, startMs: Int, gen: Int) {
        var afd: android.content.res.AssetFileDescriptor? = null
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        var at: AudioTrack? = null
        var rate = 44100
        try {
            if (!alive(gen)) return
            ex = MediaExtractor()
            val uri = Uri.parse(source)
            if (uri.scheme == "content") {
                // su USB il percorso file dà EACCES: il descrittore del provider no
                afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: throw IOException("file non accessibile")
                val fd = afd.fileDescriptor ?: throw IOException("descrittore nullo")
                ex.setDataSource(fd, afd.startOffset, afd.length)
            } else {
                ex.setDataSource(source)
            }

            var trackIndex = -1
            var found: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { trackIndex = i; found = f; break }
            }
            val format = found
            if (trackIndex < 0 || format == null) throw IOException("nessuna traccia audio")
            ex.selectTrack(trackIndex)

            duration = if (format.containsKey(MediaFormat.KEY_DURATION))
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L).toInt() else 0
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("MIME assente")
            rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val mask = channelMask(ch)
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IOException("uscita PCM non disponibile")
            if (!alive(gen)) return

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val buffer = min * 3
            val t = if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(buffer)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC, rate, mask,
                    AudioFormat.ENCODING_PCM_16BIT, buffer, AudioTrack.MODE_STREAM
                )
            }
            at = t
            t.setVolume(vol)
            if (Build.VERSION.SDK_INT >= 23) {
                try { device?.let { t.setPreferredDevice(it) } } catch (e: Exception) { }
            }

            if (startMs > 0) ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            pos = startMs
            prepared = true
            main.post { if (alive(gen)) onPrepared?.invoke() }

            decode(ex, codec, t, startMs, rate, gen)
        } catch (e: Throwable) {
            if (alive(gen)) main.post { if (alive(gen)) onError?.invoke(short(e)) }
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { at?.pause() } catch (e: Exception) { }
            try { at?.flush() } catch (e: Exception) { }
            try { at?.release() } catch (e: Exception) { }
            try { ex?.release() } catch (e: Exception) { }
            try { afd?.close() } catch (e: Exception) { }
        }
    }

    private fun decode(
        ex: MediaExtractor,
        codec: MediaCodec,
        at: AudioTrack,
        startMs: Int,
        rate: Int,
        gen: Int
    ) {
        val info = MediaCodec.BufferInfo()
        var baseUs = startMs * 1000L
        var inputDone = false
        var eos = false
        var appliedVol = vol

        while (alive(gen)) {
            val sk = seekTo
            if (sk >= 0) {
                seekTo = -1
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { at.pause() } catch (e: Exception) { }
                }
                try { at.flush() } catch (e: Exception) { }
                codec.flush()
                ex.seekTo(sk * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                baseUs = sk * 1000L
                pos = sk
                inputDone = false
                eos = false
            }

            if (!playing) {
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { at.pause() } catch (e: Exception) { }
                }
                sleepQuiet(20)
                continue
            }

            if (appliedVol != vol) {
                appliedVol = vol
                try { at.setVolume(vol) } catch (e: Exception) { }
            }
            if (at.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try { at.play() } catch (e: Exception) { }
            }

            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val n = if (buf == null) -1 else ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        var left = info.size
                        var stuck = 0
                        while (left > 0 && alive(gen)) {
                            val w = at.write(buf, left, AudioTrack.WRITE_NON_BLOCKING)
                            if (w > 0) {
                                left -= w
                                stuck = 0
                            } else {
                                sleepQuiet(4)
                                if (++stuck > 400) break // ~1,6 s: evita cicli infiniti
                            }
                        }
                    }
                    val p = ((info.presentationTimeUs - baseUs) / 1000L).toInt()
                    if (p >= 0) pos = p
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            }

            if (eos) {
                // lascia svuotare i campioni già nell'AudioTrack prima della fine
                val drain = ((at.bufferSizeInFrames.toLong() * 1000L) / rate).coerceIn(120L, 1000L)
                var waited = 0L
                while (waited < drain && alive(gen)) {
                    sleepQuiet(30)
                    waited += 30
                }
                if (alive(gen)) {
                    playing = false
                    main.post { if (alive(gen)) onCompletion?.invoke() }
                }
                return
            }
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun channelMask(ch: Int): Int = when (ch) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun short(e: Throwable): String {
        val m = e.message ?: ""
        return if (m.isBlank()) e.javaClass.simpleName
        else (e.javaClass.simpleName + ": " + m).take(160)
    }
}
