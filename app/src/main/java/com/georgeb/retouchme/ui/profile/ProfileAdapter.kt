package com.georgeb.retouchme.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgeb.retouchme.R
import com.georgeb.retouchme.data.FaceProfileWithImages
import com.georgeb.retouchme.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onRename: (FaceProfileWithImages) -> Unit,
    private val onDelete: (FaceProfileWithImages) -> Unit,
    private val onAddPhotos: (FaceProfileWithImages) -> Unit,
    private val onRemoveFace: (FaceProfileWithImages, com.georgeb.retouchme.data.FaceImageEntity) -> Unit
) : ListAdapter<FaceProfileWithImages, ProfileAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val faceAdapter = FaceThumbnailAdapter { image ->
            val profile = getItem(bindingAdapterPosition)
            onRemoveFace(profile, image)
        }

        init {
            binding.recyclerFaces.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = faceAdapter
            }
        }

        fun bind(profileWithImages: FaceProfileWithImages) {
            val profile = profileWithImages.profile
            val images = profileWithImages.images

            binding.textProfileName.text = profile.name
            binding.textFaceCount.text = binding.root.context.getString(
                R.string.face_count, images.size
            )
            faceAdapter.submitList(images)

            binding.btnRename.setOnClickListener { onRename(profileWithImages) }
            binding.btnDelete.setOnClickListener { onDelete(profileWithImages) }
            binding.btnAddPhotos.setOnClickListener { onAddPhotos(profileWithImages) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<FaceProfileWithImages>() {
            override fun areItemsTheSame(old: FaceProfileWithImages, new: FaceProfileWithImages) =
                old.profile.id == new.profile.id

            override fun areContentsTheSame(old: FaceProfileWithImages, new: FaceProfileWithImages) =
                old == new
        }
    }
}
