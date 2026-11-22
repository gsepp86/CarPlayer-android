package com.carplayer.app

import android.content.Context
import android.graphics.Typeface

/**
 * Caratteri dagli asset, con ripiego sul sistema se mancano:
 * - display: Orbitron Bold (robotico) per titolo, orologio, stato
 * - legible: Barlow Bold per lista, schede e didascalie. È il fratello "tondo" del
 *   Barlow Condensed usato prima: stessa famiglia (licenza OFL), ma largo e aperto,
 *   quindi molto più leggibile da lontano sul cruscotto.
 */
object Fonts {
    private var display: Typeface? = null
    private var legible: Typeface? = null

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

    fun legible(ctx: Context): Typeface {
        legible?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/Barlow-Bold.ttf")
        } catch (e: Exception) {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
        legible = t
        return t
    }
}
