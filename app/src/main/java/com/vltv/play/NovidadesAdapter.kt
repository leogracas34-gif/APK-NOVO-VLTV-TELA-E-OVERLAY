package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.bumptech.glide.load.DecodeFormat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String
) : RecyclerView.Adapter<NovidadesAdapter.NovidadeViewHolder>() {

    class NovidadeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgFundo: ImageView = view.findViewById(R.id.imgFundoNovidade)
        val imgLogo: ImageView = view.findViewById(R.id.imgLogoNovidade)
        val tvTitulo: TextView = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView = view.findViewById(R.id.tvTagline)
        val tvSinopse: TextView = view.findViewById(R.id.tvSinopseNovidade)
        val containerBotoes: LinearLayout = view.findViewById(R.id.containerBotoesAtivos)
        val btnAssistir: LinearLayout = view.findViewById(R.id.btnAssistirNovidade)
        val btnMinhaLista: LinearLayout = view.findViewById(R.id.btnMinhaListaNovidade)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovidadeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false)
        return NovidadeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NovidadeViewHolder, position: Int) {
        val item = lista[position]
        val context = holder.itemView.context
        val database = AppDatabase.getDatabase(context)
        val gridCachePrefs = context.getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)

        holder.job?.cancel()
        
        holder.tvTitulo.text = item.titulo
        
        holder.tvSinopse.text = item.sinopse
        holder.tvSinopse.visibility = View.VISIBLE
        
        holder.tvTagline.text = if (item.isTop10) "Top ${item.posicaoTop10} hoje" else item.tagline

        Glide.with(context)
            .load(item.imagemFundoUrl)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imgFundo)

        val cachedLogo = gridCachePrefs.getString("logo_${item.titulo}", null)
        if (cachedLogo != null) {
            holder.tvTitulo.visibility = View.GONE
            holder.imgLogo.visibility = View.VISIBLE
            Glide.with(context).load(cachedLogo).into(holder.imgLogo)
        } else {
            holder.tvTitulo.visibility = View.VISIBLE
            holder.imgLogo.visibility = View.GONE
            holder.job = CoroutineScope(Dispatchers.IO).launch {
                val logoUrl = searchTmdbLogo(item.titulo, item.isSerie, gridCachePrefs)
                if (logoUrl != null) {
                    withContext(Dispatchers.Main) {
                        if (holder.adapterPosition == position) {
                            holder.tvTitulo.visibility = View.GONE
                            holder.imgLogo.visibility = View.VISIBLE
                            Glide.with(context).load(logoUrl).into(holder.imgLogo)
                        }
                    }
                }
            }
        }

                // Garante que a sinopse SEMPRE apareça, independente de ser em breve ou não
        holder.tvSinopse.text = item.sinopse
        holder.tvSinopse.visibility = View.VISIBLE

        if (item.isEmBreve) {
            // Se for "Em Breve", apenas esconde os botões (já que não tem o que assistir ainda)
            holder.containerBotoes.visibility = View.GONE
        } else {
            // Se não for em breve, busca no banco de dados completo para liberar os botões
            CoroutineScope(Dispatchers.IO).launch {
                val streamEncontrado = if (item.isSerie) {
                    database.streamDao().getAllSeries().find { it.name.contains(item.titulo, true) }
                } else {
                    database.streamDao().getAllVods().find { it.name.contains(item.titulo, true) }
                }

                withContext(Dispatchers.Main) {
                    if (streamEncontrado != null) {
                        holder.containerBotoes.visibility = View.VISIBLE
                        holder.btnAssistir.setOnClickListener {
                            val intent = if (item.isSerie && streamEncontrado is SeriesEntity) {
                                Intent(context, SeriesDetailsActivity::class.java).apply { 
                                    putExtra("series_id", streamEncontrado.series_id)
                                    putExtra("name", streamEncontrado.name)
                                    putExtra("icon", streamEncontrado.cover)
                                    putExtra("rating", streamEncontrado.rating)
                                }
                            } else if (streamEncontrado is VodEntity) {
                                Intent(context, DetailsActivity::class.java).apply { 
                                    putExtra("stream_id", streamEncontrado.stream_id)
                                    putExtra("name", streamEncontrado.name)
                                    putExtra("poster", streamEncontrado.stream_icon)
                                    putExtra("icon", item.imagemFundoUrl ?: streamEncontrado.stream_icon)
                                    putExtra("rating", streamEncontrado.rating)
                                    putExtra("container_extension", streamEncontrado.container_extension)
                                    putExtra("is_series", false) 
                                }
                            } else { null }

                            intent?.let {
                                it.putExtra("PROFILE_NAME", currentProfile)
                                context.startActivity(it)
                            }
                        }
                        
                        holder.btnMinhaLista.setOnClickListener { 
                            val realId = if (item.isSerie) (streamEncontrado as SeriesEntity).series_id 
                                         else (streamEncontrado as VodEntity).stream_id
                            toggleFavorito(context, realId, item.isSerie) 
                        }
                    } else {
                        // Se por algum motivo o filtro da Activity passou algo que o banco não achou aqui
                        holder.containerBotoes.visibility = View.GONE
                    }
                }
            }
        }
    }

    private suspend fun searchTmdbLogo(name: String, isSerie: Boolean, prefs: SharedPreferences): String? {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        val type = if (isSerie) "tv" else "movie"
        try {
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&query=$encodedName&language=pt-BR"
            val searchResponse = URL(searchUrl).readText()
            val results = JSONObject(searchResponse).getJSONArray("results")
            
            if (results.length() > 0) {
                val tmdbId = results.getJSONObject(0).getString("id")
                val imagesUrl = "https://api.themoviedb.org/3/$type/$tmdbId/images?api_key=$apiKey&include_image_language=pt,en,null"
                val imagesResponse = URL(imagesUrl).readText()
                val logos = JSONObject(imagesResponse).getJSONArray("logos")
                
                if (logos.length() > 0) {
                    var path: String? = null
                    for (i in 0 until logos.length()) {
                        if (logos.getJSONObject(i).optString("iso_639_1") == "pt") {
                            path = logos.getJSONObject(i).getString("file_path")
                            break
                        }
                    }
                    if (path == null) path = logos.getJSONObject(0).getString("file_path")
                    val finalUrl = "https://image.tmdb.org/t/p/w500$path"
                    prefs.edit().putString("logo_$name", finalUrl).apply()
                    return finalUrl
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private fun toggleFavorito(context: Context, idNoServidor: Int, isSerie: Boolean) {
        val targetPrefs = if (isSerie) {
            context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        } else {
            context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        }

        val key = if (isSerie) "${currentProfile}_fav_series" else "${currentProfile}_favoritos"
        val favoritos = targetPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        val idStr = idNoServidor.toString()

        if (favoritos.contains(idStr)) {
            favoritos.remove(idStr)
            Toast.makeText(context, "Removido da sua lista", Toast.LENGTH_SHORT).show()
        } else {
            favoritos.add(idStr)
            Toast.makeText(context, "Adicionado à sua lista!", Toast.LENGTH_SHORT).show()
        }
        targetPrefs.edit().putStringSet(key, favoritos).apply()
    }

    override fun getItemCount() = lista.size

    fun atualizarLista(novaLista: List<NovidadeItem>) {
        lista = novaLista
        notifyDataSetChanged()
    }
}
