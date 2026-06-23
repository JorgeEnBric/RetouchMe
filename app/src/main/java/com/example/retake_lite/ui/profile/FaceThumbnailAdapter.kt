package com.example.retake_lite.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.retake_lite.data.FaceImageEntity
import com.example.retake_lite.databinding.ItemFaceThumbnailBinding

class FaceThumbnailAdapter(
    private val onRemove: (FaceImageEntity) -> Unit
) : ListAdapter<FaceImageEntity, FaceThumbnailAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFaceThumbnailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemFaceThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(image: FaceImageEntity) {
            binding.imageFace.load(image.imagePath)
            binding.btnRemoveFace.setOnClickListener { onRemove(image) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<FaceImageEntity>() {
            override fun areItemsTheSame(old: FaceImageEntity, new: FaceImageEntity) =
                old.id == new.id

            override fun areContentsTheSame(old: FaceImageEntity, new: FaceImageEntity) =
                old == new
        }
    }
}
