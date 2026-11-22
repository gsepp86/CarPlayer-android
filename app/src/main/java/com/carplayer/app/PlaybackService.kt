package com.carplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Motore di riproduzione. Vive in background (servizio in primo piano) così la musica
 * continua anche con il navigatore aperto, e risponde ai tasti del volante (MediaSession).
 *
 * L'audio passa da [ExoEngine] (AndroidX Media3 ExoPlayer): decodifica e uscita su
 * thread propri, nessun blocco dell'interfaccia e lo stesso percorso PCM dei lettori
 * del Play Store, che su queste autoradio si sente.
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

    /** Ultimo errore del motore, mostrato nelle impostazioni per capire cosa non va. */
    var lastError = ""
        private set

    val current: Track? get() = queue.getOrNull(index)

    val positionMs: Int
        get() = engine?.positionMs ?: pendingPos

    val durationMs: Int
        get() {
            val d = engine?.durationMs ?: 0
            return if (d > 0) d else (current?.durationMs?.toInt() ?: 0)
        }

    private var engine: PlayerEngine? = null
    private var prepared = false
    /** 0 = PCM diretto (quello del "Prova audio"), 1 = ExoPlayer se il PCM fallisce. */
    private var engineStep = 0
    private var loading = false
    private var pendingPlay = false
    private var pendingPos = 0
    private var errors = 0
    private var isForeground = false
    private var coverToken = 0
    private var upcoming = -1
    private val history = ArrayList<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var route: AudioRoute
    private lateinit var nm: NotificationManager

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ---------------------------------------------------------------- ciclo di vita

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        route = AudioRoute(this)
        route.setSink(Prefs.int(this, "outSink"))
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shuffle = Prefs.bool(this, "shuffle")
        repeat = Prefs.int(this, "repeat")

        session = MediaSession(this, "CarPlayer")
        if (Build.VERSION.SDK_INT >= 34) session.setPlaybackToLocal(attrs)
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
        abandonFocus()
        session.release()
        engine?.release()
        engine = null
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
        val t = current ?: return
        if (!prepared || engine == null) {
            // prepare fallita o player perso: si riparte davvero, non si resta in attesa
            pendingPlay = true
            playing = true
            if (!loading) startEngine(t.playUri, pendingPos)
            pushState()
            return
        }
        // il focus è best-effort: su molte autoradio viene negato (radio FM o app
        // di sistema che lo tengono) ma la musica deve partire lo stesso
        requestFocus()
        ensureAudible()
        engine?.setVolume(1f)
        playing = engine?.start() ?: false
        if (!playing) lastError = "Avvio rifiutato dal motore"
        pushState()
    }

    fun pause() {
        engine?.pause()
        playing = false
        pendingPlay = false
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

    /**
     * Fa partire subito il brano i della coda (tocco su miniatura o riga della lista).
     * Non mette mai in pausa: se il brano è già caricato ma fermo, lo avvia davvero.
     */
    fun playAt(i: Int) {
        if (i !in queue.indices) return
        if (i == index) {
            if (!playing || engine?.isPlaying != true) play()
            return
        }
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
        if (prepared) {
            engine?.seekTo(ms)
            pushState()
        } else {
            pendingPos = ms
        }
    }

    /** Uscita scelta a mano (0 = automatica). */
    val outputId: Int get() = route.manualSink

    /** Elenco per le impostazioni: prima l'automatico, poi le uscite disponibili. */
    fun outputOptions(): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>()
        l.add(AudioRoute.AUTO to "Automatico (consigliato)")
        for (d in route.outputs()) l.add(d.id to route.labelOf(d))
        return l
    }

    /**
     * Cambia uscita dalle impostazioni. Ricrea il motore sulla rotta nuova, così
     * l'audio riparte dallo stesso punto e non resta agganciato alla vecchia uscita.
     */
    fun setOutput(id: Int) {
        if (route.manualSink == id) return
        route.setSink(id)
        Prefs.putInt(this, "outSink", id)
        if (current == null) return
        val pos = positionMs
        loadTrack(index, playing, pos)
    }

    /**
     * Cambia a mano l'uscita audio (tocco sullo stato in alto): serve sulle autoradio
     * che continuano a mandare la musica sulla rotta sbagliata.
     */
    fun cycleOutput(): String {
        val label = route.cycleSink()
        Prefs.putInt(this, "outSink", route.manualSink)
        if (current != null) {
            val pos = positionMs
            loadTrack(index, playing, pos)
        }
        return label
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

    // ---------------------------------------------------------------- motore

    private fun loadTrack(i: Int, play: Boolean, posMs: Int = 0) {
        val t = queue.getOrNull(i) ?: return
        index = i
        upcoming = -1
        prepared = false
        engineStep = 0
        pendingPlay = play
        pendingPos = posMs
        playing = play
        startEngine(t.playUri, posMs)
        Prefs.putStr(this, "lastPath", t.path)
        Prefs.putInt(this, "lastPos", posMs)
        loadCover(t)
        pushState()
    }

    private fun startEngine(source: String, startMs: Int) {
        loading = true
        prepared = false
        // PCM diretto come prima scelta: scrive su AudioTrack, la stessa uscita del
        // "Prova audio" che si sente. Se non riesce, si passa a ExoPlayer.
        val usePcm = engineStep == 0
        val existing = engine
        val reuse = if (usePcm) existing is PcmEngine else existing is ExoEngine
        var e = existing
        if (e == null || !reuse) {
            e?.release()
            val n: PlayerEngine = if (usePcm) PcmEngine(this) else ExoEngine(this)
            n.onPrepared = { onPrepared() }
            n.onCompletion = { onCompleted() }
            n.onError = { msg -> handleEngineError(msg) }
            engine = n
            e = n
        }
        e.setVolume(1f)
        route.chosen()?.let { e.setPreferredDevice(it) }
        try {
            e.load(source, startMs)
        } catch (ex: Exception) {
            handleEngineError(ex.message ?: "caricamento")
        }
    }

    private fun onPrepared() {
        prepared = true
        loading = false
        errors = 0
        lastError = ""
        engine?.setVolume(1f)
        if (pendingPos > 0) engine?.seekTo(pendingPos)
        if (pendingPlay) {
            requestFocus()
            ensureAudible()
            playing = engine?.start() ?: false
            if (!playing) lastError = "Avvio rifiutato dal motore"
        } else {
            playing = false
        }
        pendingPlay = false
        pushState()
    }

    private fun onCompleted() {
        if (repeat == 2) {
            engine?.seekTo(0)
            engine?.start()
            playing = true
            pushState()
            return
        }
        val nx = nextIndex(true)
        if (nx < 0) {
            playing = false
            engine?.seekTo(0)
            pushState()
            return
        }
        history.add(index)
        if (history.size > 200) history.removeAt(0)
        loadTrack(nx, true)
    }

    /**
     * Errore del motore. Prima si prova l'altro motore (PCM → ExoPlayer) sullo stesso
     * brano e dallo stesso punto; se falliscono entrambi si passa al brano dopo.
     */
    private fun handleEngineError(msg: String) {
        lastError = if (engineStep == 0) "PCM $msg" else "Exo $msg"
        val t = current
        if (engineStep == 0 && t != null) {
            engineStep = 1
            engine?.release()
            engine = null
            startEngine(t.playUri, pendingPos)
            pushState()
            return
        }
        loading = false
        failTrack()
    }

    private fun failTrack() {
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
            val b = Covers.load(this@PlaybackService, t.playUri, t.path, 600)
            main.post {
                if (token == coverToken) { cover = b; pushState() }
            }
        }
    }

    // ---------------------------------------------------------------- audio focus

    /**
     * Su queste autoradio il focus viene revocato appena parte la musica (radio FM o
     * app di sistema che lo tengono): reagire mettendo in pausa faceva fermare il brano
     * un istante dopo il tocco, e sembrava che la traccia fosse solo selezionata.
     * Quindi la musica non si ferma mai per il focus: al massimo si abbassa il volume
     * durante gli avvisi del navigatore.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine?.setVolume(0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> engine?.setVolume(1f)
        }
    }

    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }
    }

    /**
     * Alcune autoradio tengono il canale multimediale mutato o a volume zero: senza
     * questo il play va avanti ma non esce un suono. Si alza solo se serve.
     */
    @Suppress("DEPRECATION")
    private fun ensureAudible() {
        try {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 7) / 10, 0)
            }
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        val req = focusRequest
        val granted = if (req != null) {
            audio.requestAudioFocus(req)
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val req = focusRequest
        if (req != null) audio.abandonAudioFocusRequest(req) else audio.abandonAudioFocus(focusListener)
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
