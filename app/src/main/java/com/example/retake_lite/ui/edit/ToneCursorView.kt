package com.example.retake_lite.ui.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class ToneCursorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cursorX: Float = -1f
    var cursorY: Float = -1f
    var cursorRadius: Float = 30f
    var isSampled: Boolean = false
    var sampledColor: Int = Color.TRANSPARENT
    var isErasing: Boolean = false

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(180, 255, 255, 255)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        color = Color.argb(160, 255, 255, 255)
    }
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(60, 60, 65)
    }
    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(235, 235, 230)
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(120, 120, 125)
    }
    private val eraserStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(100, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        if (cursorX < 0f || cursorY < 0f) return
        val r = cursorRadius.coerceAtLeast(5f)

        if (isErasing) {
            canvas.drawCircle(cursorX, cursorY, r, circlePaint)
            drawEraser3D(canvas, cursorX, cursorY, r)
        } else {
            canvas.drawCircle(cursorX, cursorY, r, circlePaint)
            canvas.drawLine(cursorX - r * 0.5f, cursorY, cursorX + r * 0.5f, cursorY, crossPaint)
            canvas.drawLine(cursorX, cursorY - r * 0.5f, cursorX, cursorY + r * 0.5f, crossPaint)
        }

        if (isSampled) {
            val samplePaint = Paint().apply {
                color = sampledColor
                style = Paint.Style.FILL
            }
            val dotR = 6f
            canvas.drawCircle(cursorX, cursorY, dotR, samplePaint)
            canvas.drawCircle(cursorX, cursorY, dotR, circlePaint)
        }
    }

    private fun drawEraser3D(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r * 0.55f
        val skew = s * 0.35f
        val h = s * 0.45f

        // front face (white eraser surface)
        val front = Path().apply {
            moveTo(cx - s, cy + h * 0.3f)
            lineTo(cx - s + skew, cy - h * 0.3f)
            lineTo(cx + s + skew, cy - h * 0.3f)
            lineTo(cx + s, cy + h * 0.3f)
            close()
        }
        canvas.drawPath(front, frontPaint)
        canvas.drawPath(front, eraserStroke)

        // top face (dark gray)
        val top = Path().apply {
            moveTo(cx - s + skew, cy - h * 0.3f)
            lineTo(cx - s + skew, cy - h * 1.1f)
            lineTo(cx + s + skew, cy - h * 1.1f)
            lineTo(cx + s + skew, cy - h * 0.3f)
            close()
        }
        canvas.drawPath(top, topPaint)
        canvas.drawPath(top, eraserStroke)

        // right side face (medium gray)
        val side = Path().apply {
            moveTo(cx + s, cy + h * 0.3f)
            lineTo(cx + s + skew, cy - h * 0.3f)
            lineTo(cx + s + skew, cy - h * 1.1f)
            lineTo(cx + s, cy - h * 0.5f)
            close()
        }
        canvas.drawPath(side, sidePaint)
        canvas.drawPath(side, eraserStroke)
    }
}
