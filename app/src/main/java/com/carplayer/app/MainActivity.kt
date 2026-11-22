package com.carplayer.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity(), PlaybackService.Listener {

    private var accent = 0
    private val white = 0xFFFFFFFF.toInt()
    private val gray = 0xFF9AA3AF.toInt()
    private val heartRed = 0xFFFF4D6D.toInt()
    private var baseBg = 0

    private var svc: PlaybackService? = null
    private var library: List<Track> = emptyList()
    private var shown: List<Track> = emptyList()
    private var folder: String? = null
    private var viewFavs = false
    private var favSet: Set<String> = emptySet()
    private var restored = false
    private var userSeeking = false
    private var lastCover: Bitmap? = null
    private var lastShownPath: String? = null
    private var bgColor = 0

    private lateinit var root: View
    private lateinit var disc: DiscView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBrand: TextView
    private lateinit var tabFolder: TextView
    private lateinit var tabFavs: TextView
    private lateinit var heart: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var list: ListView
    private lateinit var eq: EqView
    private lateinit var strip: HorizontalScrollView
    private lateinit var stripBox: LinearLayout
    private val adapter = TrackAdapter()
    private val ui = Handler(Looper.getMainLooper())

    // ---- pannello di destra: si nasconde con uno swipe e la parte restante si allarga
    private var rightCol: View? = null
    private var centerCol: View? = null
    private var discCol: View? = null
    private var showList: View? = null
    private var listHidden = false
    private var downX = 0f
    private var downY = 0f
    private var downInList = false

    // ---- striscia di miniature: finestra di al massimo 81 brani attorno a quello in esecuzione
    private var stripQueue: List<Track>? = null
    private var stripFrom = 0
    private var stripTo = 0
    private var stripCur = -1
    private var stripTouchAt = 0L

    @Volatile
    private var wanted: Set<String> = emptySet()
    private val thumbs = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 100
    }
    private val noThumb = HashSet<String>()
    private val loading = HashSet<String>()
    private val thumbExec = Executors.newSingleThreadExecutor()
    private val dp: Float get() = resources.displayMetrics.density
    private val am by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var listFont: android.graphics.Typeface? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val s = (b as PlaybackService.LocalBinder).get()
            svc = s
            s.listener = this@MainActivity
            tryRestore()
            render()
        }

        override fun onServiceDisconnected(n: ComponentName?) { svc = null }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = svc
            if (s != null && !userSeeking) {
                val p = s.positionMs
                seek.progress = p
                tvPos.text = fmt(p)
            }
            ui.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = Themes.accent(this)
        baseBg = Themes.bg(this)
        bgColor = baseBg
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        disc = findViewById(R.id.disc)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        tvBrand = findViewById(R.id.tvBrand)
        tabFolder = findViewById(R.id.tabFolder)
        tabFavs = findViewById(R.id.tabFavs)
        heart = findViewById(R.id.btnHeart)
        btnPlay = findViewById(R.id.btnPlay)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        seek = findViewById(R.id.seek)
        list = findViewById(R.id.list)
        eq = findViewById(R.id.eq)
        strip = findViewById(R.id.strip)
        stripBox = findViewById(R.id.stripBox)
        rightCol = findViewById(R.id.rightCol)
        centerCol = findViewById(R.id.centerCol)
        discCol = findViewById(R.id.discCol)
        showList = findViewById(R.id.showList)

        // carattere robotico per titolo, tempi, barra superiore e orologio
        val robo = Fonts.display(this)
        val roboViews = listOf<TextView>(
            tvTitle, tvPos, tvDur, tvStatus, tvBrand,
            findViewById(R.id.clock), findViewById(R.id.dateClock)
        )
        for (v in roboViews) v.typeface = robo
        // schede, lista e didascalie in Barlow Bold: largo e leggibile, non condensato
        val read = Fonts.legible(this)
        tabFolder.typeface = read
        tabFavs.typeface = read
        listFont = read
        findViewById<View>(R.id.dateClock).visibility =
            if (resources.getBoolean(R.bool.show_date)) View.VISIBLE else View.GONE

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> playFromList(pos) }
        // le righe non devono prendere il focus: il tocco deve arrivare alla lista
        list.setItemsCanFocus(false)
        list.isClickable = true
        strip.setOnTouchListener { _, _ -> stripTouchAt = System.currentTimeMillis(); false }
        showList?.setOnClickListener { setListHidden(false) }

        disc.setOnClickListener { svc?.togglePlay() }
        btnPlay.setOnClickListener { svc?.togglePlay() }
        findViewById<View>(R.id.btnNext).setOnClickListener { svc?.next() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        btnShuffle.setOnClickListener { svc?.toggleShuffle(); render() }
        btnRepeat.setOnClickListener { svc?.cycleRepeat(); render() }
        heart.setOnClickListener { toggleFavorite() }
        tabFavs.setOnClickListener { setView(true) }
        tabFolder.setOnClickListener { if (viewFavs) setView(false) else chooseFolder() }
        tvEmpty.setOnClickListener { if (library.isEmpty()) ensurePermission() }
        // impostazioni: tema con palette colori e uscita audio
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        // tocco sullo stato in alto: cambia al volo l'uscita audio
        tvStatus.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            android.widget.Toast.makeText(
                this, "Uscita: " + s.cycleOutput(), android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = fmt(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                svc?.seekTo(sb?.progress ?: 0)
            }
        })

        applyTheme()

        viewFavs = Prefs.str(this, "view", "folder") == "favs"
        folder = Prefs.str(this, "folder", null)
        loadFavs()

        val i = Intent(this, PlaybackService::class.java)
        startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)

        ensurePermission()
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
        if (library.isNotEmpty()) loadLibrary() // aggiorna se hai aggiunto brani
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        svc?.savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        svc?.listener = null
        try { unbindService(conn) } catch (e: Exception) { }
        thumbExec.shutdownNow()
        super.onDestroy()
    }

    override fun onPlaybackChanged() = render()

    // ---------------------------------------------------------------- tema

    /** Applica il colore principale e lo sfondo scelti dall'ingranaggio. */
    private fun applyTheme() {
        window.statusBarColor = baseBg
        window.navigationBarColor = baseBg
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(bgColor, baseBg)
        )
        tvBrand.setTextColor(accent)
        eq.setAccent(accent)
        disc.setAccent(accent)
        btnPlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
        }
        btnPlay.imageTintList = ColorStateList.valueOf(baseBg)
        seek.progressDrawable = seekDrawable()
        seek.thumbTintList = ColorStateList.valueOf(accent)
        showList?.findViewById<ImageView>(R.id.ivHandle)?.imageTintList =
            ColorStateList.valueOf(accent)
        tabFolder.setTextColor(if (viewFavs) gray else accent)
        tabFavs.setTextColor(heartRed)
    }

    /** Barra di avanzamento ridisegnata col colore del tema. */
    private fun seekDrawable(): Drawable {
        val h = resources.getDimensionPixelSize(R.dimen.seek_thick)
        val r = 4f * dp
        fun shape(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(color)
            setSize(1, h)
        }
        val bar = ClipDrawable(shape(accent), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(shape(0xFF2A2F38.toInt()), bar)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    // ---------------------------------------------------------------- permessi e libreria

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        if (hasPermission()) {
            loadLibrary()
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(audioPermission())
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasPermission()) loadLibrary()
        else showEmpty("Tocca qui per consentire l'accesso alla musica")
    }

    private fun loadLibrary() {
        Thread {
            val l = Library.scan(this)
            runOnUiThread { onLibrary(l) }
        }.start()
    }

    private fun onLibrary(l: List<Track>) {
        library = l
        if (folder == null || library.none { it.folder == folder }) folder = defaultFolder()
        refreshList()
        tryRestore()
    }

    private fun defaultFolder(): String? {
        val last = Prefs.str(this, "lastPath", null)
        library.firstOrNull { it.path == last }?.let { return it.folder }
        return library.groupBy { it.folder }.maxByOrNull { it.value.size }?.key
    }

    /** All'avvio ricarica l'ultimo brano (in pausa) esattamente dove l'avevi lasciato. */
    private fun tryRestore() {
        val s = svc ?: return
        if (library.isEmpty() || s.current != null || restored) return
        restored = true
        val last = Prefs.str(this, "lastPath", null) ?: return
        val t = library.firstOrNull { it.path == last } ?: return
        val queue = library.filter { it.folder == t.folder }
        val idx = queue.indexOfFirst { it.path == last }
        if (idx >= 0) s.setQueue(queue, idx, false, Prefs.int(this, "lastPos", 0))
    }

    // ---------------------------------------------------------------- lista

    private fun loadFavs() { favSet = Favorites.all(this).toHashSet() }

    private fun refreshList() {
        loadFavs()
        shown = if (viewFavs) {
            val byPath = library.associateBy { it.path }
            Favorites.all(this).mapNotNull { byPath[it] }
        } else {
            library.filter { it.folder == folder }
        }
        adapter.notifyDataSetChanged()

        // due sole voci, compatte: ALBUM e il cuore dei preferiti
        tabFolder.text = smallCaps("Album")
        tabFavs.text = "♥"
        tabFolder.setTextColor(if (viewFavs) gray else accent)
        tabFavs.setTextColor(heartRed)
        tabFavs.alpha = if (viewFavs) 1f else 0.55f

        when {
            library.isEmpty() ->
                showEmpty("Nessun brano trovato.\nTocca qui per riprovare.")
            shown.isEmpty() ->
                showEmpty(if (viewFavs) "Tocca il cuore ♥ su un brano\nper aggiungerlo ai preferiti" else "Cartella vuota")
            else -> { tvEmpty.visibility = View.GONE; list.visibility = View.VISIBLE }
        }
        scrollToCurrent(true)
    }

    private fun showEmpty(msg: String) {
        tvEmpty.text = msg
        tvEmpty.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    private fun scrollToCurrent(force: Boolean) {
        val path = svc?.current?.path ?: return
        if (!force && path == lastShownPath) return
        lastShownPath = path
        val i = shown.indexOfFirst { it.path == path }
        if (i >= 0) list.setSelection(i)
    }

    /** Tocco sulla lista: il brano parte subito, senza dover premere play. */
    private fun playFromList(pos: Int) {
        val s = svc ?: return
        val t = shown.getOrNull(pos) ?: return
        val qi = s.queue.indexOfFirst { it.path == t.path }
        if (qi >= 0) {
            // brano già in coda: se è quello corrente riprende, altrimenti parte subito
            s.playAt(qi)
        } else {
            s.setQueue(shown, pos, true)
        }
    }

    private fun setView(favs: Boolean) {
        viewFavs = favs
        Prefs.putStr(this, "view", if (favs) "favs" else "folder")
        refreshList()
    }

    private fun chooseFolder() {
        val fs = Library.folders(library)
        if (fs.isEmpty()) return
        val names = fs.map { "${Library.label(it.first)}   (${it.second})" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Scegli cartella")
            .setItems(names) { _, i ->
                folder = fs[i].first
                Prefs.putStr(this, "folder", folder)
                refreshList()
            }
            .show()
    }

    // ---------------------------------------------------------------- preferiti

    private fun toggleFavorite() {
        val t = svc?.current ?: return
        Favorites.toggle(this, t.path)
        loadFavs()
        heart.setImageResource(heartIcon(t))
        heart.imageTintList = ColorStateList.valueOf(
            if (t.path in favSet) accent else white
        )
        heart.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction {
            heart.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
        if (viewFavs) refreshList() else adapter.notifyDataSetChanged()
    }

    private fun heartIcon(t: Track?) =
        if (t != null && t.path in favSet) R.drawable.ic_heart_filled else R.drawable.ic_heart

    // ---------------------------------------------------------------- interfaccia

    private fun render() {
        val s = svc ?: return
        val t = s.current

        // titolo grande, su due righe; il corpo si riduce solo se il titolo è molto lungo
        val title = t?.title ?: "Nessun brano"
        tvTitle.text = title
        // dimensione base dalla risorsa dello schermo, ridotta solo se il titolo è molto lungo
        val base = resources.getDimension(R.dimen.title_sp) / resources.displayMetrics.scaledDensity
        val len = when {
            title.length <= 14 -> 1.2f
            title.length <= 26 -> 1f
            else -> 0.8f
        }
        // con la lista nascosta la parte centrale si allarga: titolo un po' più grande
        val wide = if (listHidden) 1.12f else 1f
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * len * wide)
        tvArtist.text = t?.artist ?: ""

        eq.setActive(s.playing)
        // avviso se il volume multimediale dell'autoradio è a zero: è la causa più comune di silenzio
        val silent = s.playing && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        tvStatus.text = when {
            t == null -> "NESSUN BRANO"
            silent -> "VOLUME A ZERO"
            else -> (if (s.playing) "IN RIPRODUZIONE" else "IN PAUSA") + "   " + (s.index + 1) + "/" + s.queue.size
        }

        btnPlay.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.imageTintList = ColorStateList.valueOf(baseBg)
        disc.setSpinning(s.playing)
        if (s.cover !== lastCover) {
            lastCover = s.cover
            disc.setCover(s.cover)
            tint(s.cover)
        }
        heart.setImageResource(heartIcon(t))
        heart.imageTintList = ColorStateList.valueOf(if (t != null && t.path in favSet) accent else white)
        btnShuffle.alpha = if (s.shuffle) 1f else 0.35f
        btnRepeat.setImageResource(if (s.repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.alpha = if (s.repeat == 0) 0.35f else 1f
        val d = s.durationMs
        seek.max = if (d > 0) d else 1
        tvDur.text = fmt(d)
        updateStrip(s)
        adapter.notifyDataSetChanged()
        scrollToCurrent(false)
    }

    /** Sfondo sfumato che prende il colore dalla copertina. */
    private fun tint(b: Bitmap?) {
        val target = if (b == null) {
            baseBg
        } else {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = min(hsv[1], 0.6f)
            hsv[2] = 0.30f
            Color.HSVToColor(hsv)
        }
        ValueAnimator.ofObject(ArgbEvaluator(), bgColor, target).apply {
            duration = 600
            addUpdateListener {
                bgColor = it.animatedValue as Int
                root.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(bgColor, baseBg)
                )
            }
            start()
        }
    }

    // ---------------------------------------------------------------- pannello di destra

    /**
     * Nasconde o mostra la lista di destra con uno swipe orizzontale: quando è
     * nascosta, disco e parte centrale si allargano e il titolo cresce.
     */
    private fun setListHidden(hide: Boolean) {
        if (hide == listHidden) return
        listHidden = hide
        rightCol?.visibility = if (hide) View.GONE else View.VISIBLE
        showList?.visibility = if (hide) View.VISIBLE else View.GONE
        if (centerCol != null && discCol != null) {
            val d = discCol!!.layoutParams as LinearLayout.LayoutParams
            val c = centerCol!!.layoutParams as LinearLayout.LayoutParams
            d.weight = if (hide) 4f else 3f
            c.weight = if (hide) 8f else 5f
            discCol!!.layoutParams = d
            centerCol!!.layoutParams = c
        }
        render()
    }

    /**
     * Swipe orizzontale: verso destra sulla lista per nasconderla, verso sinistra
     * dal bordo destro (o tocco sulla linguetta) per riportarla.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (rightCol != null && centerCol != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downInList = !listHidden && ev.x >= (rightCol?.left ?: 0)
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val swipe = abs(dx) > 60f * dp && abs(dx) > abs(dy) * 1.8f
                    if (swipe) {
                        if (!listHidden && downInList) {
                            setListHidden(true)
                        } else if (listHidden && dx < 0f &&
                            downX > resources.displayMetrics.widthPixels - 90f * dp
                        ) {
                            setListHidden(false)
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- impostazioni

    /** Ingranaggio: tema con palette colori e scelta dell'uscita audio. */
    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val accentPalette = v.findViewById<LinearLayout>(R.id.accentPalette)
        val bgPalette = v.findViewById<LinearLayout>(R.id.bgPalette)
        val outBox = v.findViewById<LinearLayout>(R.id.outputBox)
        val engineBox = v.findViewById<LinearLayout>(R.id.engineBox)
        val diag = v.findViewById<TextView>(R.id.audioDiag)

        // modifiche in sospeso: entrano in vigore solo toccando "Salva"
        var newAccent = accent
        var newBg = baseBg
        var newEngine = svc?.engineMode ?: PlaybackService.ENGINE_AUTO
        var newOutput = svc?.outputId ?: 0

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(v)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create()

        fun paintPalettes() {
            accentPalette.removeAllViews()
            for (c in Themes.ACCENTS) {
                accentPalette.addView(swatch(c, c == newAccent) {
                    newAccent = c
                    paintPalettes()
                })
            }
            bgPalette.removeAllViews()
            for (c in Themes.BACKGROUNDS) {
                bgPalette.addView(swatch(c, c == newBg) {
                    newBg = c
                    paintPalettes()
                })
            }
        }

        fun paintOutputs() {
            outBox.removeAllViews()
            for ((id, name) in svc?.outputOptions() ?: listOf(0 to "Automatico (consigliato)")) {
                val tv = TextView(this)
                tv.text = (if (id == newOutput) "●  " else "○  ") + name
                tv.setTextColor(if (id == newOutput) newAccent else gray)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                tv.typeface = listFont
                tv.setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                tv.setOnClickListener {
                    newOutput = id
                    paintOutputs()
                }
                outBox.addView(tv)
            }
        }

        fun paintDiag() {
            val s = svc
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val mode = when (am.mode) {
                AudioManager.MODE_IN_CALL -> "chiamata"
                AudioManager.MODE_IN_COMMUNICATION -> "comunicazione"
                AudioManager.MODE_RINGTONE -> "suoneria"
                else -> "normale"
            }
            val eng = when (newEngine) {
                PlaybackService.ENGINE_MP -> "MediaPlayer"
                PlaybackService.ENGINE_PCM -> "PCM"
                else -> "auto"
            }
            val err = s?.lastError?.takeIf { it.isNotBlank() }
            diag.text = "Volume $vol/$max · modo $mode · motore $eng\n" +
                (if (err != null) "Ultimo errore: $err" else "Nessun errore del motore")
        }

        fun paintEngine() {
            engineBox.removeAllViews()
            val opts = listOf(
                PlaybackService.ENGINE_AUTO to "Automatico (PCM, consigliato)",
                PlaybackService.ENGINE_MP to "MediaPlayer",
                PlaybackService.ENGINE_PCM to "PCM forzato"
            )
            for ((id, name) in opts) {
                val tv = TextView(this)
                tv.text = (if (id == newEngine) "●  " else "○  ") + name
                tv.setTextColor(if (id == newEngine) newAccent else gray)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                tv.typeface = listFont
                tv.setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                tv.setOnClickListener {
                    newEngine = id
                    paintEngine()
                    paintDiag()
                }
                engineBox.addView(tv)
            }
        }

        paintPalettes()
        paintOutputs()
        paintEngine()
        paintDiag()
        v.findViewById<TextView>(R.id.btnTest).setOnClickListener {
            playTestTone()
            diag.postDelayed({ paintDiag() }, 300)
        }
        dlg.setOnShowListener {
            val w = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            dlg.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            // "Salva": memorizza tema, motore e uscita scelti
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                Prefs.putInt(this, "accent", newAccent)
                Prefs.putInt(this, "bg", newBg)
                svc?.setEngine(newEngine)
                svc?.setOutput(newOutput)
                dlg.dismiss()
                if (newAccent != accent || newBg != baseBg) recreate()
            }
        }
        dlg.show()
    }

    /** Tono di 1,5 s su AudioTrack: dice subito se l'uscita audio funziona. */
    private fun playTestTone() {
        if (Build.VERSION.SDK_INT < 23) return
        Thread {
            var at: AudioTrack? = null
            try {
                val rate = 44100
                val frames = rate * 3 / 2
                val buf = ShortArray(frames * 2)
                for (i in 0 until frames) {
                    val v = (sin(2.0 * PI * 440.0 * i / rate) * 11000).toInt().toShort()
                    buf[i * 2] = v
                    buf[i * 2 + 1] = v
                }
                val min = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
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
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, 16384))
                    .build()
                at.play()
                at.write(buf, 0, buf.size)
                Thread.sleep(150)
                at.stop()
            } catch (e: Exception) {
            } finally {
                try { at?.release() } catch (e: Exception) { }
            }
        }.start()
    }

    private fun swatch(color: Int, selected: Boolean, tap: () -> Unit): View {
        val s = (52 * dp).toInt()
        val v = View(this)
        val lp = LinearLayout.LayoutParams(s, s)
        lp.marginEnd = (12 * dp).toInt()
        v.layoutParams = lp
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke((3 * dp).toInt(), white)
        }
        v.setOnClickListener { tap() }
        return v
    }

    // ---------------------------------------------------------------- striscia miniature

    private fun updateStrip(s: PlaybackService) {
        val q = s.queue
        val cur = s.index
        if (q.isEmpty() || cur !in q.indices) {
            stripBox.removeAllViews()
            stripQueue = null
            stripCur = -1
            return
        }
        val rebuild = q !== stripQueue || cur < stripFrom || cur >= stripTo
        if (rebuild) buildStrip(q, cur)
        markStrip(cur)
        // se stai scorrendo col dito, non ti riporto indietro di colpo
        val recentTouch = System.currentTimeMillis() - stripTouchAt < 4000
        if (rebuild) centerStrip(cur, smooth = false, afterLayout = true)
        else if (cur != stripCur && !recentTouch) centerStrip(cur, smooth = true, afterLayout = false)
        stripCur = cur
    }

    private fun buildStrip(q: List<Track>, cur: Int) {
        stripQueue = q
        stripFrom = maxOf(0, cur - 40)
        stripTo = minOf(q.size, cur + 41)
        stripBox.removeAllViews()
        wanted = (stripFrom until stripTo).map { q[it].path }.toHashSet()
        for (i in stripFrom until stripTo) {
            val v = layoutInflater.inflate(R.layout.item_thumb, stripBox, false)
            v.findViewById<TextView>(R.id.thTitle).apply {
                text = q[i].title
                typeface = listFont
            }
            v.setOnClickListener {
                stripTouchAt = 0L
                // tocco sulla copertina: il brano parte subito, mai solo selezionato
                svc?.playAt(i)
            }
            stripBox.addView(v)
        }
        // le copertine si caricano una alla volta, partendo da quelle più vicine al brano attuale
        for (i in (stripFrom until stripTo).sortedBy { abs(it - cur) }) {
            val iv = stripBox.getChildAt(i - stripFrom).findViewById<ImageView>(R.id.thImg)
            loadThumb(q[i].path, iv)
        }
    }

    /** Evidenzia il brano in esecuzione: bordo, titolo colorato e sottolineato. */
    private fun markStrip(cur: Int) {
        for (k in 0 until stripBox.childCount) {
            val v = stripBox.getChildAt(k)
            val isCur = stripFrom + k == cur
            v.setBackgroundResource(if (isCur) R.drawable.bg_thumb_current else 0)
            v.alpha = if (isCur) 1f else 0.8f
            val tv = v.findViewById<TextView>(R.id.thTitle)
            tv.setTextColor(if (isCur) accent else gray)
            tv.paintFlags = if (isCur) tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            else tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun centerStrip(cur: Int, smooth: Boolean, afterLayout: Boolean) {
        val run = Runnable {
            val v = stripBox.getChildAt(cur - stripFrom)
            if (v != null) {
                val x = (v.left - (strip.width - v.width) / 2).coerceAtLeast(0)
                if (smooth) strip.smoothScrollTo(x, 0) else strip.scrollTo(x, 0)
            }
        }
        if (afterLayout) {
            strip.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    strip.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    run.run()
                }
            })
        } else {
            strip.post(run)
        }
    }

    private fun showPlaceholder(iv: ImageView, path: String) {
        val p = (18 * dp).toInt()
        iv.setPadding(p, p, p, p)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.background = gradientFor(path)
        iv.setImageResource(R.drawable.ic_note)
    }

    /** Sfumatura casuale (ma sempre uguale per lo stesso brano) quando manca la copertina. */
    private fun gradientFor(path: String): GradientDrawable {
        val h = (abs(path.hashCode()) % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(h, 0.55f, 0.62f))
        val c2 = Color.HSVToColor(floatArrayOf((h + 42f) % 360f, 0.72f, 0.28f))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)
        ).apply { cornerRadius = 8 * dp }
    }

    private fun showCover(iv: ImageView, b: Bitmap) {
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(b)
    }

    private fun loadThumb(path: String, iv: ImageView) {
        iv.tag = path
        val b = thumbs[path]
        if (b != null) {
            showCover(iv, b)
            return
        }
        showPlaceholder(iv, path)
        if (path in noThumb || !loading.add(path)) return
        thumbExec.execute {
            val skipped = path !in wanted
            val bmp: Bitmap? = if (skipped) null else Covers.load(path, 120)?.let { shrink(it) }
            runOnUiThread {
                loading.remove(path)
                if (bmp != null) {
                    thumbs[path] = bmp
                    applyThumbs()
                } else if (!skipped) {
                    noThumb.add(path)
                }
            }
        }
    }

    /** Mette le copertine appena caricate nelle miniature che le aspettavano. */
    private fun applyThumbs() {
        for (k in 0 until stripBox.childCount) {
            val iv = stripBox.getChildAt(k).findViewById<ImageView>(R.id.thImg)
            if (iv.scaleType == ImageView.ScaleType.CENTER_CROP) continue
            val b = (iv.tag as? String)?.let { thumbs[it] } ?: continue
            showCover(iv, b)
        }
    }

    /** Le miniature restano piccole in memoria (max ~128 px). */
    private fun shrink(b: Bitmap): Bitmap {
        val m = min(b.width, b.height)
        if (m <= 128) return b
        val sc = 128f / m
        return Bitmap.createScaledBitmap(
            b,
            (b.width * sc).toInt().coerceAtLeast(1),
            (b.height * sc).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Maiuscoletto: le minuscole diventano maiuscole piccole, le maiuscole restano grandi. */
    private fun smallCaps(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (ch in s) {
            val start = sb.length
            sb.append(ch.uppercaseChar())
            if (ch.isLowerCase()) {
                sb.setSpan(RelativeSizeSpan(0.75f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val t = shown[position]
            val cur = svc?.current?.path == t.path
            v.findViewById<TextView>(R.id.itTitle).apply {
                text = smallCaps(t.title)
                setTextColor(if (cur) accent else white)
                // il brano in esecuzione è sottolineato
                paintFlags = if (cur) paintFlags or Paint.UNDERLINE_TEXT_FLAG
                else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                typeface = listFont
                // selected = testo che scorre quando non entra nella riga
                isSelected = true
            }
            v.findViewById<TextView>(R.id.itArtist).apply {
                text = t.artist
                typeface = listFont
                isSelected = true
            }
            v.findViewById<ImageView>(R.id.itFav).visibility =
                if (t.path in favSet) View.VISIBLE else View.GONE
            v.setBackgroundColor(
                if (cur) (accent and 0x00FFFFFF) or 0x22000000 else 0
            )
            return v
        }
    }
}
