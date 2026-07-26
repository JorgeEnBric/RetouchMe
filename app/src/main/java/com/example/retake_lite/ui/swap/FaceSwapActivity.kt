package com.example.retake_lite.ui.swap

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.retake_lite.R
import com.example.retake_lite.data.FaceImageEntity
import com.example.retake_lite.data.FaceProfile
import com.example.retake_lite.data.FaceRepository
import com.example.retake_lite.databinding.ActivityFaceSwapBinding
import com.example.retake_lite.face.FaceDetectorHelper
import com.example.retake_lite.face.FaceSwapAssignment
import com.example.retake_lite.face.FaceSwapEngine
import com.example.retake_lite.ui.edit.RetakeEditActivity
import com.example.retake_lite.ui.edit.RetakeEditSession
import com.example.retake_lite.util.BitmapUtils
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FaceSwapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceSwapBinding
    private lateinit var repository: FaceRepository
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var swapEngine: FaceSwapEngine

    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var detectedFaces: List<Face> = emptyList()
    private var profiles: List<FaceProfile> = emptyList()
    private var profileImagesCache = mutableMapOf<Long, List<FaceImageEntity>>()
    private var selectedFaceIndex: Int = -1
    private var selectedReferenceId: Long? = null

    private var useAutoSelection = false

    private lateinit var referenceAdapter: ReferenceFaceAdapter

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { loadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceSwapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = FaceRepository(this)
        faceDetector = FaceDetectorHelper(this)
        swapEngine = FaceSwapEngine(this, faceDetector)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupReferenceAdapter()

        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnSwap.setOnClickListener { performSwap() }

        binding.switchAutoSelect.setOnCheckedChangeListener { _, isChecked ->
            useAutoSelection = isChecked
            onAutoSelectionToggled()
        }

        binding.faceOverlay.setOnFaceSelectedListener { index ->
            onFaceSelected(index)
        }

        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < profiles.size) {
                    loadReferenceFaces(profiles[position].id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        lifecycleScope.launch {
            profiles = repository.getAllProfiles().first()
        }
    }

    private fun setupReferenceAdapter() {
        referenceAdapter = ReferenceFaceAdapter { image ->
            selectedReferenceId = image.id
        }
        binding.recyclerReferenceFaces.apply {
            layoutManager = LinearLayoutManager(this@FaceSwapActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = referenceAdapter
        }
    }

    private fun onAutoSelectionToggled() {
        binding.recyclerReferenceFaces.alpha = if (useAutoSelection) 0.5f else 1.0f
        binding.recyclerReferenceFaces.isEnabled = !useAutoSelection

        val profilePosition = binding.spinnerProfile.selectedItemPosition
        if (profilePosition !in profiles.indices) return
        val profileId = profiles[profilePosition].id
        val images = profileImagesCache[profileId] ?: return
        if (images.isEmpty()) return

        if (useAutoSelection) {
            lifecycleScope.launch {
                val autoId = swapEngine.getAutoSelectedReferenceId(profileId, images)
                autoId?.let { referenceAdapter.setSelection(it) }
            }
        } else {
            referenceAdapter.setSelection(selectedReferenceId ?: images.first().id)
        }
    }

    private fun loadImage(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        resetSwapState()

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapUtils.loadBitmap(this@FaceSwapActivity, uri)
            }

            if (bitmap == null) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, R.string.error_loading_image, Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            sourceBitmap?.recycle()
            sourceBitmap = bitmap
            profiles = repository.getAllProfiles().first()
            profileImagesCache.clear()

            detectedFaces = withContext(Dispatchers.Default) {
                faceDetector.detectRawFaces(bitmap)
            }

            binding.faceOverlay.setImage(bitmap, detectedFaces)
            binding.progressBar.visibility = View.GONE
            updateUiAfterDetection()
        }
    }

    private fun resetSwapState() {
        resultBitmap?.recycle()
        resultBitmap = null
        selectedFaceIndex = -1
        selectedReferenceId = null
        binding.imageResult.visibility = View.GONE
        binding.cardSelection.visibility = View.GONE
        updateSwapButtonState()
    }

    private fun updateUiAfterDetection() {
        if (detectedFaces.isEmpty()) {
            binding.textNoFaces.visibility = View.VISIBLE
            binding.textTapHint.visibility = View.GONE
            binding.cardSelection.visibility = View.GONE
            return
        }

        binding.textNoFaces.visibility = View.GONE
        binding.textTapHint.visibility = View.VISIBLE

        if (profiles.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_profiles_warning, Snackbar.LENGTH_LONG).show()
            return
        }

        setupProfileSpinner()
    }

    private fun setupProfileSpinner() {
        val names = profiles.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerProfile.adapter = adapter
        if (profiles.isNotEmpty()) {
            loadReferenceFaces(profiles[0].id)
        }
    }

    private fun onFaceSelected(index: Int) {
        selectedFaceIndex = index
        binding.cardSelection.visibility = View.VISIBLE
        binding.textSelectedFace.text = getString(R.string.selected_face, index + 1)

        val profilePosition = binding.spinnerProfile.selectedItemPosition
        if (profilePosition in profiles.indices) {
            loadReferenceFaces(profiles[profilePosition].id)
        }
        updateSwapButtonState()
    }

    private fun loadReferenceFaces(profileId: Long) {
        lifecycleScope.launch {
            val images = profileImagesCache.getOrPut(profileId) {
                repository.getImagesForProfile(profileId)
            }
            referenceAdapter.submitList(images)

            if (images.isEmpty()) {
                selectedReferenceId = null
                return@launch
            }

            if (useAutoSelection) {
                val autoId = swapEngine.getAutoSelectedReferenceId(profileId, images)
                referenceAdapter.setSelection(autoId ?: images.first().id)
                selectedReferenceId = autoId ?: images.first().id
            } else {
                selectedReferenceId = images.first().id
                referenceAdapter.setSelection(images.first().id)
            }
        }
    }

    private fun updateSwapButtonState() {
        binding.btnSwap.isEnabled = selectedFaceIndex >= 0 && sourceBitmap != null && profiles.isNotEmpty()
    }

    private fun performSwap() {
        val bitmap = sourceBitmap ?: return
        if (selectedFaceIndex < 0) {
            Snackbar.make(binding.root, R.string.select_face_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        val profilePosition = binding.spinnerProfile.selectedItemPosition
        if (profilePosition !in profiles.indices) return

        val profile = profiles[profilePosition]
        val images = profileImagesCache[profile.id] ?: emptyList()
        if (images.isEmpty()) {
            Snackbar.make(binding.root, R.string.profile_has_no_faces, Snackbar.LENGTH_SHORT).show()
            return
        }

        val referenceId: Long? = if (useAutoSelection) null else (selectedReferenceId ?: images.first().id)
        val assignment = FaceSwapAssignment(selectedFaceIndex, profile.id, referenceId)

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSwap.isEnabled = false

        lifecycleScope.launch {
            val profileImages = mutableMapOf<Long, List<FaceImageEntity>>()
            profileImages[profile.id] = images

            val result = withContext(Dispatchers.Default) {
                swapEngine.swapFaces(bitmap, detectedFaces, listOf(assignment), profileImages)
            }

            resultBitmap?.recycle()
            resultBitmap = result
            binding.progressBar.visibility = View.GONE
            binding.btnSwap.isEnabled = true

            openEditScreen(assignment)
        }
    }

    private fun openEditScreen(assignment: FaceSwapAssignment) {
        val bitmap = sourceBitmap ?: return
        val targetFace = detectedFaces.getOrNull(assignment.faceIndex) ?: return

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val images = profileImagesCache[assignment.profileId]
                ?: withContext(Dispatchers.IO) { repository.getImagesForProfile(assignment.profileId) }

            val auto = withContext(Dispatchers.Default) {
                val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
                swapEngine.retakeEngine.computeAutoResult(
                    safeBitmap, targetFace, assignment.profileId, images, assignment.referenceImageId
                )
            }
            binding.progressBar.visibility = View.GONE

            if (auto == null) {
                Snackbar.make(binding.root, R.string.error_loading_image, Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            RetakeEditSession.start(
                engine = swapEngine.retakeEngine,
                auto = auto
            ) { editedBitmap ->
                resultBitmap?.recycle()
                resultBitmap = editedBitmap
                binding.imageResult.setImageBitmap(editedBitmap)
            }

            startActivity(Intent(this@FaceSwapActivity, RetakeEditActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        sourceBitmap?.recycle()
        resultBitmap?.recycle()
        faceDetector.close()
        swapEngine.close()
    }
}
