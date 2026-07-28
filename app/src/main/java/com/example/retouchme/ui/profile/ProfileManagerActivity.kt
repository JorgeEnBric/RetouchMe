package com.example.retouchme.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.retouchme.R
import com.example.retouchme.data.FaceImageEntity
import com.example.retouchme.data.FaceProfileWithImages
import com.example.retouchme.data.FaceRepository
import com.example.retouchme.databinding.ActivityProfileManagerBinding
import com.example.retouchme.face.FaceDetectorHelper
import com.example.retouchme.util.BitmapUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileManagerBinding
    private lateinit var repository: FaceRepository
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var adapter: ProfileAdapter

    private var pendingProfileId: Long? = null

    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> processSelectedPhotos(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = FaceRepository(this)
        faceDetector = FaceDetectorHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ProfileAdapter(
            onRename = ::showRenameDialog,
            onDelete = ::confirmDeleteProfile,
            onAddPhotos = { profile ->
                pendingProfileId = profile.profile.id
                pickPhotosLauncher.launch("image/*")
            },
            onRemoveFace = ::removeFace
        )

        binding.recyclerProfiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerProfiles.adapter = adapter

        binding.fabAddProfile.setOnClickListener { showCreateProfileDialog() }

        lifecycleScope.launch {
            repository.getAllProfilesWithImages().collectLatest { profiles ->
                adapter.submitList(profiles)
                binding.textEmpty.visibility =
                    if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showCreateProfileDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.profile_name_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_profile)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim().ifEmpty {
                    getString(R.string.default_profile_name, System.currentTimeMillis() % 1000)
                }
                lifecycleScope.launch {
                    repository.createProfile(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profileWithImages: FaceProfileWithImages) {
        val input = EditText(this).apply {
            setText(profileWithImages.profile.name)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_profile)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        repository.renameProfile(profileWithImages.profile, newName)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProfile(profileWithImages: FaceProfileWithImages) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, profileWithImages.profile.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    profileWithImages.images.forEach { File(it.imagePath).delete() }
                    repository.deleteProfile(profileWithImages.profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeFace(
        profileWithImages: FaceProfileWithImages,
        image: FaceImageEntity
    ) {
        lifecycleScope.launch {
            File(image.imagePath).delete()
            repository.deleteFaceImage(image)
        }
    }

    private fun processSelectedPhotos(uris: List<Uri>) {
        val profileId = pendingProfileId ?: return
        if (uris.isEmpty()) return

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            var totalFaces = 0
            var noFaceCount = 0

            withContext(Dispatchers.Default) {
                for (uri in uris) {
                    val bitmap = BitmapUtils.loadBitmap(this@ProfileManagerActivity, uri)
                        ?: continue
                    val faces = faceDetector.detectFaces(bitmap)
                    bitmap.recycle()

                    for (face in faces) {
                        repository.addFaceImage(
                            FaceImageEntity(
                                profileId = profileId,
                                imagePath = face.cropPath,
                                leftEyeX = face.leftEyeX,
                                leftEyeY = face.leftEyeY,
                                rightEyeX = face.rightEyeX,
                                rightEyeY = face.rightEyeY,
                                noseX = face.noseX,
                                noseY = face.noseY
                            )
                        )
                        totalFaces++
                    }
                    if (faces.isEmpty()) noFaceCount++
                }
            }

            binding.progressBar.visibility = View.GONE

            when {
                totalFaces > 0 -> Snackbar.make(
                    binding.root,
                    getString(R.string.faces_added, totalFaces),
                    Snackbar.LENGTH_SHORT
                ).show()

                noFaceCount > 0 -> Snackbar.make(
                    binding.root,
                    R.string.no_faces_in_photos,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetector.close()
    }
}
