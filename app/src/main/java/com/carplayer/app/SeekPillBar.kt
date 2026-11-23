package com.carplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * Barra di avanzamento a "tacca": una scanalatura spessa con la parte già suonata
 * colorata e una tacca verticale sul punto corrente. Non c'è più il pallino
 * separato: il cursore è la tacca stessa.
 *
 * Il tocco o il trascinamento in qualunque punto della barra spostano subito la
 * riproduzione (prima il tocco veniva perso dalla lista che si chiudeva).
 */
class SeekPillBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBar(context, attrs) {

    /** Colore del tratto già suonato (quello del tema). */
    var accentColor: Int = 0xFF3DDC97.toInt()
        set(v) {
            field = v
            fillPaint.color = v
            invalidate()
        }

    /** Colore della scanalatura vuota. */
    var trackColor: Int = 0xFF26324D.toInt()
        set(v) {
            field = v
            trackPaint.color = v
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private val dens = resources.displayMetrics.density
    private val grooveH = resources.getDimension(R.dimen.seek_thick)
    private var tracking = false

    /** Inizio e fine del gesto: l'attività li usa per fermare l'aggiornamento e saltare. */
    var onTrackStart: (() -> Unit)? = null
    var onTrackEnd: (() -> Unit)? = null

    init {
        trackPaint.color = trackColor
        fillPaint.color = accentColor
    }

    /** Rettangolo della scanalatura, con i bordi arrotondati dentro la vista. */
    private fun groove(): RectF {
        val h = grooveH.coerceAtMost(height - 2f * dens).coerceAtLeast(2f * dens)
        val cap = h / 2f
        val left = paddingLeft + cap
        val right = (width - paddingRight - cap).coerceAtLeast(left + h)
        val cy = height / 2f
        return RectF(left, cy - h / 2f, right, cy + h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val g = groove()
        val h = g.height()
        val r = h / 2f

        canvas.drawRoundRect(g, r, r, trackPaint)

        val maxV = if (max > 0) max else 1
        val f = (progress.toFloat() / maxV).coerceIn(0f, 1f)
        val px = g.left + (g.right - g.left) * f
        canvas.drawRoundRect(RectF(g.left, g.top, maxOf(px, g.left + h), g.bottom), r, r, fillPaint)

        // la tacca: barretta verticale arrotondata sul punto di ascolto
        val tw = 5f * dens
        val th = (h + 6f * dens).coerceAtMost(height - 2f * dens)
        val cx = px.coerceIn(tw / 2f, width - tw / 2f)
        val cy = height / 2f
        canvas.drawRoundRect(
            RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f),
            tw / 2f, tw / 2f, tickPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // la barra si prende il gesto: lo ScrollView non deve portarselo via
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                onTrackStart?.invoke()
                moveTo(event.x)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) moveTo(event.x)
            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    moveTo(event.x)
                    tracking = false
                    onTrackEnd?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    onTrackEnd?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** Porta l'avanzamento al punto toccato (anche fuori dalle estremità). */
    private fun moveTo(x: Float) {
        val g = groove()
        val f = ((x - g.left) / (g.right - g.left)).coerceIn(0f, 1f)
        val v = (f * (if (max > 0) max else 1)).toInt()
        if (v != progress) progress = v
    }
}
