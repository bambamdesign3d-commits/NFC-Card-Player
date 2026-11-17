package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

/** Small battery symbol with 1–4 bars (phone battery). */
class BatteryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level = 100
        set(v) { field = v.coerceIn(0, 100); updateDescription(); invalidate() }
    var charging = false
        set(v) { field = v; updateDescription(); invalidate() }
    var frameColor = Color.DKGRAY
        set(v) { field = v; invalidate() }
    var barColor = Color.rgb(0x4A, 0x6F, 0xD1)
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val r = RectF()

    private fun updateDescription() {
        contentDescription = "Akku / Battery $level %" + if (charging) " ⚡" else ""
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val sw = 1.4f * d
        stroke.strokeWidth = sw
        stroke.color = frameColor
        fill.color = frameColor
        val capW = 2.2f * d
        val w = width - capW
        val h = height.toFloat()
        // body
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        c.drawRoundRect(r, 2 * d, 2 * d, stroke)
        // cap
        r.set(w, h * 0.3f, w + capW, h * 0.7f)
        c.drawRoundRect(r, 1 * d, 1 * d, fill)
        // bars (1–4)
        val bars = if (level <= 0) 0 else ceil(level / 25.0).toInt().coerceIn(1, 4)
        fill.color = when {
            charging -> Color.rgb(0x43, 0xA0, 0x47)
            level <= 15 -> Color.rgb(0xE5, 0x39, 0x35)
            else -> barColor
        }
        val pad = sw + 1.2f * d
        val gap = 1.2f * d
        val slotW = (w - 2 * pad - 3 * gap) / 4
        for (i in 0 until bars) {
            val left = pad + i * (slotW + gap)
            r.set(left, pad, left + slotW, h - pad)
            c.drawRect(r, fill)
        }
    }
}
