package com.carplayer.app

import android.content.Context

/** Piccolo wrapper per SharedPreferences + gestione preferiti. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("carplayer", Context.MODE_PRIVATE)

    fun str(c: Context, k: String, d: String?): String? = sp(c).getString(k, d)
    fun putStr(c: Context, k: String, v: String?) = sp(c).edit().putString(k, v).apply()
    fun int(c: Context, k: String, d: Int = 0): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean = false): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** Playlist dei preferiti: elenco di percorsi file, in ordine di aggiunta. */
object Favorites {
    fun all(c: Context): List<String> =
        (Prefs.str(c, "favs", "") ?: "").split("\n").filter { it.isNotEmpty() }

    /** Aggiunge o toglie il brano. Ritorna true se ora è nei preferiti. */
    fun toggle(c: Context, path: String): Boolean {
        val l = all(c).toMutableList()
        val added = if (l.remove(path)) false else { l.add(path); true }
        Prefs.putStr(c, "favs", l.joinToString("\n"))
        return added
    }
}
