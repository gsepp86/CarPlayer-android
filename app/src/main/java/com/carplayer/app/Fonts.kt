package com.carplayer.app

import android.content.Context
import android.graphics.Typeface

/** Carattere "robotico" (Orbitron Bold, licenza OFL) caricato dagli asset, con ripiego sul sistema. */
object Fonts {
    private var display: Typeface? = null

    fun display(ctx: Context): Typeface {
        display?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/Orbitron-Bold.ttf")
        } catch (e: Exception) {
            Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        display = t
        return t
    }
}
