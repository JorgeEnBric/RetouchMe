package com.georgeb.retouchme.ui.edit

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.georgeb.retouchme.R
import com.georgeb.retouchme.databinding.ActivityPostProcessBinding
import com.georgeb.retouchme.face.PostProcessAdjustments
import com.georgeb.retouchme.face.PostProcessEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class PostProcessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostProcessBinding
    private var renderJob: Job? = null
    private var inputBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostProcessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputBitmap = PostProcessSession.bitmap
        if (inputBitmap == null) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.imagePreview.setImageBitmap(inputBitmap)

        setupChips()
        setupSliders()
        loadLastAdjustments()

        binding.btnCancel.setOnClickListener {
            PostProcessSession.clear()
            finish()
        }

        binding.btnApply.setOnClickListener { applyAndFinish() }
    }

    private fun setupChips() {
        binding.chipGroupMode.setOnCheckedChangeListener { _, checkedId ->
            val isFace = checkedId == binding.chipFace.id
            binding.layoutFaceSliders.visibility = if (isFace) View.VISIBLE else View.GONE
            binding.layoutGlobalSliders.visibility = if (isFace) View.GONE else View.VISIBLE
        }
    }

    private fun setupSliders() {
        val onChange = com.google.android.material.slider.Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) scheduleRender()
        }
        binding.sliderFaceBrightness.addOnChangeListener(onChange)
        binding.sliderFaceContrast.addOnChangeListener(onChange)
        binding.sliderFaceSaturation.addOnChangeListener(onChange)
        binding.sliderFaceWarmth.addOnChangeListener(onChange)
        binding.sliderBrightness.addOnChangeListener(onChange)
        binding.sliderContrast.addOnChangeListener(onChange)
        binding.sliderSaturation.addOnChangeListener(onChange)
        binding.sliderGamma.addOnChangeListener(onChange)
        binding.sliderWarmth.addOnChangeListener(onChange)
        binding.sliderSharpness.addOnChangeListener(onChange)
    }

    private fun loadLastAdjustments() {
        val last = PostProcessSession.lastAdjustments
        binding.sliderFaceBrightness.value = last.faceBrightness
        binding.sliderFaceContrast.value = last.faceContrast
        binding.sliderFaceSaturation.value = last.faceSaturation
        binding.sliderFaceWarmth.value = last.faceWarmth
        binding.sliderBrightness.value = last.brightness
        binding.sliderContrast.value = last.contrast
        binding.sliderSaturation.value = last.saturation
        binding.sliderGamma.value = (last.gamma * 100f).coerceIn(20f, 300f)
        binding.sliderWarmth.value = last.warmth
        binding.sliderSharpness.value = last.sharpness
    }

    private fun readAdjustments(): PostProcessAdjustments = PostProcessAdjustments(
        faceBrightness = binding.sliderFaceBrightness.value,
        faceContrast = binding.sliderFaceContrast.value,
        faceSaturation = binding.sliderFaceSaturation.value,
        faceWarmth = binding.sliderFaceWarmth.value,
        brightness = binding.sliderBrightness.value,
        contrast = binding.sliderContrast.value,
        saturation = binding.sliderSaturation.value,
        gamma = binding.sliderGamma.value / 100f,
        warmth = binding.sliderWarmth.value,
        sharpness = binding.sliderSharpness.value
    )

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
            val bitmap = inputBitmap ?: return@launch
            val adj = readAdjustments()

            binding.progressPreview.visibility = View.VISIBLE
            val result = withContext(Dispatchers.Default) {
                PostProcessEngine.apply(bitmap.copy(Bitmap.Config.ARGB_8888, true), PostProcessSession.mask, adj)
            }
            binding.imagePreview.setImageBitmap(result)
            binding.progressPreview.visibility = View.GONE
        }
    }

    private fun applyAndFinish() {
        val bitmap = inputBitmap ?: return finish()
        val adj = readAdjustments()

        PostProcessSession.lastAdjustments = adj

        binding.progressPreview.visibility = View.VISIBLE
        lifecycleScope.launch {
            val finalBitmap = withContext(Dispatchers.Default) {
                PostProcessEngine.apply(bitmap.copy(Bitmap.Config.ARGB_8888, true), PostProcessSession.mask, adj)
            }

            withContext(Dispatchers.IO) {
                saveToGallery(finalBitmap)
            }

            Toast.makeText(this@PostProcessActivity, R.string.image_saved_toast, Toast.LENGTH_SHORT).show()

            PostProcessSession.onApplied?.invoke(finalBitmap)
            PostProcessSession.clear()
            finish()
        }
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

    override fun onSupportNavigateUp(): Boolean {
        PostProcessSession.clear()
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        renderJob?.cancel()
    }
}
