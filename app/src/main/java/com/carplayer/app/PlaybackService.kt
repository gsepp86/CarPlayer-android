package com.carplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * Motore di riproduzione. Vive in background (servizio in primo piano) così la musica
 * continua anche con il navigatore aperto, e risponde ai tasti del volante (MediaSession).
 */
class PlaybackService : Service() {

    interface Listener { fun onPlaybackChanged() }

    inner class LocalBinder : Binder() {
        fun get(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    var listener: Listener? = null

    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    var playing = false
        private set
    var cover: Bitmap? = null
        private set
    var shuffle = false
        private set
    var repeat = 0 // 0 = no, 1 = tutta la coda, 2 = brano singolo
        private set

    val current: Track? get() = queue.getOrNull(index)

    val positionMs: Int
        get() = if (prepared) try { player.currentPosition } catch (e: Exception) { 0 } else pendingPos

    val durationMs: Int
        get() = if (prepared) try { player.duration.coerceAtLeast(0) } catch (e: Exception) { 0 }
        else (current?.durationMs?.toInt() ?: 0)

    private lateinit var player: MediaPlayer
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var nm: NotificationManager
    private var prepared = false
    private var pendingPlay = false
    private var pendingPos = 0
    private var resumeOnFocus = false
    private var errors = 0
    private var isForeground = false
    private var coverToken = 0
    private var upcoming = -1
    private val history = ArrayList<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ---------------------------------------------------------------- ciclo di vita

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shuffle = Prefs.bool(this, "shuffle")
        repeat = Prefs.int(this, "repeat")

        player = MediaPlayer().apply {
            setOnPreparedListener { onPrepared() }
            setOnCompletionListener { onCompleted() }
            setOnErrorListener { _, _, _ -> onError(); true }
        }

        session = MediaSession(this, "CarPlayer")
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onStop() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        })
        session.isActive = true

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Riproduzione", NotificationManager.IMPORTANCE_LOW)
            )
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlay()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePosition()
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        @Suppress("DEPRECATION")
        audio.abandonAudioFocus(focusListener)
        session.release()
        player.release()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- comandi

    fun setQueue(list: List<Track>, start: Int, play: Boolean, posMs: Int = 0) {
        queue = list
        history.clear()
        errors = 0
        loadTrack(start, play, posMs)
    }

    fun play() {
        if (current == null) return
        if (!prepared) {
            pendingPlay = true
            playing = true
        } else {
            if (!requestFocus()) return
            player.start()
            playing = true
        }
        resumeOnFocus = false
        pushState()
    }

    fun pause() {
        if (prepared) try { if (player.isPlaying) player.pause() } catch (e: Exception) { }
        playing = false
        pendingPlay = false
        resumeOnFocus = false
        savePosition()
        pushState()
    }

    fun togglePlay() { if (playing) pause() else play() }

    fun next() {
        if (queue.isEmpty()) return
        val nx = nextIndex(false)
        if (nx >= 0) {
            history.add(index)
            if (history.size > 200) history.removeAt(0)
            loadTrack(nx, true)
        }
    }

    /** Salta direttamente al brano i della coda (usato dalle miniature). Sul brano attuale fa play/pausa. */
    fun playIndex(i: Int) {
        if (i !in queue.indices) return
        if (i == index) { togglePlay(); return }
        if (index >= 0) {
            history.add(index)
            if (history.size > 200) history.removeAt(0)
        }
        loadTrack(i, true)
    }

    private fun shuffleNext(): Int {
        val n = queue.size
        if (upcoming !in 0 until n || upcoming == index) {
            var r: Int
            do { r = (0 until n).random() } while (r == index)
            upcoming = r
        }
        return upcoming
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (prepared && positionMs > 3000) { seekTo(0); return }
        val p = when {
            shuffle && history.isNotEmpty() -> history.removeAt(history.lastIndex)
            index > 0 -> index - 1
            else -> queue.size - 1
        }
        loadTrack(p, true)
    }

    fun seekTo(ms: Int) {
        if (prepared) { player.seekTo(ms); pushState() } else pendingPos = ms
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        upcoming = -1
        Prefs.putBool(this, "shuffle", shuffle)
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        Prefs.putInt(this, "repeat", repeat)
    }

    fun savePosition() {
        val t = current ?: return
        Prefs.putStr(this, "lastPath", t.path)
        Prefs.putInt(this, "lastPos", positionMs)
    }

    // ---------------------------------------------------------------- interno

    private fun loadTrack(i: Int, play: Boolean, posMs: Int = 0) {
        val t = queue.getOrNull(i) ?: return
        index = i
        upcoming = -1
        prepared = false
        pendingPlay = play
        pendingPos = posMs
        playing = play
        try {
            player.reset()
            player.setAudioAttributes(attrs)
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(t.path)
            player.prepareAsync()
        } catch (e: Exception) {
            onError()
            return
        }
        Prefs.putStr(this, "lastPath", t.path)
        Prefs.putInt(this, "lastPos", posMs)
        loadCover(t)
        pushState()
    }

    private fun onPrepared() {
        prepared = true
        errors = 0
        if (pendingPos > 0) player.seekTo(pendingPos)
        if (pendingPlay) {
            if (requestFocus()) { player.start(); playing = true } else playing = false
        } else {
            playing = false
        }
        pendingPlay = false
        pushState()
    }

    private fun onCompleted() {
        if (repeat == 2) { player.seekTo(0); player.start(); return }
        val nx = nextIndex(true)
        if (nx < 0) {
            playing = false
            player.seekTo(0)
            pushState()
            return
        }
        history.add(index)
        if (history.size > 200) history.removeAt(0)
        loadTrack(nx, true)
    }

    private fun onError() {
        prepared = false
        errors++
        if (queue.size <= 1 || errors >= queue.size) {
            playing = false
            errors = 0
            pushState()
            return
        }
        val nx = nextIndex(false)
        if (nx >= 0) loadTrack(nx, playing) else { playing = false; pushState() }
    }

    private fun nextIndex(auto: Boolean): Int {
        val n = queue.size
        if (n == 0) return -1
        if (shuffle && n > 1) return shuffleNext()
        val nx = index + 1
        return if (nx < n) nx else if (repeat == 1 || !auto) 0 else -1
    }

    private fun loadCover(t: Track) {
        val token = ++coverToken
        cover = null
        io.execute {
            val b = Covers.load(t.path, 600)
            main.post {
                if (token == coverToken) { cover = b; pushState() }
            }
        }
    }

    // ---------------------------------------------------------------- audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { pause(); resumeOnFocus = true }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (prepared) player.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (prepared) player.setVolume(1f, 1f)
                if (resumeOnFocus) { resumeOnFocus = false; play() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean =
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    // ---------------------------------------------------------------- sessione e notifica

    private fun pushState() {
        updateSession()
        updateNotification()
        listener?.onPlaybackChanged()
    }

    private fun updateSession() {
        val t = current
        if (t != null) {
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
            cover?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(md.build())
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val st = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(st, positionMs.toLong(), if (playing) 1f else 0f)
                .build()
        )
    }

    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun actionIntent(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val t = current
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        b.setSmallIcon(R.drawable.ic_note)
            .setContentTitle(t?.title ?: "CarPlayer")
            .setContentText(t?.artist ?: "")
            .setLargeIcon(cover)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(R.drawable.ic_prev, "Precedente", actionIntent(ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pausa" else "Play",
                actionIntent(ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_next, "Successivo", actionIntent(ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun updateNotification() {
        if (current == null) return
        val n = buildNotification()
        if (playing) {
            if (!isForeground) isForeground = enterForeground(n)
            nm.notify(NOTIF_ID, n)
        } else {
            if (isForeground) {
                stopForeground(false)
                isForeground = false
            }
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun enterForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        const val ACTION_TOGGLE = "com.carplayer.app.TOGGLE"
        const val ACTION_NEXT = "com.carplayer.app.NEXT"
        const val ACTION_PREV = "com.carplayer.app.PREV"
    }
}
