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
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NovidadeItem(
    val id: Int, // ID do TMDb
    val titulo: String,
    val sinopse: String,
    val imagemFundoUrl: String,
    val tagline: String,
    val isTop10: Boolean = false,
    val isEmBreve: Boolean = false
)

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String
) : RecyclerView.Adapter<NovidadesAdapter.NovidadeViewHolder>() {

    class NovidadeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgFundoNovidade: ImageView = view.findViewById(R.id.imgFundoNovidade)
        val tvTituloNovidade: TextView = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView = view.findViewById(R.id.tvTagline)
        val tvSinopseNovidade: TextView = view.findViewById(R.id.tvSinopseNovidade)
        
        val containerBotoesAtivos: LinearLayout = view.findViewById(R.id.containerBotoesAtivos)
        val btnAssistirNovidade: LinearLayout = view.findViewById(R.id.btnAssistirNovidade)
        val btnMinhaListaNovidade: LinearLayout = view.findViewById(R.id.btnMinhaListaNovidade)

        // IDs que agora são View fantasmas para não quebrar o layout antigo
        val tvNumeroTop10: View = view.findViewById(R.id.tvNumeroTop10)
        val btnPlayTrailer: View = view.findViewById(R.id.btnPlayTrailer)
        val containerBotaoAviso: View = view.findViewById(R.id.containerBotaoAviso)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovidadeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false)
        return NovidadeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NovidadeViewHolder, position: Int) {
        val item = lista[position]
        val context = holder.itemView.context
        val database = AppDatabase.getDatabase(context)

        holder.tvTituloNovidade.text = item.titulo
        holder.tvSinopseNovidade.text = item.sinopse
        holder.tvTagline.text = item.tagline

        Glide.with(context)
            .load(item.imagemFundoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imgFundoNovidade)

        // Esconde elementos removidos do design
        holder.tvNumeroTop10.visibility = View.GONE
        holder.btnPlayTrailer.visibility = View.GONE
        holder.containerBotaoAviso.visibility = View.GONE

        // Lógica de abas
        if (item.isEmBreve) {
            holder.containerBotoesAtivos.visibility = View.GONE
        } else {
            // CRUZAMENTO COM SERVIDOR: Verifica se o filme existe no banco local
            CoroutineScope(Dispatchers.IO).launch {
                val filmeServidor = database.streamDao().searchVod(item.titulo).firstOrNull()
                
                withContext(Dispatchers.Main) {
                    if (filmeServidor != null) {
                        holder.containerBotoesAtivos.visibility = View.VISIBLE
                        
                        // Botão Assistir -> Vai para Detalhes com o ID do seu servidor
                        holder.btnAssistirNovidade.setOnClickListener {
                            val intent = Intent(context, DetailsActivity::class.java).apply {
                                putExtra("stream_id", filmeServidor.stream_id)
                                putExtra("name", filmeServidor.name)
                                putExtra("icon", filmeServidor.stream_icon)
                                putExtra("rating", filmeServidor.rating ?: "0.0")
                                putExtra("PROFILE_NAME", currentProfile)
                            }
                            context.startActivity(intent)
                        }
                    } else {
                        // Se não tem no servidor, esconde os botões (apenas mostra a info)
                        holder.containerBotoesAtivos.visibility = View.GONE
                    }
                }
            }
        }

        // Lógica de Favoritos (Minha Lista)
        holder.btnMinhaListaNovidade.setOnClickListener {
            favoritarFilme(context, item)
        }
    }

    private fun favoritarFilme(context: Context, item: NovidadeItem) {
        val prefs = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        val key = "${currentProfile}_favoritos"
        val favoritos = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        
        // Aqui usamos o ID do item para favoritar
        if (favoritos.contains(item.id.toString())) {
            favoritos.remove(item.id.toString())
            Toast.makeText(context, "Removido da Minha Lista", Toast.LENGTH_SHORT).show()
        } else {
            favoritos.add(item.id.toString())
            Toast.makeText(context, "Adicionado à Minha Lista!", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet(key, favoritos).apply()
    }

    override fun getItemCount(): Int = lista.size

    fun atualizarLista(novaLista: List<NovidadeItem>) {
        lista = novaLista
        notifyDataSetChanged()
    }
}
