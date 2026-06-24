package com.example.retake_lite.ui.swap

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.retake_lite.databinding.ItemPendingSwapBinding

data class PendingSwap(
    val faceIndex: Int,
    val faceLabel: String,
    val profileId: Long,
    val profileName: String,
    val referenceImageId: Long?,
    val isAutoSelected: Boolean = false  // opcional: para mostrar un ícono/badge "auto" en el RecyclerView de pendientes
)

class PendingSwapAdapter(
    private val onRemove: (PendingSwap) -> Unit
) : ListAdapter<PendingSwap, PendingSwapAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPendingSwapBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemPendingSwapBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PendingSwap) {
            binding.textPendingSwap.text = binding.root.context.getString(
                com.example.retake_lite.R.string.pending_swap_item,
                item.faceLabel,
                item.profileName
            )
            binding.btnRemovePending.setOnClickListener { onRemove(item) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<PendingSwap>() {
            override fun areItemsTheSame(old: PendingSwap, new: PendingSwap) =
                old.faceIndex == new.faceIndex

            override fun areContentsTheSame(old: PendingSwap, new: PendingSwap) = old == new
        }
    }
}
