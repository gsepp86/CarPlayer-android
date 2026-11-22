package com.carplayer.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Riproduzione in PCM: MediaExtractor legge il file, MediaCodec lo decodifica e
 * AudioTrack manda i campioni all'uscita. È lo stesso percorso dei lettori del
 * Play Store (AIMP & co.), che su queste autoradio si sentono mentre MediaPlayer
 * resta muto.
 *
 * Il lavoro avviene su un thread dedicato: l'interfaccia non si blocca mai.
 */
class PcmEngine : PlayerEngine {

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var prepared = false
    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var duration = 0
    @Volatile private var pos = 0
    @Volatile private var seekToMs = -1
    @Volatile private var vol = 1f
    @Volatile private var preferred: AudioDeviceInfo? = null

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var token = 0

    override val positionMs: Int get() = pos
    override val durationMs: Int get() = duration
    override val isPlaying: Boolean get() = playing && prepared

    override fun load(path: String, startMs: Int) {
        release()
        val my = token
        released = false
        prepared = false
        playing = false
        duration = 0
        pos = startMs
        seekToMs = startMs
        val w = Thread({ decode(path, startMs, my) }, "pcm")
        worker = w
        w.start()
    }

    override fun start(): Boolean {
        playing = true
        val t = track ?: return true
        return try { t.play(); true } catch (e: Exception) { false }
    }

    override fun pause() {
        playing = false
        try { track?.pause() } catch (e: Exception) { }
    }

    override fun seekTo(ms: Int) {
        pos = ms
        seekToMs = ms
    }

    override fun setVolume(v: Float) {
        vol = v
        try { track?.setVolume(v) } catch (e: Exception) { }
    }

    override fun setPreferredDevice(d: AudioDeviceInfo?) {
        preferred = d
        if (Build.VERSION.SDK_INT >= 28 && d != null) {
            try { track?.setPreferredDevice(d) } catch (e: Exception) { }
        }
    }

    override fun release() {
        token++
        released = true
        playing = false
        prepared = false
        // rilasciare l'AudioTrack sblocca subito una write in corso sul thread di decodifica
        val t = track
        track = null
        try { worker?.interrupt() } catch (e: Exception) { }
        try { t?.pause() } catch (e: Exception) { }
        try { t?.flush() } catch (e: Exception) { }
        try { t?.release() } catch (e: Exception) { }
        try { worker?.join(600) } catch (e: Exception) { }
        worker = null
    }

    // ---------------------------------------------------------------- interno

    private fun decode(path: String, startMs: Int, my: Int) {
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        var at: AudioTrack? = null
        try {
            ex = MediaExtractor()
            ex.setDataSource(path)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                fail("Nessuna traccia audio", my)
                return
            }
            ex.selectTrack(trackIndex)

            duration = if (format.containsKey(MediaFormat.KEY_DURATION))
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L).toInt() else 0

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val mask = channelMask(ch)
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                fail("AudioTrack non disponibile", my)
                return
            }
            at = AudioTrack.Builder()
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
                .setBufferSizeInBytes(min * 2)
                .build()
            at.setVolume(vol)
            if (Build.VERSION.SDK_INT >= 28) {
                preferred?.let { try { at.setPreferredDevice(it) } catch (e: Exception) { } }
            }
            track = at

            if (startMs > 0) ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            pos = startMs
            prepared = true
            main.post { if (!released && token == my) onPrepared?.invoke() }

            loop(ex, codec, at, startMs, my)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName, my)
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { at?.pause() } catch (e: Exception) { }
            try { at?.flush() } catch (e: Exception) { }
            try { at?.release() } catch (e: Exception) { }
            try { ex?.release() } catch (e: Exception) { }
            if (track === at) track = null
        }
    }

    private fun loop(ex: MediaExtractor, codec: MediaCodec, at: AudioTrack, startMs: Int, my: Int) {
        val info = MediaCodec.BufferInfo()
        var baseUs = startMs * 1000L
        var inputDone = false
        var eos = false

        while (!released) {
            val sk = seekToMs
            if (sk >= 0) {
                seekToMs = -1
                try { at.pause() } catch (e: Exception) { }
                try { at.flush() } catch (e: Exception) { }
                codec.flush()
                ex.seekTo(sk * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                baseUs = sk * 1000L
                pos = sk
                inputDone = false
                eos = false
            }

            if (!playing) {
                try { Thread.sleep(15) } catch (e: InterruptedException) { return }
                continue
            }
            if (at.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try { at.play() } catch (e: Exception) { }
            }

            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
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

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        var left = info.size
                        while (left > 0 && !released) {
                            val w = at.write(buf, left, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) break
                            left -= w
                        }
                    }
                    val p = ((info.presentationTimeUs - baseUs) / 1000L).toInt()
                    if (p >= 0) pos = p
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            }

            if (eos) {
                // lascia finire i campioni già nell'AudioTrack prima di dichiarare la fine
                val ms = ((at.bufferSizeInFrames.toLong() * 1000L) / 44100L).coerceIn(150L, 1200L)
                try { Thread.sleep(ms) } catch (e: InterruptedException) { return }
                if (!released) {
                    playing = false
                    main.post { if (!released && token == my) onCompletion?.invoke() }
                }
                return
            }
        }
    }

    private fun channelMask(ch: Int): Int = when (ch) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun fail(msg: String, my: Int) {
        prepared = false
        playing = false
        main.post { if (!released && token == my) onError?.invoke(msg) }
    }
}
