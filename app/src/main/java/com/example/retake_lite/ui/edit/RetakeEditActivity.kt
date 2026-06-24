package com.example.retake_lite.ui.edit

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.retake_lite.databinding.ActivityRetakeEditBinding
import com.example.retake_lite.face.FaceAdjustments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edición manual post-procesamiento: permite corregir el resultado del
 * swap automático sin recalcular detección/alineación, usando
 * FaceRetakeEngine.render() sobre el AutoRetakeResult guardado en
 * RetakeEditSession.
 *
 * Una sola herramienta activa a la vez (zoom / rotar / halo / tono),
 * cada una controla el mismo Slider pero con un rango y significado
 * distintos — así no se necesitan 4 sliders separados en pantalla.
 */
class RetakeEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRetakeEditBinding

    private var adjustments = FaceAdjustments()
    private var activeTool: Tool = Tool.ZOOM
    private var renderJob: Job? = null

    private enum class Tool { ZOOM, ROTATE, HALO, COLOR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRetakeEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (RetakeEditSession.engine == null || RetakeEditSession.auto == null) {
            // No debería pasar si siempre se navega vía RetakeEditSession.start(),
            // pero por seguridad evitamos un crash si la Activity se recrea sola
            // (p. ej. el sistema mata el proceso y la vuelve a crear).
            finish()
            return
        }

        adjustments = RetakeEditSession.lastAdjustments

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupToolbar()
        setupSlider()

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
                binding.toolHalo.id -> Tool.HALO
                binding.toolColor.id -> Tool.COLOR
                else -> Tool.ZOOM
            }
            selectTool(tool)
        }
    }

    private fun selectTool(tool: Tool) {
        activeTool = tool
        when (tool) {
            Tool.ZOOM -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_zoom)
                binding.sliderTool.valueFrom = -100f
                binding.sliderTool.valueTo = 100f
                binding.sliderTool.value = scaleToSlider(adjustments.scale)
                binding.toolToggleGroup.check(binding.toolZoom.id)
            }
            Tool.ROTATE -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_rotate)
                binding.sliderTool.valueFrom = -25f
                binding.sliderTool.valueTo = 25f
                binding.sliderTool.value = adjustments.rotationDegrees.coerceIn(-25f, 25f)
                binding.toolToggleGroup.check(binding.toolRotate.id)
            }
            Tool.HALO -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_halo)
                binding.sliderTool.valueFrom = 0f
                binding.sliderTool.valueTo = 100f
                binding.sliderTool.value = adjustments.edgeShrink * 100f
                binding.toolToggleGroup.check(binding.toolHalo.id)
            }
            Tool.COLOR -> {
                binding.textActiveTool.setText(com.example.retake_lite.R.string.tool_color)
                binding.sliderTool.valueFrom = -30f
                binding.sliderTool.valueTo = 30f
                // redGreenShift negativo = más verde. Mostramos el slider
                // invertido para que "mover a la derecha" siempre signifique
                // "menos verde / más cálido", más intuitivo para el usuario.
                binding.sliderTool.value = -adjustments.redGreenShift.coerceIn(-30f, 30f)
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
                Tool.HALO -> adjustments.copy(edgeShrink = value / 100f)
                Tool.COLOR -> adjustments.copy(redGreenShift = -value)
            }
            scheduleRender()
        }
    }

    /** Mapea -100..100 del slider a un rango de escala 0.7x..1.4x, con 0 = 1.0x (sin cambio). */
    private fun sliderToScale(value: Float): Float =
        if (value >= 0) 1f + (value / 100f) * 0.4f else 1f + (value / 100f) * 0.3f

    private fun scaleToSlider(scale: Float): Float =
        if (scale >= 1f) ((scale - 1f) / 0.4f) * 100f else ((scale - 1f) / 0.3f) * 100f

    /**
     * Debounce simple: espera 120ms tras el último movimiento del slider
     * antes de re-renderizar a resolución completa. Evita recalcular en
     * cada micro-movimiento mientras el usuario arrastra.
     */
    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(120)
            renderPreview()
        }
    }

    private fun renderPreview() {
        val auto = RetakeEditSession.auto ?: return
        val engine = RetakeEditSession.engine ?: return

        binding.progressPreview.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                engine.render(auto, adjustments)
            }
            binding.imagePreview.setImageBitmap(bitmap)
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
            RetakeEditSession.onApplied?.invoke(finalBitmap)
            RetakeEditSession.clear()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        RetakeEditSession.clear()
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        renderJob?.cancel()
        // No se llama RetakeEditSession.clear() aquí a propósito: si la
        // Activity se destruye por rotación de pantalla, queremos conservar
        // la sesión. Solo se limpia explícitamente en Cancelar/Aplicar/back.
    }
}