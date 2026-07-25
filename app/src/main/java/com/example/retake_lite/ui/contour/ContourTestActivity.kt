package com.example.retake_lite.ui.contour

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.retake_lite.databinding.ActivityContourTestBinding
import com.example.retake_lite.face.FaceMaskBuilder
import com.example.retake_lite.face.MediaPipeFaceMeshHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContourTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContourTestBinding
    private var meshHelper: MediaPipeFaceMeshHelper? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
        if (bitmap != null) detectAndDraw(bitmap)
        else Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContourTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        meshHelper = MediaPipeFaceMeshHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }
    }

    private fun detectAndDraw(source: Bitmap) {
        binding.progress.visibility = android.view.View.VISIBLE
        binding.textStatus.text = "Detectando rostro con MediaPipe Face Mesh..."

        lifecycleScope.launch {
            try {
                val landmarks = withContext(Dispatchers.Default) {
                    meshHelper?.detectFirstFace(source)
                }

                if (landmarks == null || landmarks.size < 10) {
                    binding.textStatus.text = "No se detectó ningún rostro"
                    binding.progress.visibility = android.view.View.GONE
                    binding.imageView.setImageBitmap(source)
                    return@launch
                }

                val result = withContext(Dispatchers.Default) { drawFaceMesh(source, landmarks) }
                binding.imageView.setImageBitmap(result)
                binding.textStatus.text = "Rostro detectado — ${landmarks.size} puntos MediaPipe Face Mesh"
                binding.progress.visibility = android.view.View.GONE

            } catch (e: Exception) {
                binding.textStatus.text = "Error: ${e.message}"
                binding.progress.visibility = android.view.View.GONE
            }
        }
    }

    private fun drawFaceMesh(bitmap: Bitmap, landmarks: List<PointF>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        val canvas = Canvas(out)

        val scale = minOf(bitmap.width, bitmap.height) / 800f

        val hull = FaceMaskBuilder.convexHull(landmarks)
        val denseContour = FaceMaskBuilder.interpolateContour(hull, 200)

        val pixelCount = FaceMaskBuilder.countPixelsInPath(bitmap.width, bitmap.height, denseContour)

        val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f * scale
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = android.graphics.Path()
        path.moveTo(denseContour[0].x, denseContour[0].y)
        for (i in 1 until denseContour.size) {
            path.lineTo(denseContour[i].x, denseContour[i].y)
        }
        path.close()
        canvas.drawPath(path, contourPaint)

        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f * scale
            isFakeBoldText = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        canvas.drawText("Contorno: ${denseContour.size} pts · ${pixelCount} px dentro", 20f, 50f, infoPaint)

        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        meshHelper?.close()
        meshHelper = null
    }
}
