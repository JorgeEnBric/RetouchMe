package com.example.retouchme.ui.swap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.retouchme.R
import com.google.mlkit.vision.face.Face
import kotlin.math.min

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var faces: List<Face> = emptyList()
    private var selectedIndex: Int = -1
    private var onFaceSelectedListener: ((Int) -> Unit)? = null

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val drawRect = RectF()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 33, 150, 243)
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 76, 175, 80)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 33, 33, 33)
    }

    private val colorDefault = ContextCompat.getColor(context, R.color.face_box_default)
    private val colorSelected = ContextCompat.getColor(context, R.color.face_box_selected)

    fun setImage(bitmap: Bitmap, faces: List<Face>) {
        this.bitmap = bitmap
        this.faces = faces
        this.selectedIndex = -1
        updateMatrix()
        invalidate()
    }

    fun setSelectedFace(index: Int) {
        selectedIndex = index
        invalidate()
    }

    fun getSelectedFaceIndex(): Int = selectedIndex

    fun setOnFaceSelectedListener(listener: (Int) -> Unit) {
        onFaceSelectedListener = listener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        val scale = min(viewW / bmpW, viewH / bmpH)
        val dx = (viewW - bmpW * scale) / 2f
        val dy = (viewH - bmpH * scale) / 2f

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        inverseMatrix.reset()
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap

        if (bmp == null) {
            drawPlaceholder(canvas)
            return
        }

        drawRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(drawRect)
        canvas.drawBitmap(bmp, imageMatrix, null)

        faces.forEachIndexed { index, face ->
            val box = face.boundingBox
            val rect = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            imageMatrix.mapRect(rect)

            val isSelected = index == selectedIndex
            canvas.drawRect(rect, if (isSelected) selectedFillPaint else fillPaint)

            boxPaint.color = if (isSelected) colorSelected else colorDefault
            boxPaint.strokeWidth = if (isSelected) 6f else 4f
            canvas.drawRect(rect, boxPaint)

            val label = (index + 1).toString()
            val cx = rect.centerX()
            val top = rect.top - 8f
            val textY = if (top > 40f) top - 12f else rect.bottom + 40f
            canvas.drawCircle(cx, textY - 14f, 22f, labelBgPaint)
            canvas.drawText(label, cx, textY, labelPaint)
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_photo_placeholder) ?: return
        val size = (minOf(width, height) * 0.35f).toInt()
        val left = (width - size) / 2
        val top = (height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || faces.isEmpty()) return true

        val points = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(points)
        val x = points[0]
        val y = points[1]

        val hitIndex = faces.indexOfLast { face ->
            face.boundingBox.contains(x.toInt(), y.toInt())
        }

        if (hitIndex >= 0) {
            selectedIndex = hitIndex
            invalidate()
            onFaceSelectedListener?.invoke(hitIndex)
        }
        return true
    }
}
