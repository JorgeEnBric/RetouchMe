package com.example.retake_lite.ui.edit

import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.retake_lite.databinding.ActivityRetakeEditBinding
import com.example.retake_lite.face.FaceAdjustments
import com.example.retake_lite.face.FaceMaskBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RetakeEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRetakeEditBinding

    private var adjustments = FaceAdjustments()
    private var activeTool: Tool = Tool.ZOOM
    private var renderJob: Job? = null

    private var zoomMatrix = Matrix()
    private var scaleDetector: ScaleGestureDetector? = null

    private enum class Tool { ZOOM, ROTATE, POSITION, HALO, COLOR }

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
        setupPinchZoom()

        binding.btnCancelEdit.setOnClickListener {
            RetakeEditSession.clear()
            finish()
        }

        binding.btnApplyEdit.setOnClickListener { applyAndFinish() }

        selectTool(Tool.ZOOM)
        renderPreview()
    }

    private fun setupToolbar() {
        binding.toolToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tool = when (checkedId) {
                binding.toolZoom.id -> Tool.ZOOM
                binding.toolRotate.id -> Tool.ROTATE
                binding.toolPosition.id -> Tool.POSITION
                binding.toolHalo.id -> Tool.HALO
                binding.toolColor.id -> Tool.COLOR
                else -> Tool.ZOOM
            }
            selectTool(tool)
        }
    }

    private fun setupPinchZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                zoomMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
                binding.imagePreview.imageMatrix = zoomMatrix
                return true
            }
        })

        binding.imagePreview.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            true
        }
    }

    private fun fitCenterMatrix(bmpW: Int, bmpH: Int, viewW: Int, viewH: Int): Matrix {
        val m = Matrix()
        val scale = kotlin.math.min(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val dx = (viewW - bmpW * scale) / 2f
        val dy = (viewH - bmpH * scale) / 2f
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        return m
    }

    private fun selectTool(tool: Tool) {
        activeTool = tool
        binding.sliderTool.visibility = View.GONE
        binding.layoutPosSliders.visibility = View.GONE
        binding.layoutHaloSliders.visibility = View.GONE

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
                binding.sliderHaloGeneral.value = adjustments.edgeShrink * 100f
                binding.sliderHaloLeft.value = adjustments.edgeShrinkLeft * 100f
                binding.sliderHaloRight.value = adjustments.edgeShrinkRight * 100f
                binding.sliderHaloTop.value = adjustments.edgeShrinkTop * 100f
                binding.sliderHaloBottom.value = adjustments.edgeShrinkBottom * 100f
                binding.toolToggleGroup.check(binding.toolHalo.id)
            }
            Tool.COLOR -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_color)
                binding.sliderTool.visibility = View.VISIBLE
                binding.sliderTool.valueFrom = -50f
                binding.sliderTool.valueTo = 50f
                binding.sliderTool.value = -adjustments.redGreenShift.coerceIn(-50f, 50f)
                binding.toolToggleGroup.check(binding.toolColor.id)
            }
        }
    }

    private fun setupSlider() {
        binding.sliderTool.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = when (activeTool) {
                Tool.ZOOM -> adjustments.copy(scale = sliderToScale(value))
                Tool.ROTATE -> adjustments.copy(rotationDegrees = value)
                Tool.COLOR -> adjustments.copy(redGreenShift = -value)
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
        binding.sliderHaloGeneral.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(edgeShrink = value / 100f)
            scheduleRender()
        }
        binding.sliderHaloLeft.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(edgeShrinkLeft = value / 100f)
            scheduleRender()
        }
        binding.sliderHaloRight.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(edgeShrinkRight = value / 100f)
            scheduleRender()
        }
        binding.sliderHaloTop.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(edgeShrinkTop = value / 100f)
            scheduleRender()
        }
        binding.sliderHaloBottom.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            adjustments = adjustments.copy(edgeShrinkBottom = value / 100f)
            scheduleRender()
        }
    }

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
            val bitmap = withContext(Dispatchers.Default) {
                engine.render(auto, adjustments)
            }
            binding.imagePreview.setImageBitmap(bitmap)
            zoomMatrix.reset()
            zoomMatrix = fitCenterMatrix(
                bitmap.width, bitmap.height,
                binding.imagePreview.width, binding.imagePreview.height
            )
            binding.imagePreview.imageMatrix = zoomMatrix
            binding.progressPreview.visibility = View.GONE
            RetakeEditSession.lastAdjustments = adjustments
        }
    }

    private fun applyAndFinish() {
        val auto = RetakeEditSession.auto ?: return finish()
        val engine = RetakeEditSession.engine ?: return finish()

        binding.progressPreview.visibility = View.VISIBLE
        lifecycleScope.launch {
            val finalBitmap = withContext(Dispatchers.Default) {
                engine.render(auto, adjustments)
            }
            val faceMask = withContext(Dispatchers.Default) {
                FaceMaskBuilder.createFaceMask(
                    finalBitmap.width, finalBitmap.height, auto.targetFace,
                    adjustments.edgeShrink,
                    adjustments.edgeShrinkLeft, adjustments.edgeShrinkRight,
                    adjustments.edgeShrinkTop, adjustments.edgeShrinkBottom
                )
            }
            val onFinalApplied = RetakeEditSession.onApplied
            RetakeEditSession.clear()
            PostProcessSession.start(finalBitmap, faceMask) { edited ->
                onFinalApplied?.invoke(edited)
            }
            val intent = Intent(this@RetakeEditActivity, PostProcessActivity::class.java)
            startActivity(intent)
            finish()
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
    }
}
