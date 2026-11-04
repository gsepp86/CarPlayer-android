package com.carplayer.app

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Disco in vinile che gira. Il disegno è statico (e messo in cache dalla GPU):
 * a ruotare è la vista intera, quindi costa quasi zero anche su autoradio lenti.
 */
class DiscView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    private val density = resources.displayMetrics.density

    private val vinyl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101114.toInt() }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF1E2025.toInt()
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF2B2E35.toInt()
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0B0D.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)

    private val note = ctx.getDrawable(R.drawable.ic_note)
    private var cover: Bitmap? = null
    private var spinning = false

    private val spin: ObjectAnimator =
        ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
            duration = 9000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setCover(b: Bitmap?) {
        cover = b
        rebuild()
        invalidate()
    }

    fun setSpinning(on: Boolean) {
        spinning = on
        applySpin()
    }

    private fun applySpin() {
        if (!isAttachedToWindow) return
        if (!spin.isStarted) spin.start()
        if (spinning) spin.resume() else spin.pause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applySpin()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
    }

    private fun rebuild() {
        val r = min(width, height) / 2f
        if (r <= 0f) return
        val rc = r * 0.58f
        val cx = width / 2f
        val cy = height / 2f
        val b = cover
        if (b != null) {
            val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val sc = (2f * rc) / min(b.width, b.height)
            val m = Matrix()
            m.setScale(sc, sc)
            m.postTranslate(cx - b.width * sc / 2f, cy - b.height * sc / 2f)
            s.setLocalMatrix(m)
            label.shader = s
        } else {
            label.shader = RadialGradient(
                cx, cy, rc,
                intArrayOf(Color.parseColor("#3DDC97"), Color.parseColor("#17604A")),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2f * density
        if (r <= 0f) return

        c.drawCircle(cx, cy, r, vinyl)
        var g = r * 0.64f
        while (g < r * 0.97f) {
            c.drawCircle(cx, cy, g, groove)
            g += 5f * density
        }
        c.drawCircle(cx, cy, r, rim)

        val rc = r * 0.58f
        c.drawCircle(cx, cy, rc, label)
        if (cover == null) {
            note?.let {
                val half = (rc * 0.35f).toInt()
                it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                it.draw(c)
            }
        }
        c.drawCircle(cx, cy, rc, rim)

        c.drawCircle(cx, cy, r * 0.055f, hole)
        c.drawCircle(cx, cy, r * 0.055f, rim)
    }
}
