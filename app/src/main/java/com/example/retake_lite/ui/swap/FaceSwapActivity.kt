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
    private var lastAssignments: List<FaceSwapAssignment> = emptyList()
    private val pendingSwaps = mutableListOf<PendingSwap>()

    /**
     * Toggle de modo de selección de referencia:
     *  - false (manual, default): se usa la foto que el usuario elige en
     *    recyclerReferenceFaces (selectedReferenceId), igual que antes.
     *  - true (automático): se delega en el FaceProfileModel de OpenFace
     *    (swapEngine.getAutoSelectedReferenceId) — se pasa referenceImageId
     *    = null en el assignment para que FaceRetakeEngine.resolveReference
     *    use el bestReferenceId del modelo en vez de forzar una foto.
     */
    private var useAutoSelection = false

    private lateinit var referenceAdapter: ReferenceFaceAdapter
    private lateinit var pendingAdapter: PendingSwapAdapter

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

        setupAdapters()

        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnAddSwap.setOnClickListener { addPendingSwap() }
        binding.btnSwap.setOnClickListener { performSwap() }
        binding.btnEditResult.setOnClickListener { openEditScreen() }

        // NUEVO: switch de selección automática. Requiere un
        // <com.google.android.material.switchmaterial.SwitchMaterial
        //     android:id="@+id/switchAutoSelect" .../>
        // en activity_face_swap.xml (ver nota al final de la respuesta).
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

    private fun setupAdapters() {
        referenceAdapter = ReferenceFaceAdapter { image ->
            selectedReferenceId = image.id
        }
        binding.recyclerReferenceFaces.apply {
            layoutManager = LinearLayoutManager(this@FaceSwapActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = referenceAdapter
        }

        pendingAdapter = PendingSwapAdapter { swap ->
            pendingSwaps.remove(swap)
            pendingAdapter.submitList(pendingSwaps.toList())
            updatePendingVisibility()
            updateSwapButtonState()
        }
        binding.recyclerPendingSwaps.apply {
            layoutManager = LinearLayoutManager(this@FaceSwapActivity)
            adapter = pendingAdapter
        }
    }

    /**
     * Se llama al cambiar el switch. En modo automático, el selector manual
     * se deshabilita visualmente (sigue mostrando las fotos, pero no se usa
     * la selección) y se resalta cuál foto eligió el modelo, para que el
     * usuario vea la decisión y pueda volver a manual si no le convence.
     */
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
            // Al volver a manual, se resalta lo último seleccionado a mano
            // (o la primera foto si nunca se eligió nada explícitamente).
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
        pendingSwaps.clear()
        pendingAdapter.submitList(emptyList())
        selectedFaceIndex = -1
        selectedReferenceId = null
        binding.imageResult.visibility = View.GONE
        binding.btnEditResult.visibility = View.GONE
        binding.cardSelection.visibility = View.GONE
        updatePendingVisibility()
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
                // En modo automático resaltamos la elección del modelo en
                // vez de la primera foto de la lista.
                val autoId = swapEngine.getAutoSelectedReferenceId(profileId, images)
                referenceAdapter.setSelection(autoId ?: images.first().id)
                // selectedReferenceId se deja como referencia de respaldo
                // para cuando el usuario vuelva a modo manual.
                selectedReferenceId = autoId ?: images.first().id
            } else {
                selectedReferenceId = images.first().id
                referenceAdapter.setSelection(images.first().id)
            }
        }
    }

    private fun addPendingSwap() {
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

        // CLAVE: en modo automático se guarda null como referenceImageId.
        // Eso es lo que hace que FaceRetakeEngine.resolveReference use el
        // bestReferenceId del FaceProfileModel en vez de forzar una foto
        // fija — y además, si el perfil cambia (se agregan/quitan fotos)
        // antes del swap final, la elección automática se recalcula con
        // el contenido más reciente en vez de quedar "congelada".
        val referenceId: Long? = if (useAutoSelection) null else (selectedReferenceId ?: images.first().id)
        val faceLabel = getString(R.string.face_number, selectedFaceIndex + 1)

        pendingSwaps.removeAll { it.faceIndex == selectedFaceIndex }
        pendingSwaps.add(
            PendingSwap(
                faceIndex = selectedFaceIndex,
                faceLabel = faceLabel,
                profileId = profile.id,
                profileName = profile.name,
                referenceImageId = referenceId,
                isAutoSelected = useAutoSelection
            )
        )

        pendingAdapter.submitList(pendingSwaps.toList())
        updatePendingVisibility()
        updateSwapButtonState()

        Snackbar.make(binding.root, R.string.swap_added, Snackbar.LENGTH_SHORT).show()
    }

    private fun updatePendingVisibility() {
        val hasPending = pendingSwaps.isNotEmpty()
        binding.textPendingTitle.visibility = if (hasPending) View.VISIBLE else View.GONE
        binding.recyclerPendingSwaps.visibility = if (hasPending) View.VISIBLE else View.GONE
    }

    private fun updateSwapButtonState() {
        binding.btnSwap.isEnabled = pendingSwaps.isNotEmpty() && sourceBitmap != null
    }

    private fun performSwap() {
        val bitmap = sourceBitmap ?: return
        if (pendingSwaps.isEmpty()) {
            Snackbar.make(binding.root, R.string.select_at_least_one_profile, Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSwap.isEnabled = false

        val assignments = pendingSwaps.map {
            FaceSwapAssignment(it.faceIndex, it.profileId, it.referenceImageId)
        }
        lastAssignments = assignments

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val profileImages = mutableMapOf<Long, List<FaceImageEntity>>()

                assignments.forEach { assignment ->
                    if (!profileImages.containsKey(assignment.profileId)) {
                        profileImages[assignment.profileId] =
                            profileImagesCache[assignment.profileId]
                                ?: repository.getImagesForProfile(assignment.profileId)
                    }
                }

                swapEngine.swapFaces(bitmap, detectedFaces, assignments, profileImages)
            }

            resultBitmap?.recycle()
            resultBitmap = result
            binding.imageResult.setImageBitmap(result)
            binding.imageResult.visibility = View.VISIBLE
            binding.btnEditResult.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.btnSwap.isEnabled = true

            Snackbar.make(binding.root, R.string.swap_complete, Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Abre RetakeEditActivity para ajustar manualmente zoom/rotación/halo/tono
     * del único rostro intercambiado en el último swap. Recalcula el
     * AutoRetakeResult (detección + alineación) una vez aquí, ya que
     * swapFaces() no lo expone — pero esta vez ya está cacheado en
     * FaceRetakeEngine.modelCache, así que es rápido.
     */
    private fun openEditScreen() {
        val assignment = lastAssignments.singleOrNull() ?: return
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