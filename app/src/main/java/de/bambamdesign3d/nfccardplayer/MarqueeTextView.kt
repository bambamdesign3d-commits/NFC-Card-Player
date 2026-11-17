package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Single-line text that scrolls continuously and evenly when it is too long.
 * Unlike the built-in marquee it is not restarted by layout changes of other views.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
    }
    private val speedPx = 32 * resources.displayMetrics.density   // pixels per second
    private val gapPx = 40 * resources.displayMetrics.density
    private val pauseMs = 1500L                                    // short stop at the start of each loop
    private var startTime = SystemClock.uptimeMillis()
    private var textWidth = 0f

    var text: CharSequence = ""
        set(v) {
            if (v.toString() == field.toString()) return
            field = v
            textWidth = paint.measureText(v.toString())
            startTime = SystemClock.uptimeMillis()
            contentDescription = v
            requestLayout()
            invalidate()
        }

    fun setTextColor(c: Int) { paint.color = c; invalidate() }

    fun setTextSizeSp(sp: Float) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        textWidth = paint.measureText(text.toString())
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent + paddingTop + paddingBottom).toInt() + 1
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val s = text.toString()
        if (s.isEmpty()) return
        val fm = paint.fontMetrics
        val y = paddingTop - fm.ascent
        val avail = width - paddingLeft - paddingRight
        if (textWidth <= avail) {
            c.drawText(s, paddingLeft.toFloat(), y, paint)
            return
        }
        val loop = textWidth + gapPx
        val loopMs = (loop / speedPx * 1000).toLong() + pauseMs
        val t = (SystemClock.uptimeMillis() - startTime) % loopMs
        val offset = if (t < pauseMs) 0f else (t - pauseMs) / 1000f * speedPx
        c.save()
        c.clipRect(paddingLeft, 0, width - paddingRight, height)
        val x = paddingLeft - offset
        c.drawText(s, x, y, paint)
        c.drawText(s, x + loop, y, paint)
        c.restore()
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
