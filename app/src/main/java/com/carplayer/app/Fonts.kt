package com.carplayer.app

import android.content.Context
import android.graphics.Typeface

/**
 * Caratteri dagli asset, con ripiego sul sistema se mancano:
 * - display: Orbitron Bold (robotico) per titolo, orologio, stato
 * - condensed: Barlow Condensed Bold per lista, schede e didascalie (licenza OFL)
 */
object Fonts {
    private var display: Typeface? = null
    private var condensed: Typeface? = null

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

    fun condensed(ctx: Context): Typeface {
        condensed?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/BarlowCondensed-Bold.ttf")
        } catch (e: Exception) {
            Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        condensed = t
        return t
    }
}
