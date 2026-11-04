package com.carplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * Piccolo equalizzatore decorativo (non analizza l'audio, quindi niente permesso microfono).
 * Si muove solo durante la riproduzione, a ~25 fps: costo trascurabile.
 */
class EqView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val n = 5
    private val cur = FloatArray(n) { 0.15f }
    private val tgt = FloatArray(n) { 0.15f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DDC97.toInt() }
    private val rect = RectF()
    private var active = false
    private var nextPick = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (active) {
                if (now >= nextPick) {
                    for (i in 0 until n) tgt[i] = 0.25f + Random.nextFloat() * 0.75f
                    nextPick = now + 170
                }
            } else {
                for (i in 0 until n) tgt[i] = 0.15f
            }
            var moving = false
            for (i in 0 until n) {
                val d = tgt[i] - cur[i]
                cur[i] += d * 0.28f
                if (abs(d) > 0.01f) moving = true
            }
            invalidate()
            if (isAttachedToWindow && (active || moving)) postDelayed(this, 40)
        }
    }

    fun setActive(a: Boolean) {
        if (a == active) return
        active = a
        removeCallbacks(tick)
        if (isAttachedToWindow) post(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w / (n * 2 - 1)
        for (i in 0 until n) {
            val bh = h * cur[i].coerceIn(0.1f, 1f)
            val x = i * bw * 2
            rect.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}
