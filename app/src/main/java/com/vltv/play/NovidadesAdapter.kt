package com.vltv.play

import android.content.Intent
import android.graphics.Color
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

// 1. Criamos um modelo de dados para organizar as informações que virão da API
data class NovidadeItem(
    val id: Int,
    val titulo: String,
    val sinopse: String,
    val imagemFundoUrl: String,
    val tagline: String, // Ex: "Estreia dia 25 de Novembro"
    val isTop10: Boolean = false, // Avisa o Adapter se deve mostrar o número gigante
    val posicaoTop10: Int = 0,    // O número em si (1, 2, 3...)
    val trailerUrl: String? = null // Link do YouTube
)

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String
) : RecyclerView.Adapter<NovidadesAdapter.NovidadeViewHolder>() {

    class NovidadeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTopNumber: TextView = view.findViewById(R.id.tvTopNumber)
        val imgBackdrop: ImageView = view.findViewById(R.id.imgBackdrop)
        val imgPlay: ImageView = view.findViewById(R.id.imgPlay)
        val tvTitulo: TextView = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView = view.findViewById(R.id.tvTagline)
        val tvSinopse: TextView = view.findViewById(R.id.tvSinopse)
        val btnAssistir: LinearLayout = view.findViewById(R.id.btnAssistir)
        val btnMinhaLista: LinearLayout = view.findViewById(R.id.btnMinhaLista)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovidadeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false)
        return NovidadeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NovidadeViewHolder, position: Int) {
        val item = lista[position]

        // Preenche os textos
        holder.tvTitulo.text = item.titulo
        holder.tvSinopse.text = item.sinopse
        holder.tvTagline.text = item.tagline

        // Carrega a imagem gigante (Backdrop)
        Glide.with(holder.itemView.context)
            .load(item.imagemFundoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imgBackdrop)

        // A Mágica do Top 10 (Mostra ou Esconde o número gigante)
        if (item.isTop10 && item.posicaoTop10 > 0) {
            holder.tvTopNumber.visibility = View.VISIBLE
            // Formata para ficar com zero na frente (ex: "01", "02", "10")
            holder.tvTopNumber.text = item.posicaoTop10.toString().padStart(2, '0')
            
            // Efeito visual para deixar a letra "vazada" com borda clara
            holder.tvTopNumber.setTextColor(Color.TRANSPARENT)
            holder.tvTopNumber.setShadowLayer(4f, 0f, 0f, Color.LTGRAY) 
        } else {
            holder.tvTopNumber.visibility = View.GONE
        }

        // Clique no Play Gigante para abrir o Trailer no YouTube
        holder.imgPlay.setOnClickListener {
            abrirTrailer(holder.itemView, item.trailerUrl)
        }
        
        // Botão Assistir (Também abre o trailer, já que filmes 'Em Breve' ainda não tem no servidor)
        holder.btnAssistir.setOnClickListener {
            abrirTrailer(holder.itemView, item.trailerUrl)
        }

        // Botão Minha Lista (Apenas um aviso por enquanto, depois você pode ligar ao seu sistema de Favoritos)
        holder.btnMinhaLista.setOnClickListener {
            Toast.makeText(holder.itemView.context, "${item.titulo} adicionado à Minha Lista!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = lista.size

    // Função para atualizar a lista instantaneamente quando o usuário trocar de aba
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
