package com.example.retake_lite.ui.edit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.retake_lite.R
import com.example.retake_lite.databinding.ActivityRetakeEditBinding
import com.example.retake_lite.face.FaceAdjustments
import com.example.retake_lite.face.FaceMaskBuilder
import com.example.retake_lite.face.MediaPipeFaceMeshHelper
import com.example.retake_lite.face.PostProcessAdjustments
import com.example.retake_lite.face.PostProcessEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.sqrt

class RetakeEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRetakeEditBinding

    private var adjustments = FaceAdjustments()
    private var postAdjustments = PostProcessSession.lastAdjustments
    private var activeTool: Tool = Tool.ZOOM
    private var renderJob: Job? = null
    private var isPostVisible = false

    private var zoomMatrix = Matrix()
    private var scaleDetector: ScaleGestureDetector? = null
    private var displayZoom = 1f
    private var displayFocusX = 0f
    private var displayFocusY = 0f

    private var toneOverlay: Bitmap? = null
    private var toneCanvas: Canvas? = null
    private var toneIsSampled = false
    private var toneSampledColor = Color.TRANSPARENT
    private var toneIsPainting = false
    private var toneLastX = -1f
    private var toneLastY = -1f
    private var toneDownX = -1f
    private var toneDownY = -1f
    private var toneHasPaint = false
    private val toneLongPressHandler = Handler(Looper.getMainLooper())
    private var toneIsErasing = false
    private var haloEraseMask: Bitmap? = null
    private var haloEraseCanvas: Canvas? = null
    private var haloIsPainting = false
    private var haloLastX = -1f
    private var haloLastY = -1f
    private var haloDownX = -1f
    private var haloDownY = -1f
    private val haloUndoStack = mutableListOf<Bitmap>()
    private var toneLongPressBmpX = 0f
    private var toneLongPressBmpY = 0f
    private val toneLongPressRunnable = Runnable {
        sampleToneColor(toneLongPressBmpX, toneLongPressBmpY)
        binding.imagePreview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        scheduleRender()
    }



    private var mediaPipeHelper: MediaPipeFaceMeshHelper? = null
    private var faceContour: List<PointF>? = null
    private var sourceContourRaw: List<PointF>? = null
    private var showContourLines: Boolean = false
    private var showFrameExpandOverlay: Boolean = false

    private enum class Tool { ZOOM, ROTATE, POSITION, HALO, TONE, FRAME_EXPAND }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRetakeEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (RetakeEditSession.engine == null || RetakeEditSession.auto == null) {
            finish()
            return
        }

        adjustments = RetakeEditSession.lastAdjustments

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupToolbar()
        setupSlider()
        setupPosSliders()
        setupHaloSliders()
        setupPostChips()
        setupPostSliders()
        setupToneControls()
        setupFrameExpandSliders()
        setupPinchZoom()
        mediaPipeHelper = MediaPipeFaceMeshHelper(this)

        binding.btnCancelEdit.setOnClickListener {
            RetakeEditSession.clear()
            finish()
        }

        binding.btnTogglePost.setOnClickListener { togglePostPanel() }
        binding.btnApplySave.setOnClickListener { applyAndSave() }
        binding.btnPostCancel.setOnClickListener { hidePostPanel() }
        binding.btnPostApplySave.setOnClickListener { applyAndSave() }
        binding.layoutPostPanel.setOnClickListener { hidePostPanel() }

        selectTool(Tool.ZOOM)

        // Auto-activar halo mágico al abrir el editor (solo primera vez)
        if (!RetakeEditSession.magicHaloAutoApplied) {
            RetakeEditSession.magicHaloAutoApplied = true
            adjustments = adjustments.copy(magicHaloEnabled = true)
            RetakeEditSession.lastAdjustments = adjustments
            showContourLines = true
            lifecycleScope.launch {
                delay(2000)
                if (adjustments.magicHaloEnabled) {
                    showContourLines = false
                    scheduleRender()
                }
            }
        }

        renderPreview()
        preloadFaceContour()
    }

    private fun getFaceMatrix(auto: com.example.retake_lite.face.AutoRetakeResult): android.graphics.Matrix {
        val box = auto.targetFace.boundingBox
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val faceSize = kotlin.math.max(box.width(), box.height()).toFloat()
        val matrix = android.graphics.Matrix(auto.baseMatrix)
        matrix.postScale(adjustments.scale, adjustments.scale, cx, cy)
        matrix.postRotate(adjustments.rotationDegrees, cx, cy)
        matrix.postTranslate(adjustments.offsetXRatio * faceSize, adjustments.offsetYRatio * faceSize)
        return matrix
    }

    private fun transformPoints(points: List<PointF>, matrix: android.graphics.Matrix): List<PointF> {
        val src = FloatArray(points.size * 2)
        val dst = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            src[i * 2] = p.x; src[i * 2 + 1] = p.y
        }
        matrix.mapPoints(dst, src)
        return points.indices.map { PointF(dst[it * 2], dst[it * 2 + 1]) }
    }

    private fun preloadFaceContour() {
        val auto = RetakeEditSession.auto ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val allLandmarks = mediaPipeHelper?.detectFirstFace(auto.sourceBitmap)
            if (allLandmarks != null && allLandmarks.size >= 468) {
                val hull = FaceMaskBuilder.convexHull(allLandmarks)
                sourceContourRaw = FaceMaskBuilder.interpolateContour(hull, 200)
                withContext(Dispatchers.Main) {
                    if (activeTool == Tool.FRAME_EXPAND) scheduleRender()
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.toolToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tool = when (checkedId) {
                binding.toolZoom.id -> Tool.ZOOM
                binding.toolRotate.id -> Tool.ROTATE
                binding.toolPosition.id -> Tool.POSITION
                binding.toolHalo.id -> Tool.HALO
                binding.toolTone.id -> Tool.TONE
                binding.toolFrameExpand.id -> Tool.FRAME_EXPAND
                else -> Tool.ZOOM
            }
            selectTool(tool)
        }
    }

    private fun setupPinchZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                displayZoom = (displayZoom * detector.scaleFactor).coerceIn(0.5f, 5f)
                displayFocusX = detector.focusX
                displayFocusY = detector.focusY
                applyDisplayZoom()
                showZoomIndicator()
                return true
            }
        })

        binding.imagePreview.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            if (event.pointerCount <= 1) {
                if (binding.btnHaloEraser.isSelected) {
                    handleHaloTouch(event)
                } else if (binding.btnTonePaint.isSelected || binding.btnToneEraser.isSelected) {
                    handleToneTouch(event)
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                binding.zoomIndicator.hide()
            }
            true
        }
    }

    private fun applyDisplayZoom() {
        val bmp = (binding.imagePreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val viewW = binding.imagePreview.width.toFloat()
        val viewH = binding.imagePreview.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val fx = if (displayFocusX == 0f) viewW / 2f else displayFocusX
        val fy = if (displayFocusY == 0f) viewH / 2f else displayFocusY
        val base = fitCenterMatrix(
            bmp.width, bmp.height,
            viewW.toInt(), viewH.toInt()
        )
        base.postScale(displayZoom, displayZoom, fx, fy)
        zoomMatrix = base
        binding.imagePreview.imageMatrix = zoomMatrix
    }

    private fun showZoomIndicator() {
        val bmp = (binding.imagePreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val viewW = binding.imagePreview.width.toFloat()
        val viewH = binding.imagePreview.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val fx = if (displayFocusX == 0f) viewW / 2f else displayFocusX
        val fy = if (displayFocusY == 0f) viewH / 2f else displayFocusY
        if (displayZoom > 1.01f) {
            val vpW = viewW / displayZoom
            val vpH = viewH / displayZoom
            val left = (fx - vpW / 2f).coerceIn(0f, viewW - vpW)
            val top = (fy - vpH / 2f).coerceIn(0f, viewH - vpH)
            binding.zoomIndicator.visibility = View.VISIBLE
            binding.zoomIndicator.setViewport(RectF(left, top, left + vpW, top + vpH))
        }
    }

    private fun handleToneTouch(event: MotionEvent) {
        val bmp = (binding.imagePreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        if (toneOverlay == null || toneOverlay!!.width != bmp.width || toneOverlay!!.height != bmp.height) {
            initToneOverlay(bmp.width, bmp.height)
        }

        val p = screenToBitmap(event.x, event.y)
        binding.toneCursor.cursorX = event.x
        binding.toneCursor.cursorY = event.y
        binding.toneCursor.cursorRadius = adjustments.toneRadius
        binding.toneCursor.isErasing = toneIsErasing
        binding.toneCursor.invalidate()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                toneDownX = event.x; toneDownY = event.y
                toneIsPainting = false
                toneLastX = -1f
                toneLongPressBmpX = p.x; toneLongPressBmpY = p.y
                toneLongPressHandler.postDelayed(toneLongPressRunnable, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - toneDownX; val dy = event.y - toneDownY
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist > 15f) {
                    toneLongPressHandler.removeCallbacks(toneLongPressRunnable)
                    if (!toneIsPainting && (toneIsSampled || toneIsErasing)) {
                        toneIsPainting = true
                        toneLastX = p.x; toneLastY = p.y
                    }
                    if (toneIsPainting && toneLastX >= 0) {
                        val steps = (dist / 5f).toInt().coerceIn(1, 20)
                        for (i in 1..steps) {
                            val t = i.toFloat() / steps
                            val ix = toneLastX + (p.x - toneLastX) * t
                            val iy = toneLastY + (p.y - toneLastY) * t
                            if (toneIsErasing) eraseTone(ix, iy) else paintTone(ix, iy)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                toneLongPressHandler.removeCallbacks(toneLongPressRunnable)
                toneIsPainting = false
                toneLastX = -1f
            }
        }
    }

    private fun handleHaloTouch(event: MotionEvent) {
        val bmp = (binding.imagePreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        if (haloEraseMask == null || haloEraseMask!!.width != bmp.width || haloEraseMask!!.height != bmp.height) {
            haloEraseMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            haloEraseCanvas = Canvas(haloEraseMask!!)
        }

        val p = screenToBitmap(event.x, event.y)
        binding.toneCursor.cursorX = event.x
        binding.toneCursor.cursorY = event.y
        val matrixValues = FloatArray(9)
        binding.imagePreview.imageMatrix.getValues(matrixValues)
        val screenRadius = adjustments.haloBrushRadius * matrixValues[Matrix.MSCALE_X]
        binding.toneCursor.cursorRadius = screenRadius
        binding.toneCursor.isErasing = true
        binding.toneCursor.invalidate()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                haloDownX = event.x; haloDownY = event.y
                haloIsPainting = false
                haloLastX = -1f
                if (haloEraseMask != null) {
                    val snapshot = haloEraseMask!!.copy(Bitmap.Config.ARGB_8888, true)
                    if (haloUndoStack.size >= 20) {
                        haloUndoStack.removeFirst().recycle()
                    }
                    haloUndoStack.add(snapshot)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - haloDownX; val dy = event.y - haloDownY
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist > 15f) {
                    haloIsPainting = true
                    if (haloLastX < 0) { haloLastX = p.x; haloLastY = p.y }
                    val steps = (dist / 5f).toInt().coerceIn(1, 20)
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val ix = haloLastX + (p.x - haloLastX) * t
                        val iy = haloLastY + (p.y - haloLastY) * t
                        eraseHalo(ix, iy)
                    }
                    haloLastX = p.x; haloLastY = p.y
                    scheduleRender()
                }
            }
            MotionEvent.ACTION_UP -> {
                haloIsPainting = false
                haloLastX = -1f
                binding.toneCursor.isErasing = false
                binding.toneCursor.invalidate()
            }
        }
    }

    private fun eraseHalo(bmpX: Float, bmpY: Float) {
        val canvas = haloEraseCanvas ?: return
        val r = adjustments.haloBrushRadius
        val paint = Paint().apply {
            shader = android.graphics.RadialGradient(
                bmpX, bmpY, r,
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0.3f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(bmpX, bmpY, r, paint)
    }

    private fun fitCenterMatrix(bmpW: Int, bmpH: Int, viewW: Int, viewH: Int, zoom: Float = 1.5f): Matrix {
        val m = Matrix()
        val baseScale = kotlin.math.min(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH) * zoom
        val dx = (viewW - bmpW * baseScale) / 2f
        val dy = (viewH - bmpH * baseScale) / 2f
        m.setScale(baseScale, baseScale)
        m.postTranslate(dx, dy)
        return m
    }

    private fun selectTool(tool: Tool) {
        activeTool = tool
        binding.sliderTool.visibility = View.GONE
        binding.layoutPosSliders.visibility = View.GONE
        binding.layoutHaloSliders.visibility = View.GONE
        binding.layoutToneSliders.visibility = View.GONE
        binding.layoutFrameExpandSliders.visibility = View.GONE
        binding.toneCursor.visibility = View.GONE
        binding.toneCursor.isErasing = false
        binding.btnTonePaint.isSelected = false
        updateButtonTint(binding.btnTonePaint)
        binding.btnToneEraser.isSelected = false
        updateButtonTint(binding.btnToneEraser)
        binding.btnHaloEraser.isSelected = false
        updateButtonTint(binding.btnHaloEraser)
        toneIsErasing = false
        showFrameExpandOverlay = false

        when (tool) {
            Tool.ZOOM -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_zoom)
                binding.sliderTool.visibility = View.VISIBLE
                binding.sliderTool.valueFrom = -100f
                binding.sliderTool.valueTo = 100f
                binding.sliderTool.value = scaleToSlider(adjustments.scale)
                binding.toolToggleGroup.check(binding.toolZoom.id)
            }
            Tool.ROTATE -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_rotate)
                binding.sliderTool.visibility = View.VISIBLE
                binding.sliderTool.valueFrom = -25f
                binding.sliderTool.valueTo = 25f
                binding.sliderTool.value = adjustments.rotationDegrees.coerceIn(-25f, 25f)
                binding.toolToggleGroup.check(binding.toolRotate.id)
            }
            Tool.POSITION -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_position)
                binding.layoutPosSliders.visibility = View.VISIBLE
                binding.sliderPosX.value = (adjustments.offsetXRatio * 100f).coerceIn(-50f, 50f)
                binding.sliderPosY.value = (adjustments.offsetYRatio * 100f).coerceIn(-50f, 50f)
                binding.toolToggleGroup.check(binding.toolPosition.id)
            }
            Tool.HALO -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_halo)
                binding.layoutHaloSliders.visibility = View.VISIBLE
                binding.toolToggleGroup.check(binding.toolHalo.id)
                binding.sliderHaloIntensity.value = adjustments.haloEraseIntensity * 100f
                if (binding.btnHaloEraser.isSelected) {
                    binding.toneCursor.visibility = View.VISIBLE
                    binding.toneCursor.cursorRadius = adjustments.haloBrushRadius
                    val mv = FloatArray(9)
                    binding.imagePreview.imageMatrix.getValues(mv)
                    if (mv[Matrix.MSCALE_X] > 0f) {
                        binding.toneCursor.cursorRadius = adjustments.haloBrushRadius * mv[Matrix.MSCALE_X]
                    }
                    binding.toneCursor.isErasing = true
                    binding.toneCursor.invalidate()
                }
            }
            Tool.TONE -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_tone)
                binding.layoutToneSliders.visibility = View.VISIBLE
                binding.sliderToneRadius.value = adjustments.toneRadius
                binding.sliderToneIntensity.value = adjustments.toneIntensity * 100f
                binding.toneCursor.cursorRadius = adjustments.toneRadius
                binding.toneCursor.isSampled = toneIsSampled
                binding.toneCursor.sampledColor = toneSampledColor
                binding.toneCursor.visibility = if (binding.btnTonePaint.isSelected) View.VISIBLE else View.GONE
                updateToneStatus()
                binding.toolToggleGroup.check(binding.toolTone.id)
            }
            Tool.FRAME_EXPAND -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_frame_expand)
                binding.layoutFrameExpandSliders.visibility = View.VISIBLE
                binding.sliderFrameExpandX.value = (adjustments.frameExpandX * 100f).coerceIn(50f, 200f)
                binding.sliderFrameExpandY.value = (adjustments.frameExpandY * 100f).coerceIn(50f, 200f)
                binding.toolToggleGroup.check(binding.toolFrameExpand.id)
                showFrameExpandOverlay = true
                if (faceContour == null) preloadFaceContour()
                scheduleRender()
            }
        }
    }

    private fun setupSlider() {
        binding.sliderTool.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = when (activeTool) {
                Tool.ZOOM -> adjustments.copy(scale = sliderToScale(value))
                Tool.ROTATE -> adjustments.copy(rotationDegrees = value)
                else -> return@addOnChangeListener
            }
            scheduleRender()
        }
    }

    private fun setupPosSliders() {
        binding.sliderPosX.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(offsetXRatio = value / 100f)
            scheduleRender()
        }
        binding.sliderPosY.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(offsetYRatio = value / 100f)
            scheduleRender()
        }
    }

    private fun setupHaloSliders() {
        binding.sliderHaloRadius.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(haloBrushRadius = value)
        }
        binding.sliderHaloIntensity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(haloEraseIntensity = value / 100f)
        }
        binding.btnHaloEraser.setOnClickListener {
            binding.btnHaloEraser.isSelected = !binding.btnHaloEraser.isSelected
            updateButtonTint(binding.btnHaloEraser)
            if (binding.btnHaloEraser.isSelected) {
                binding.textHaloStatus.setText(R.string.tool_halo_paint_mode)
                binding.toneCursor.visibility = View.VISIBLE
                binding.toneCursor.cursorRadius = adjustments.haloBrushRadius
                val mv = FloatArray(9)
                binding.imagePreview.imageMatrix.getValues(mv)
                if (mv[Matrix.MSCALE_X] > 0f) {
                    binding.toneCursor.cursorRadius = adjustments.haloBrushRadius * mv[Matrix.MSCALE_X]
                }
                binding.toneCursor.isErasing = true
                binding.toneCursor.invalidate()
            } else {
                binding.textHaloStatus.setText(R.string.tool_halo_brush_hint)
                binding.toneCursor.visibility = View.GONE
            }
        }
        binding.btnHaloUndo.setOnClickListener {
            if (haloUndoStack.isNotEmpty()) {
                haloEraseMask?.recycle()
                haloEraseMask = haloUndoStack.removeLast()
                haloEraseCanvas = haloEraseMask?.let { Canvas(it) }
                scheduleRender()
            }
        }
    }

    private fun setupToneControls() {
        binding.sliderToneRadius.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(toneRadius = value)
            binding.toneCursor.cursorRadius = value
        }
        binding.sliderToneIntensity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(toneIntensity = value / 100f)
        }
        binding.btnToneEraser.setOnClickListener {
            binding.btnToneEraser.isSelected = !binding.btnToneEraser.isSelected
            updateButtonTint(binding.btnToneEraser)
            toneIsErasing = binding.btnToneEraser.isSelected
            if (binding.btnToneEraser.isSelected) {
                binding.btnTonePaint.isSelected = false
                updateButtonTint(binding.btnTonePaint)
                showToneCursor()
            } else {
                if (!binding.btnTonePaint.isSelected) {
                    hideToneCursor()
                }
            }
            updateToneStatus()
        }
        binding.btnTonePaint.setOnClickListener {
            binding.btnTonePaint.isSelected = !binding.btnTonePaint.isSelected
            updateButtonTint(binding.btnTonePaint)
            if (binding.btnTonePaint.isSelected) {
                binding.btnToneEraser.isSelected = false
                updateButtonTint(binding.btnToneEraser)
                toneIsErasing = false
                showToneCursor()
            } else {
                if (!binding.btnToneEraser.isSelected) {
                    hideToneCursor()
                }
            }
            updateToneStatus()
        }
    }

    private fun setupFrameExpandSliders() {
        binding.sliderFrameExpandX.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(frameExpandX = value / 100f)
            scheduleRender()
        }
        binding.sliderFrameExpandY.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(frameExpandY = value / 100f)
            scheduleRender()
        }
    }

    private fun drawContourDotsFromMask(bitmap: Bitmap, mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)

        val solid = BooleanArray(w * h) { Color.alpha(px[it]) > 127 }

        val edgePoints = mutableListOf<PointF>()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                if (!solid[y * w + x]) continue
                if (!solid[(y - 1) * w + x] || !solid[(y + 1) * w + x] ||
                    !solid[y * w + (x - 1)] || !solid[y * w + (x + 1)]) {
                    edgePoints.add(PointF(x.toFloat(), y.toFloat()))
                }
            }
        }

        if (edgePoints.size < 10) return bitmap

        val step = (edgePoints.size / 300).coerceAtLeast(1)

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 80, 80)
            style = Paint.Style.FILL
        }

        var i = 0
        while (i < edgePoints.size) {
            canvas.drawCircle(edgePoints[i].x, edgePoints[i].y, 4f, dotPaint)
            i += step
        }
        return out
    }

    private fun drawContourLine(bitmap: Bitmap, points: List<PointF>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f * minOf(bitmap.width, bitmap.height) / 800f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        canvas.drawPath(path, paint)
        return out
    }

    private fun expandContourPoints(points: List<PointF>, expandX: Float, expandY: Float): List<PointF> {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return points.map { p ->
            val newY = if (p.y < cy) {
                cy + (p.y - cy) * expandY
            } else {
                p.y
            }
            PointF(
                cx + (p.x - cx) * expandX,
                newY
            )
        }
    }

    private fun buildContourPath(points: List<PointF>): android.graphics.Path {
        val path = android.graphics.Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        return path
    }

    private fun toneCursorVisible() = binding.toneCursor.visibility == View.VISIBLE

    private fun showToneCursor() {
        val bmp = (binding.imagePreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bmp != null && (toneOverlay == null || toneOverlay!!.width != bmp.width || toneOverlay!!.height != bmp.height)) {
            toneOverlay?.recycle()
            toneOverlay = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            toneCanvas = Canvas(toneOverlay!!)
        }
        binding.toneCursor.visibility = View.VISIBLE
        binding.toneCursor.cursorRadius = adjustments.toneRadius
        binding.toneCursor.isSampled = toneIsSampled
        binding.toneCursor.sampledColor = toneSampledColor
        binding.toneCursor.isErasing = toneIsErasing
    }

    private fun hideToneCursor() {
        binding.toneCursor.visibility = View.GONE
    }

    private fun updateToneStatus() {
        binding.textToneStatus.setText(
            when {
                toneIsErasing -> R.string.tool_tone_eraser_active
                toneIsSampled -> R.string.tool_tone_paint
                else -> R.string.tool_tone_sample
            }
        )
        binding.imagePreview.isLongClickable = true
    }

    private fun initToneOverlay(w: Int, h: Int) {
        toneOverlay?.recycle()
        toneOverlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        toneCanvas = Canvas(toneOverlay!!)
        toneIsSampled = false
        toneSampledColor = Color.TRANSPARENT
        toneLastX = -1f
        toneLastY = -1f
        toneHasPaint = false
        toneIsErasing = false
        binding.btnToneEraser.isSelected = false
        binding.btnTonePaint.isSelected = false
    }

    private fun updateButtonTint(button: com.google.android.material.button.MaterialButton) {
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary)
        val transparent = android.graphics.Color.TRANSPARENT
        if (button.isSelected) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            button.setTextColor(android.graphics.Color.WHITE)
            button.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        } else {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(transparent)
            val onSurface = com.google.android.material.color.MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurface)
            button.setTextColor(onSurface)
            button.iconTint = android.content.res.ColorStateList.valueOf(onSurface)
        }
    }

    private fun screenToBitmap(sx: Float, sy: Float): PointF {
        val inv = Matrix()
        binding.imagePreview.imageMatrix.invert(inv)
        val pts = floatArrayOf(sx, sy)
        inv.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private fun sampleToneColor(bmpX: Float, bmpY: Float) {
        val bmp = binding.imagePreview.drawable?.let { d ->
            if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
        } ?: return
        val w = bmp.width; val h = bmp.height
        val ix = bmpX.toInt().coerceIn(0, w - 1)
        val iy = bmpY.toInt().coerceIn(0, h - 1)
        val r = (adjustments.toneRadius.coerceAtLeast(5f) / 2f).toInt().coerceIn(1, 50)

        var sumR = 0L; var sumG = 0L; var sumB = 0L; var count = 0L
        for (dy in -r..r) for (dx in -r..r) {
            val px = (ix + dx).coerceIn(0, w - 1)
            val py = (iy + dy).coerceIn(0, h - 1)
            if (dx * dx + dy * dy <= r * r) {
                val c = bmp.getPixel(px, py)
                sumR += Color.red(c); sumG += Color.green(c); sumB += Color.blue(c); count++
            }
        }
        if (count == 0L) return
        toneSampledColor = Color.rgb(
            (sumR / count).toInt().coerceIn(0, 255),
            (sumG / count).toInt().coerceIn(0, 255),
            (sumB / count).toInt().coerceIn(0, 255)
        )
        toneIsSampled = true
        toneHasPaint = false
        toneIsErasing = false
        toneOverlay?.let { ov ->
            ov.eraseColor(Color.TRANSPARENT)
        }
        binding.toneCursor.isSampled = true
        binding.toneCursor.sampledColor = toneSampledColor
        updateToneStatus()
    }

    private fun paintTone(bmpX: Float, bmpY: Float) {
        if (!toneIsSampled || toneCanvas == null) return
        val radius = (adjustments.toneRadius / 2f).coerceAtLeast(3f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = toneSampledColor
            style = Paint.Style.FILL
            alpha = 255
        }
        toneCanvas!!.drawCircle(bmpX, bmpY, radius, paint)
        toneHasPaint = true
        toneLastX = bmpX
        toneLastY = bmpY
        scheduleRender()
    }

    private fun eraseTone(bmpX: Float, bmpY: Float) {
        if (toneCanvas == null) return
        val radius = (adjustments.toneRadius / 2f).coerceAtLeast(3f)
        val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        toneCanvas!!.drawCircle(bmpX, bmpY, radius, erasePaint)
        toneHasPaint = true
        toneLastX = bmpX
        toneLastY = bmpY
        scheduleRender()
    }

    // --- Post-process: chips, sliders, toggle, save ---

    private fun setupPostChips() {
        binding.chipGroupMode.setOnCheckedChangeListener { _, checkedId ->
            val isFace = checkedId == binding.chipFace.id
            binding.layoutFaceSliders.visibility = if (isFace) View.VISIBLE else View.GONE
            binding.layoutGlobalSliders.visibility = if (isFace) View.GONE else View.VISIBLE
        }
    }

    private fun setupPostSliders() {
        val onChange = com.google.android.material.slider.Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) {
                postAdjustments = readPostAdjustments()
                PostProcessSession.lastAdjustments = postAdjustments
                scheduleRender()
            }
        }
        binding.sliderFaceBrightness.addOnChangeListener(onChange)
        binding.sliderFaceContrast.addOnChangeListener(onChange)
        binding.sliderFaceSaturation.addOnChangeListener(onChange)
        binding.sliderFaceWarmth.addOnChangeListener(onChange)
        binding.sliderFaceSmooth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                adjustments = adjustments.copy(smoothing = value / 100f)
                scheduleRender()
            }
        }
        binding.sliderBrightness.addOnChangeListener(onChange)
        binding.sliderContrast.addOnChangeListener(onChange)
        binding.sliderSaturation.addOnChangeListener(onChange)
        binding.sliderGamma.addOnChangeListener(onChange)
        binding.sliderWarmth.addOnChangeListener(onChange)
        binding.sliderSharpness.addOnChangeListener(onChange)

        loadPostAdjustments()
    }

    private fun loadPostAdjustments() {
        val last = PostProcessSession.lastAdjustments
        binding.sliderFaceBrightness.value = last.faceBrightness
        binding.sliderFaceContrast.value = last.faceContrast
        binding.sliderFaceSaturation.value = last.faceSaturation
        binding.sliderFaceWarmth.value = last.faceWarmth
        binding.sliderFaceSmooth.value = last.faceSmoothing * 100f
        binding.sliderBrightness.value = last.brightness
        binding.sliderContrast.value = last.contrast
        binding.sliderSaturation.value = last.saturation
        binding.sliderGamma.value = (last.gamma * 100f).coerceIn(20f, 300f)
        binding.sliderWarmth.value = last.warmth
        binding.sliderSharpness.value = last.sharpness
    }

    private fun readPostAdjustments(): PostProcessAdjustments = PostProcessAdjustments(
        faceBrightness = binding.sliderFaceBrightness.value,
        faceContrast = binding.sliderFaceContrast.value,
        faceSaturation = binding.sliderFaceSaturation.value,
        faceWarmth = binding.sliderFaceWarmth.value,
        faceSmoothing = binding.sliderFaceSmooth.value / 100f,
        brightness = binding.sliderBrightness.value,
        contrast = binding.sliderContrast.value,
        saturation = binding.sliderSaturation.value,
        gamma = binding.sliderGamma.value / 100f,
        warmth = binding.sliderWarmth.value,
        sharpness = binding.sliderSharpness.value
    )

    private fun togglePostPanel() {
        if (isPostVisible) hidePostPanel() else showPostPanel()
    }

    private fun showPostPanel() {
        isPostVisible = true
        binding.layoutPostPanel.visibility = View.VISIBLE
    }

    private fun hidePostPanel() {
        isPostVisible = false
        binding.layoutPostPanel.visibility = View.GONE
    }

    // --- Render pipeline ---

    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(120)
            renderPreview()
        }
    }

    private fun renderPreview() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val auto = RetakeEditSession.auto ?: return@launch
            val engine = RetakeEditSession.engine ?: return@launch

            binding.progressPreview.visibility = View.VISIBLE

            val faceMatrix = getFaceMatrix(auto)
            val transformedContour = if (sourceContourRaw != null) {
                transformPoints(sourceContourRaw!!, faceMatrix)
            } else null
            faceContour = transformedContour

            val isFrameExpandModified = adjustments.frameExpandX != 1.0f || adjustments.frameExpandY != 1.0f
            val maskContourPath = if (faceContour != null && isFrameExpandModified) {
                val expanded = expandContourPoints(faceContour!!, adjustments.frameExpandX, adjustments.frameExpandY)
                buildContourPath(expanded)
            } else if (adjustments.magicHaloEnabled && faceContour != null) {
                val pts = faceContour!!
                val path = android.graphics.Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
                path
            } else null

            val swapBitmap = withContext(Dispatchers.Default) {
                engine.render(auto, adjustments, haloEraseMask, maskContourPath)
            }

            val mask = withContext(Dispatchers.Default) {
                FaceMaskBuilder.createFaceMask(
                    swapBitmap.width, swapBitmap.height, auto.targetFace,
                    adjustments.edgeShrink,
                    haloEraseMask,
                    maskContourPath,
                    adjustments.haloEraseIntensity
                )
            }
            val filtered = withContext(Dispatchers.Default) {
                PostProcessEngine.apply(swapBitmap, mask, postAdjustments)
            }
            val result = applyToneOverlay(filtered)

            if (activeTool == Tool.FRAME_EXPAND && faceContour != null) {
                mask.recycle()
                if (filtered !== result) filtered.recycle()
                binding.imagePreview.setImageBitmap(result)
            } else if (adjustments.magicHaloEnabled) {
                val preview: Bitmap
                if (faceContour == null) {
                    val allLandmarks = withContext(Dispatchers.Default) {
                        mediaPipeHelper?.detectFirstFace(auto.sourceBitmap)
                    }
                    if (allLandmarks != null && allLandmarks.size >= 10) {
                        val hull = FaceMaskBuilder.convexHull(allLandmarks)
                        sourceContourRaw = FaceMaskBuilder.interpolateContour(hull, 200)
                        faceContour = transformPoints(sourceContourRaw!!, faceMatrix)
                        preview = if (showContourLines) drawContourLine(result, faceContour!!) else result
                    } else {
                        preview = result
                    }
                } else if (showContourLines) {
                    preview = drawContourLine(result, faceContour!!)
                } else {
                    preview = result
                }
                mask.recycle()
                if (preview !== result) result.recycle()
                if (filtered !== result) filtered.recycle()
                binding.imagePreview.setImageBitmap(preview)
            } else {
                mask.recycle()
                if (filtered !== result) filtered.recycle()
                binding.imagePreview.setImageBitmap(result)
            }
            applyDisplayZoom()
            binding.progressPreview.visibility = View.GONE
            RetakeEditSession.lastAdjustments = adjustments
        }
    }

    private fun applyAndSave() {
        val auto = RetakeEditSession.auto ?: return
        val engine = RetakeEditSession.engine ?: return

        postAdjustments = readPostAdjustments()
        PostProcessSession.lastAdjustments = postAdjustments

        binding.progressPreview.visibility = View.VISIBLE
        lifecycleScope.launch {
            val faceMatrix = getFaceMatrix(auto)
            val transformedContour = if (sourceContourRaw != null) {
                transformPoints(sourceContourRaw!!, faceMatrix)
            } else null
            faceContour = transformedContour

            val isFrameExpandModified2 = adjustments.frameExpandX != 1.0f || adjustments.frameExpandY != 1.0f
            val maskContourPath = if (faceContour != null && isFrameExpandModified2) {
                val expanded = expandContourPoints(faceContour!!, adjustments.frameExpandX, adjustments.frameExpandY)
                buildContourPath(expanded)
            } else if (adjustments.magicHaloEnabled && faceContour != null) {
                val pts = faceContour!!
                val path = android.graphics.Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
                path
            } else null
            val swapBitmap = withContext(Dispatchers.Default) {
                engine.render(auto, adjustments, haloEraseMask, maskContourPath)
            }
            val mask = withContext(Dispatchers.Default) {
                FaceMaskBuilder.createFaceMask(
                    swapBitmap.width, swapBitmap.height, auto.targetFace,
                    adjustments.edgeShrink,
                    haloEraseMask,
                    maskContourPath,
                    adjustments.haloEraseIntensity
                )
            }
            val filtered = withContext(Dispatchers.Default) {
                PostProcessEngine.apply(swapBitmap, mask, postAdjustments)
            }
            val finalBitmap = applyToneOverlay(filtered)
            if (finalBitmap !== filtered) filtered.recycle()
            mask.recycle()

            withContext(Dispatchers.IO) {
                saveToGallery(finalBitmap)
            }

            Toast.makeText(this@RetakeEditActivity, com.example.retake_lite.R.string.image_saved_toast, Toast.LENGTH_SHORT).show()

            RetakeEditSession.onApplied?.invoke(finalBitmap)
            RetakeEditSession.clear()
            finish()
        }
    }

    private fun applyToneOverlay(bitmap: Bitmap): Bitmap {
        val ov = toneOverlay
        if (ov == null || !toneHasPaint) return bitmap
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (adjustments.toneIntensity * 255).toInt()
        }
        canvas.drawBitmap(ov, 0f, 0f, paint)
        return out
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "retake_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Retouch Me")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } catch (_: Exception) {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun sliderToScale(value: Float): Float =
        if (value >= 0) 1f + (value / 100f) * 0.4f else 1f + (value / 100f) * 0.3f

    private fun scaleToSlider(scale: Float): Float =
        if (scale >= 1f) ((scale - 1f) / 0.4f) * 100f else ((scale - 1f) / 0.3f) * 100f

    override fun onSupportNavigateUp(): Boolean {
        RetakeEditSession.clear()
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        renderJob?.cancel()
        toneLongPressHandler.removeCallbacks(toneLongPressRunnable)
        toneOverlay?.recycle()
        toneOverlay = null
        mediaPipeHelper?.close()
        mediaPipeHelper = null
    }
}
