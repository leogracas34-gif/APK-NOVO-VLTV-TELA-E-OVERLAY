package com.vltv.play

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

// 1. Atualizamos o modelo de dados para incluir a variável "isEmBreve"
data class NovidadeItem(
    val id: Int,
    val titulo: String,
    val sinopse: String,
    val imagemFundoUrl: String,
    val tagline: String, 
    val isTop10: Boolean = false, 
    val posicaoTop10: Int = 0,    
    val isEmBreve: Boolean = false, // <-- A MÁGICA DOS BOTÕES COMEÇA AQUI
    val trailerUrl: String? = null 
)

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String
) : RecyclerView.Adapter<NovidadesAdapter.NovidadeViewHolder>() {

    class NovidadeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // IDs novos que conectam com o item_novidade.xml premium
        val tvNumeroTop10: TextView = view.findViewById(R.id.tvNumeroTop10)
        val imgFundoNovidade: ImageView = view.findViewById(R.id.imgFundoNovidade)
        val btnPlayTrailer: ImageView = view.findViewById(R.id.btnPlayTrailer)
        val tvTituloNovidade: TextView = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView = view.findViewById(R.id.tvTagline)
        val tvSinopseNovidade: TextView = view.findViewById(R.id.tvSinopseNovidade)
        
        // Containers e botões novos
        val containerBotoesAtivos: LinearLayout = view.findViewById(R.id.containerBotoesAtivos)
        val btnAssistirNovidade: LinearLayout = view.findViewById(R.id.btnAssistirNovidade)
        val btnMinhaListaNovidade: LinearLayout = view.findViewById(R.id.btnMinhaListaNovidade)
        
        val containerBotaoAviso: LinearLayout = view.findViewById(R.id.containerBotaoAviso)
        val btnReceberAviso: LinearLayout = view.findViewById(R.id.btnReceberAviso)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovidadeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false)
        return NovidadeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NovidadeViewHolder, position: Int) {
        val item = lista[position]

        // Preenche os textos
        holder.tvTituloNovidade.text = item.titulo
        holder.tvSinopseNovidade.text = item.sinopse
        holder.tvTagline.text = item.tagline

        // Carrega a imagem gigante
        Glide.with(holder.itemView.context)
            .load(item.imagemFundoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imgFundoNovidade)

        // Lógica do Top 10 (Agora com o número gigante e sólido do XML)
        if (item.isTop10 && item.posicaoTop10 > 0) {
            holder.tvNumeroTop10.visibility = View.VISIBLE
            holder.tvNumeroTop10.text = item.posicaoTop10.toString().padStart(2, '0')
        } else {
            holder.tvNumeroTop10.visibility = View.GONE
        }

        // Lógica da Aba Em Breve (Troca os botões)
        if (item.isEmBreve) {
            holder.containerBotoesAtivos.visibility = View.GONE
            holder.containerBotaoAviso.visibility = View.VISIBLE
        } else {
            holder.containerBotoesAtivos.visibility = View.VISIBLE
            holder.containerBotaoAviso.visibility = View.GONE
        }

        // Cliques
        holder.btnPlayTrailer.setOnClickListener {
            abrirTrailer(holder.itemView, item.trailerUrl)
        }
        
        holder.btnAssistirNovidade.setOnClickListener {
            abrirTrailer(holder.itemView, item.trailerUrl)
        }

        holder.btnMinhaListaNovidade.setOnClickListener {
            Toast.makeText(holder.itemView.context, "${item.titulo} adicionado à Minha Lista!", Toast.LENGTH_SHORT).show()
        }

        holder.btnReceberAviso.setOnClickListener {
            Toast.makeText(holder.itemView.context, "Lembrete criado! Avisaremos quando chegar no servidor.", Toast.LENGTH_LONG).show()
        }
    }

    override fun getItemCount(): Int = lista.size

    fun atualizarLista(novaLista: List<NovidadeItem>) {
        lista = novaLista
        notifyDataSetChanged()
    }

    private fun abrirTrailer(view: View, trailerUrl: String?) {
        if (trailerUrl != null && trailerUrl.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$trailerUrl"))
            view.context.startActivity(intent)
        } else {
            Toast.makeText(view.context, "Trailer ainda não divulgado.", Toast.LENGTH_SHORT).show()
        }
    }
}
