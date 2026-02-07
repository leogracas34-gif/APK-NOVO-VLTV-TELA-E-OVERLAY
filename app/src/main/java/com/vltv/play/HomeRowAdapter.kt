package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

class HomeRowAdapter(
    private val list: List<VodItem>,
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vod_card_horizontal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvTitle.text = item.name
        
        // Configuração otimizada para carregar imagens do Database instantaneamente
        Glide.with(holder.itemView.context)
            .asBitmap() // Carregamento mais rápido para listas
            .load(item.streamIcon)
            .format(DecodeFormat.PREFER_RGB_565) // 🟢 Reduz uso de RAM pela metade
            .override(180, 270) // 🟢 Redimensiona para o tamanho do card (evita processar pixels inúteis)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // 🟢 Prioriza o que já está no banco/disco
            .placeholder(R.drawable.ic_launcher)
            .thumbnail(0.1f) // 🟢 Carrega uma versão leve (10%) enquanto a original carrega
            .into(holder.ivPoster)

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.1f else 1.0f
            v.scaleY = if (hasFocus) 1.1f else 1.0f
            v.elevation = if (hasFocus) 10f else 0f
        }
    }

    override fun getItemCount() = list.size
}
