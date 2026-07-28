package com.example.retouchme.ui.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ZoomIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var screenRect: RectF? = null

    init {
        setWillNotDraw(false)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(30, 255, 255, 255)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(200, 255, 255, 255)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    fun setViewport(rect: RectF?) {
        screenRect = rect
        invalidate()
    }

    fun hide() {
        screenRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val rect = screenRect ?: return
        if (rect.width() < 4f || rect.height() < 4f) return

        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)

        val cornerLen = rect.width().coerceAtMost(rect.height()) * 0.12f
        val corners = arrayOf(
            floatArrayOf(rect.left, rect.top + cornerLen, rect.left, rect.top, rect.left + cornerLen, rect.top),
            floatArrayOf(rect.right - cornerLen, rect.top, rect.right, rect.top, rect.right, rect.top + cornerLen),
            floatArrayOf(rect.right, rect.bottom - cornerLen, rect.right, rect.bottom, rect.right - cornerLen, rect.bottom),
            floatArrayOf(rect.left + cornerLen, rect.bottom, rect.left, rect.bottom, rect.left, rect.bottom - cornerLen)
        )
        for (c in corners) {
            canvas.drawLines(c, cornerPaint)
        }
    }
}
