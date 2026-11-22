package com.carplayer.app

import android.content.Context

/** Sfondo a due colori, disegnato in diagonale sulla schermata principale. */
data class BgStyle(val start: Int, val end: Int)

/**
 * Palette dei temi: il colore principale (accenti, evidenziazioni, barra) e lo
 * sfondo sfumato si scelgono dall'ingranaggio e vengono ricordati.
 */
object Themes {

    val DEFAULT_ACCENT = 0xFF3DDC97.toInt()

    /** Colori principali: vivaci, ben distinguibili anche di giorno in auto. */
    val ACCENTS = intArrayOf(
        0xFF3DDC97.toInt(), // verde
        0xFFF44336.toInt(), // rosso
        0xFF4DD0E1.toInt(), // turchese
        0xFF4FC3F7.toInt(), // azzurro
        0xFF64B5F6.toInt(), // blu
        0xFF7E57C2.toInt(), // viola
        0xFFBA68C8.toInt(), // lilla
        0xFFF06292.toInt(), // rosa
        0xFFE57373.toInt(), // rosso
        0xFFFF8A65.toInt(), // corallo
        0xFFFFB74D.toInt(), // arancio
        0xFFFFD54F.toInt(), // giallo
        0xFFAED581.toInt(), // lime
        0xFFE0E0E0.toInt(), // bianco
        0xFF90A4AE.toInt()  // grigio
    )

    /** Sfondi sfumati, abbastanza scuri da lasciare leggibile il testo bianco. */
    val BACKGROUNDS = listOf(
        BgStyle(0xFF161C28.toInt(), 0xFF07090D.toInt()), // notte
        BgStyle(0xFF2C1B4F.toInt(), 0xFF0B0714.toInt()), // viola
        BgStyle(0xFF0B2E3A.toInt(), 0xFF040E13.toInt()), // oceano
        BgStyle(0xFF3A1A1C.toInt(), 0xFF0F0607.toInt()), // rosso
        BgStyle(0xFF1B3A2A.toInt(), 0xFF060F0A.toInt()), // bosco
        BgStyle(0xFF3A2A10.toInt(), 0xFF120C04.toInt()), // ambra
        BgStyle(0xFF1A2340.toInt(), 0xFF080B14.toInt())  // blu
    )

    fun accent(c: Context): Int = Prefs.int(c, "accent", DEFAULT_ACCENT)

    fun bgStyleIndex(c: Context): Int =
        Prefs.int(c, "bgStyle", 0).coerceIn(0, BACKGROUNDS.lastIndex)

    fun bgStyle(c: Context): BgStyle = BACKGROUNDS[bgStyleIndex(c)]
}
