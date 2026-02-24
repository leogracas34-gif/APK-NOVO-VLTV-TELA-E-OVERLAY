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
    val isSerie: Boolean = false,
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
        
        // Elementos ignorados do layout base
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
        
        holder.tvNumeroTop10.visibility = View.GONE
        holder.btnPlayTrailer.visibility = View.GONE
        holder.containerBotaoAviso.visibility = View.GONE

        Glide.with(context)
            .load(item.imagemFundoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imgFundoNovidade)

        if (item.isEmBreve) {
            holder.containerBotoesAtivos.visibility = View.GONE
        } else {
            // Busca no Banco de Dados local para cruzar com o TMDb
            CoroutineScope(Dispatchers.IO).launch {
                var idServidor: Int? = null
                var nomeServidor: String? = null
                var iconServidor: String? = null
                var ratingServidor: String? = null

                if (item.isSerie) {
                    val serie = database.streamDao().getRecentSeries(2000).firstOrNull { 
                        it.name.contains(item.titulo, ignoreCase = true) 
                    }
                    idServidor = serie?.series_id
                    nomeServidor = serie?.name
                    iconServidor = serie?.cover
                    ratingServidor = serie?.rating
                } else {
                    val filme = database.streamDao().searchVod(item.titulo).firstOrNull()
                    idServidor = filme?.stream_id
                    nomeServidor = filme?.name
                    iconServidor = filme?.stream_icon
                    ratingServidor = filme?.rating
                }

                withContext(Dispatchers.Main) {
                    if (idServidor != null) {
                        holder.containerBotoesAtivos.visibility = View.VISIBLE
                        holder.btnAssistirNovidade.setOnClickListener {
                            val intent = if (item.isSerie) {
                                Intent(context, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", idServidor)
                                }
                            } else {
                                Intent(context, DetailsActivity::class.java).apply {
                                    putExtra("stream_id", idServidor)
                                    putExtra("is_series", false)
                                }
                            }
                            
                            intent.apply {
                                putExtra("name", nomeServidor)
                                putExtra("icon", iconServidor)
                                putExtra("rating", ratingServidor ?: "0.0")
                                putExtra("PROFILE_NAME", currentProfile)
                            }
                            context.startActivity(intent)
                        }
                    } else {
                        holder.containerBotoesAtivos.visibility = View.GONE
                    }
                }
            }
        }

        holder.btnMinhaListaNovidade.setOnClickListener {
            toggleFavorito(context, item)
        }
    }

    private fun toggleFavorito(context: Context, item: NovidadeItem) {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        
        // Usa a lógica de cada Activity: Filmes em "vltv_favoritos" e Séries em "vltv_prefs"
        val key: String
        val targetPrefs = if (!item.isSerie) {
            key = "${currentProfile}_favoritos"
            context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        } else {
            key = "${currentProfile}_fav_series"
            prefs
        }

        val favoritos = targetPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        val idString = item.id.toString()

        if (favoritos.contains(idString)) {
            favoritos.remove(idString)
            Toast.makeText(context, "Removido da Minha Lista", Toast.LENGTH_SHORT).show()
        } else {
            favoritos.add(idString)
            Toast.makeText(context, "Adicionado à Minha Lista!", Toast.LENGTH_SHORT).show()
        }
        targetPrefs.edit().putStringSet(key, favoritos).apply()
    }

    override fun getItemCount(): Int = lista.size

    fun atualizarLista(novaLista: List<NovidadeItem>) {
        lista = novaLista
        notifyDataSetChanged()
    }
}
