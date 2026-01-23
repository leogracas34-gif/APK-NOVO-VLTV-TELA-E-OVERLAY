package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView

// Modelo de dados para o Ator
data class CastMember(
    val name: String,
    val profilePath: String?
)

class CastAdapter(private val castList: List<CastMember>) : 
    RecyclerView.Adapter<CastAdapter.ViewHolder>() {

    inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val imgActor: ShapeableImageView = v.findViewById(R.id.imgActor)
        val tvName: TextView = v.findViewById(R.id.tvActorName)
        
        init {
            // Configuração de foco para TV
            v.isFocusable = true
            v.setOnFocusChangeListener { _, hasFocus ->
                v.scaleX = if (hasFocus) 1.1f else 1.0f
                v.scaleY = if (hasFocus) 1.1f else 1.0f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val actor = castList[position]
        holder.tvName.text = actor.name

        // URL do TMDB para fotos
        val url = "https://image.tmdb.org/t/p/w185${actor.profilePath}"

        Glide.with(holder.itemView.context)
            .load(url)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_report_image)
            .into(holder.imgActor)
    }

    override fun getItemCount() = castList.size
}
