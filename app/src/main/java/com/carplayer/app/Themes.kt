package com.carplayer.app

import android.content.Context

/**
 * Palette dei temi: il colore principale (accenti, evidenziazioni, barra) e il
 * colore di sfondo si scelgono dall'ingranaggio e vengono ricordati.
 */
object Themes {

    val DEFAULT_ACCENT = 0xFF3DDC97.toInt()
    val DEFAULT_BG = 0xFF0A0C10.toInt()

    /** Colori principali: vivaci, ben distinguibili anche di giorno in auto. */
    val ACCENTS = intArrayOf(
        0xFF3DDC97.toInt(), // verde
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

    /** Sfondi scuri: il testo bianco resta sempre leggibile. */
    val BACKGROUNDS = intArrayOf(
        0xFF0A0C10.toInt(), // nero
        0xFF12141C.toInt(), // ardesia
        0xFF0C1620.toInt(), // blu notte
        0xFF101A14.toInt(), // verde scuro
        0xFF141020.toInt(), // viola scuro
        0xFF1A1210.toInt()  // marrone
    )

    fun accent(c: Context): Int = Prefs.int(c, "accent", DEFAULT_ACCENT)

    fun bg(c: Context): Int = Prefs.int(c, "bg", DEFAULT_BG)
}
