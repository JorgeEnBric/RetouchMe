package com.example.retouchme.ui.swap

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.retouchme.data.FaceImageEntity
import com.example.retouchme.databinding.ItemReferenceFaceBinding
import com.google.android.material.color.MaterialColors

class ReferenceFaceAdapter(
    private val onSelected: (FaceImageEntity) -> Unit
) : ListAdapter<FaceImageEntity, ReferenceFaceAdapter.ViewHolder>(DiffCallback) {

    var selectedId: Long = -1L
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReferenceFaceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setSelection(imageId: Long) {
        selectedId = imageId
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemReferenceFaceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(image: FaceImageEntity) {
            binding.imageReference.load(image.imagePath)
            val selected = image.id == selectedId
            val stroke = if (selected) 4 else 0
            val color = if (selected) {
                MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            } else {
                ContextCompat.getColor(binding.root.context, android.R.color.transparent)
            }
            binding.imageReference.strokeWidth = stroke.toFloat()
            binding.imageReference.strokeColor = android.content.res.ColorStateList.valueOf(color)
            binding.root.setOnClickListener {
                selectedId = image.id
                notifyDataSetChanged()
                onSelected(image)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<FaceImageEntity>() {
            override fun areItemsTheSame(old: FaceImageEntity, new: FaceImageEntity) = old.id == new.id
            override fun areContentsTheSame(old: FaceImageEntity, new: FaceImageEntity) = old == new
        }
    }
}
