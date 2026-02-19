package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

class HomeRowAdapter(
    private val list: List<VodItem>,
    private val viewTypeFormat: Int, // 🟢 NOVO: 1=Vertical, 2=Horizontal, 3=Top10
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    // Identificadores de formato baseados no nosso combinado
    companion object {
        const val TYPE_VERTICAL = 1   // Estilo Disney+ 2:3 (130x190dp)
        const val TYPE_HORIZONTAL = 2 // Estilo Continuar Assistindo 16:9
        const val TYPE_TOP10 = 3      // Estilo Ranking com números
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView? = view.findViewById(R.id.tvTitle)
        // Apenas para o layout horizontal (Continuar Assistindo)
        val progressLine: View? = view.findViewById(R.id.progressLine) 
        // Apenas para o Top 10
        val tvTopNumber: TextView? = view.findViewById(R.id.tvTopNumber) 
    }

    override fun getItemViewType(position: Int): Int = viewTypeFormat

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = when (viewType) {
            TYPE_VERTICAL -> R.layout.item_vod_vertical
            TYPE_HORIZONTAL -> R.layout.item_vod_horizontal
            TYPE_TOP10 -> R.layout.item_vod_top10
            else -> R.layout.item_vod_vertical
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        
        // Configuração de texto segura
        holder.tvTitle?.text = item.name
        
        // Se for Top 10, define o número do ranking
        holder.tvTopNumber?.text = (position + 1).toString()

        // Definição de resolução baseada no formato para não pesar a RAM
        val width = if (viewTypeFormat == TYPE_HORIZONTAL) 220 else 130
        val height = if (viewTypeFormat == TYPE_HORIZONTAL) 125 else 190

        Glide.with(holder.itemView.context)
            .asBitmap()
            .load(item.streamIcon)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(width, height) // 🟢 Padronização Disney+
            .centerCrop() // 🟢 Garante que a capa preencha o card sem sobras
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.ic_launcher)
            .thumbnail(0.1f)
            .into(holder.ivPoster)

        holder.itemView.setOnClickListener { onItemClick(item) }

        // Efeito de Foco Premium
        holder.itemView.isFocusable = true
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).translationZ(10f).setDuration(200).start()
                v.elevation = 10f
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(200).start()
                v.elevation = 0f
            }
        }
    }

    override fun getItemCount() = list.size
}
