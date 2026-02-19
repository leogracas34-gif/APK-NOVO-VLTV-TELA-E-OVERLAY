package com.vltv.play.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vltv.play.data.TmdbPerson
import com.vltv.play.data.TmdbClient
import com.vltv.play.databinding.ItemAvatarBinding

class AvatarAdapter(
    private val avatars: List<TmdbPerson>,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.Adapter<AvatarAdapter.AvatarViewHolder>() {

    inner class AvatarViewHolder(val binding: ItemAvatarBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
        val binding = ItemAvatarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AvatarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
        val avatar = avatars[position]
        val fullUrl = TmdbClient.getFullImageUrl(avatar.profile_path)

        // Usando o Glide para carregar a foto do personagem no círculo
        Glide.with(holder.itemView.context)
            .load(fullUrl)
            .centerCrop()
            .placeholder(android.R.drawable.progress_indeterminate_horizontal) // Mostra algo enquanto carrega
            .into(holder.binding.ivAvatar)

        holder.itemView.setOnClickListener {
            fullUrl?.let { url -> onAvatarClick(url) }
        }
    }

    override fun getItemCount(): Int = avatars.size
}
