package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class SearchResultAdapter(
    private val onClick: (SearchResultItem) -> Unit
) : ListAdapter<SearchResultItem, SearchResultAdapter.VH>(SearchDiffCallback()) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vod, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.title

        // Usando o campo iconUrl que definimos no SearchResultItem
        Glide.with(holder.itemView.context)
            .load(item.iconUrl)
            .placeholder(R.mipmap.ic_launcher)
            .error(R.mipmap.ic_launcher)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache para ser mais rápido
            .into(holder.imgPoster)

        // Lógica de foco para Android TV / TV Box
        holder.itemView.isFocusable = true
        holder.itemView.isClickable = true
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            // Efeito visual de foco
            holder.itemView.alpha = if (hasFocus) 1.0f else 0.8f
            holder.itemView.scaleX = if (hasFocus) 1.05f else 1.0f
            holder.itemView.scaleY = if (hasFocus) 1.05f else 1.0f
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }
}

class SearchDiffCallback : DiffUtil.ItemCallback<SearchResultItem>() {
    override fun areItemsTheSame(old: SearchResultItem, new: SearchResultItem) = 
        old.id == new.id && old.type == new.type
        
    override fun areContentsTheSame(old: SearchResultItem, new: SearchResultItem) = 
        old == new
}
