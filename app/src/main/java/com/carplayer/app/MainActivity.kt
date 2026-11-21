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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
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
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), PlaybackService.Listener {

    private val accent = 0xFF3DDC97.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val gray = 0xFF9AA3AF.toInt()
    private val baseBg = 0xFF0A0C10.toInt()

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
    private var bgColor = 0xFF0F2A24.toInt()

    private lateinit var root: View
    private lateinit var disc: DiscView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
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
    private var condTypeface: android.graphics.Typeface? = null

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
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        disc = findViewById(R.id.disc)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
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

        // carattere robotico per titolo, tempi, barra superiore e orologio
        val robo = Fonts.display(this)
        val roboViews = listOf<TextView>(
            tvTitle, tvPos, tvDur, tvStatus,
            findViewById(R.id.tvBrand), findViewById(R.id.clock), findViewById(R.id.dateClock)
        )
        for (v in roboViews) v.typeface = robo
        // schede, lista e didascalie in carattere condensato, così i nomi lunghi entrano
        val cond = Fonts.condensed(this)
        tabFolder.typeface = cond
        tabFavs.typeface = cond
        condTypeface = cond
        findViewById<View>(R.id.dateClock).visibility =
            if (resources.getBoolean(R.bool.show_date)) View.VISIBLE else View.GONE

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> playFromList(pos) }
        strip.setOnTouchListener { _, _ -> stripTouchAt = System.currentTimeMillis(); false }

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

        val f = folder
        tabFolder.text = smallCaps(if (f != null) Library.label(f) + (if (viewFavs) "" else "  ▾") else "Cartella")
        tabFavs.text = smallCaps("♥ Preferiti")
        tabFolder.setTextColor(if (viewFavs) gray else accent)
        tabFavs.setTextColor(if (viewFavs) accent else gray)

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
        if (s.current?.path == t.path) {
            // riga già caricata (magari in pausa): riprende a suonare, non mette in pausa
            if (!s.playing) s.play()
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
        val f = when {
            title.length <= 14 -> 1.2f
            title.length <= 26 -> 1f
            else -> 0.8f
        }
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * f)
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
        disc.setSpinning(s.playing)
        if (s.cover !== lastCover) {
            lastCover = s.cover
            disc.setCover(s.cover)
            tint(s.cover)
        }
        heart.setImageResource(heartIcon(t))
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
            0xFF0F2A24.toInt()
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
                typeface = condTypeface
            }
            v.setOnClickListener {
                stripTouchAt = 0L
                svc?.playIndex(i)
            }
            stripBox.addView(v)
        }
        // le copertine si caricano una alla volta, partendo da quelle più vicine al brano attuale
        for (i in (stripFrom until stripTo).sortedBy { abs(it - cur) }) {
            val iv = stripBox.getChildAt(i - stripFrom).findViewById<ImageView>(R.id.thImg)
            loadThumb(q[i].path, iv)
        }
    }

    /** Evidenzia il brano in esecuzione: bordo verde, titolo verde e sottolineato. */
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

    private fun showPlaceholder(iv: ImageView) {
        val p = (22 * dp).toInt()
        iv.setPadding(p, p, p, p)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageResource(R.drawable.ic_note)
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
        showPlaceholder(iv)
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
                typeface = condTypeface
                // selected = testo che scorre quando non entra nella riga
                isSelected = true
            }
            v.findViewById<TextView>(R.id.itArtist).apply {
                text = t.artist
                typeface = condTypeface
                isSelected = true
            }
            v.findViewById<ImageView>(R.id.itFav).visibility =
                if (t.path in favSet) View.VISIBLE else View.GONE
            v.setBackgroundColor(if (cur) 0x223DDC97 else 0)
            return v
        }
    }
}
