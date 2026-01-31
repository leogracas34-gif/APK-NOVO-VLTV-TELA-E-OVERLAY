package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.request.target.Target
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.graphics.Color

// --- IMPORTAÇÕES PARA A API DO TMDB E PERFORMANCE ---
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Job // Adicionado para controle de fluxo

class SeriesActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView

    private var username = ""
    private var password = ""
    private lateinit var seriesCachePrefs: SharedPreferences // ✅ NOVO: Armazena links das logos

    // Cache em memória
    private var cachedCategories: List<LiveCategory>? = null
    private val seriesCache = mutableMapOf<String, List<SeriesStream>>() // key = categoryId
    private var favSeriesCache: List<SeriesStream>? = null

    private var categoryAdapter: SeriesCategoryAdapter? = null
    private var seriesAdapter: SeriesAdapter? = null

    // ✅ FUNÇÃO PARA DETECTAR SE É TV BOX OU CELULAR
    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- USANDO O LAYOUT LIMPO (SEM PLAYER) ---
        setContentView(R.layout.activity_vod)
        // ---------------------------

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvSeries = findViewById(R.id.rvChannels) // O ID bate com o XML novo (activity_vod)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        // ✅ Inicializa o cache exclusivo para séries
        seriesCachePrefs = getSharedPreferences("vltv_series_cache", Context.MODE_PRIVATE)

        // ✅ BUSCA DIRETA (PULA A PONTE)
        val searchInput = findViewById<View>(R.id.etSearchContent)
        searchInput?.isFocusableInTouchMode = false // Impede abrir teclado aqui
        searchInput?.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            startActivity(intent)
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(60) // ✅ CACHE PARA NAVEGAÇÃO RÁPIDA
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER // ✅ REMOVE TREMEDEIRA
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvSeries.layoutManager = GridLayoutManager(this, 5)
        rvSeries.isFocusable = true
        rvSeries.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvSeries.setHasFixedSize(true)
        rvSeries.setItemViewCacheSize(100) // ✅ CACHE PARA PÔSTERES

        carregarCategorias()
    }

    // ✅ FUNÇÃO ATUALIZADA: DOUBLE PRELOAD (POSTER + LOGO TMDB)
    private fun preLoadImages(series: List<SeriesStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Pré-carrega os posters (Até 40)
            val limitPosters = if (series.size > 40) 40 else series.size
            for (i in 0 until limitPosters) {
                val url = series[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@SeriesActivity)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .priority(Priority.LOW) 
                            .preload(200, 300)
                    }
                }
            }

            // 2. Pré-carrega as logos do TMDB (Somente as primeiras 15 para não dar erro de limite)
            val limitLogos = if (series.size > 15) 15 else series.size
            for (i in 0 until limitLogos) {
                preLoadTmdbLogo(series[i].name)
            }
        }
    }

    // ✅ FUNÇÃO AUXILIAR PARA O PRELOAD DA LOGO
    private suspend fun preLoadTmdbLogo(rawName: String) {
        val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
        var cleanName = rawName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(Regex("\\b\\d{4}\\b"), "").trim()
            .replace(Regex("\\s+"), " ")

        try {
            val query = URLEncoder.encode(cleanName, "UTF-8")
            // ✅ CORREÇÃO: region=BR adicionado
            val searchJson = URL("https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR").readText()
            val results = JSONObject(searchJson).getJSONArray("results")

            if (results.length() > 0) {
                // ✅ FILTRO DE MATCH EXATO PARA O PRELOAD
                var bestResult = results.getJSONObject(0)
                for (j in 0 until results.length()) {
                    val obj = results.getJSONObject(j)
                    if (obj.optString("name", "").equals(cleanName, ignoreCase = true)) {
                        bestResult = obj
                        break
                    }
                }
                val seriesId = bestResult.getString("id")
                
                val imagesJson = URL("https://api.themoviedb.org/3/tv/$seriesId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null").readText()
                val logos = JSONObject(imagesJson).getJSONArray("logos")
                
                if (logos.length() > 0) {
                    var finalPath = ""
                    // ✅ CORREÇÃO: Prioridade para logo em PT
                    for (i in 0 until logos.length()) {
                        val lg = logos.getJSONObject(i)
                        if (lg.optString("iso_639_1") == "pt") {
                            finalPath = lg.getString("file_path")
                            break
                        }
                    }
                    if (finalPath.isEmpty()) finalPath = logos.getJSONObject(0).getString("file_path")
                    
                    val fullLogoUrl = "https://image.tmdb.org/t/p/w500$finalPath"
                    withContext(Dispatchers.Main) {
                        Glide.with(this@SeriesActivity)
                            .load(fullLogoUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .preload()
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // -------- helper p/ detectar adulto --------
    private fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("+18") ||
                n.contains("adult") ||
                n.contains("xxx") ||
                n.contains("hot") ||
                n.contains("sexo")
    }

    private fun carregarCategorias() {
        cachedCategories?.let { categoriasCacheadas ->
            aplicarCategorias(categoriasCacheadas)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getSeriesCategories(username, password)
            .enqueue(object : Callback<List<LiveCategory>> {
                override fun onResponse(
                    call: Call<List<LiveCategory>>,
                    response: Response<List<LiveCategory>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val originais = response.body()!!

                        var categorias = mutableListOf<LiveCategory>()
                        categorias.add(
                            LiveCategory(
                                category_id = "FAV_SERIES",
                                category_name = "FAVORITOS"
                            )
                        )
                        categorias.addAll(originais)

                        cachedCategories = categorias

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            categorias = categorias.filterNot { cat ->
                                isAdultName(cat.name)
                            }.toMutableList()
                        }

                        aplicarCategorias(categorias)
                    } else {
                        Toast.makeText(this@SeriesActivity, "Erro ao carregar categorias", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@SeriesActivity, "Falha de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) {
            Toast.makeText(this, "Nenhuma categoria disponível.", Toast.LENGTH_SHORT).show()
            rvCategories.adapter = SeriesCategoryAdapter(emptyList()) {}
            rvSeries.adapter = SeriesAdapter(emptyList()) {}
            return
        }

        categoryAdapter = SeriesCategoryAdapter(categorias) { categoria ->
            if (categoria.id == "FAV_SERIES") {
                carregarSeriesFavoritas()
            } else {
                carregarSeries(categoria)
            }
        }
        rvCategories.adapter = categoryAdapter

        val primeiraCategoriaNormal = categorias.firstOrNull { it.id != "FAV_SERIES" }
        if (primeiraCategoriaNormal != null) {
            carregarSeries(primeiraCategoriaNormal)
        } else {
            tvCategoryTitle.text = "FAVORITOS"
            carregarSeriesFavoritas()
        }
    }

    private fun carregarSeries(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name

        seriesCache[categoria.id]?.let { seriesCacheadas ->
            aplicarSeries(seriesCacheadas)
            preLoadImages(seriesCacheadas) // ✅ PRELOAD DO CACHE
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(
                    call: Call<List<SeriesStream>>,
                    response: Response<List<SeriesStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var series = response.body()!!

                        seriesCache[categoria.id] = series

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            series = series.filterNot { s ->
                                isAdultName(s.name)
                            }
                        }

                        aplicarSeries(series)
                        preLoadImages(series) // ✅ PRELOAD DA API
                    }
                }

                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun carregarSeriesFavoritas() {
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavSeries(this)

        if (favIds.isEmpty()) {
            rvSeries.adapter = SeriesAdapter(emptyList()) {}
            Toast.makeText(this, "Nenhuma série favorita.", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ ACELERAÇÃO: Tenta encontrar as séries já carregadas na memória (todas as abas)
        val listaFavoritosInstantanea = mutableListOf<SeriesStream>()
        seriesCache.values.flatten().distinctBy { it.id }.forEach { serie ->
            if (favIds.contains(serie.id)) {
                listaFavoritosInstantanea.add(serie)
            }
        }

        // Se encontrou as séries no cache, exibe instantaneamente sem chamar a API
        if (listaFavoritosInstantanea.size >= favIds.size) {
            aplicarSeries(listaFavoritosInstantanea)
            return
        }

        // Caso não estejam no cache, faz a busca padrão (Categoria 0)
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getSeries(username, password, categoryId = "0")
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(
                    call: Call<List<SeriesStream>>,
                    response: Response<List<SeriesStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var todas = response.body()!!
                        todas = todas.filter { favIds.contains(it.id) }

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            todas = todas.filterNot { s -> isAdultName(s.name) }
                        }

                        favSeriesCache = todas
                        aplicarSeries(todas)
                    }
                }

                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun aplicarSeries(series: List<SeriesStream>) {
        seriesAdapter = SeriesAdapter(series) { serie ->
            abrirDetalhesSerie(serie)
        }
        rvSeries.adapter = seriesAdapter
    }

    private fun abrirDetalhesSerie(serie: SeriesStream) {
        val intent = Intent(this@SeriesActivity, SeriesDetailsActivity::class.java)
        intent.putExtra("series_id", serie.id)
        intent.putExtra("name", serie.name)
        intent.putExtra("icon", serie.icon)
        intent.putExtra("rating", serie.rating ?: "0.0")
        startActivity(intent)
    }

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    // ================= ADAPTERS =================

    inner class SeriesCategoryAdapter(
        private val list: List<LiveCategory>,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.VH>() {

        private var selectedPos = 0

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            if (selectedPos == position) {
                holder.tvName.setTextColor(
                    holder.itemView.context.getColor(R.color.red_primary)
                )
                holder.tvName.setBackgroundColor(0xFF252525.toInt())
            } else {
                holder.tvName.setTextColor(
                    holder.itemView.context.getColor(R.color.gray_text)
                )
                holder.tvName.setBackgroundColor(0x00000000)
            }

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    // ✅ FOCO AMARELO + NEON + ZOOM NAS CATEGORIAS
                    holder.tvName.setTextColor(Color.YELLOW)
                    holder.tvName.textSize = 20f
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    holder.tvName.textSize = 16f
                    view.setBackgroundResource(0)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    if (selectedPos != holder.adapterPosition) {
                        holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text))
                        holder.tvName.setBackgroundColor(0x00000000)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos)
                selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    inner class SeriesAdapter(
        private val list: List<SeriesStream>,
        private val onClick: (SeriesStream) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
            val imgDownload: ImageView = v.findViewById(R.id.imgDownload)
            var job: Job? = null // Adicionado para controle de fluxo
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vod, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.job?.cancel() // ✅ Para buscas do item anterior ao reciclar
            
            holder.tvName.visibility = View.GONE
            holder.imgLogo.setImageDrawable(null)
            holder.imgLogo.visibility = View.INVISIBLE
            holder.imgDownload.visibility = View.GONE

            val context = holder.itemView.context
            
            Glide.with(context)
                .load(item.icon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(if (isTelevision(context)) Priority.HIGH else Priority.IMMEDIATE)
                .thumbnail(0.1f)
                .placeholder(R.drawable.bg_logo_placeholder)
                .error(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .into(holder.imgPoster)

            // ✅ Lógica de Cache para Parar o Pisca-Pisca
            val cachedUrl = seriesCachePrefs.getString("logo_${item.name}", null)
            if (cachedUrl != null) {
                holder.tvName.visibility = View.GONE
                holder.imgLogo.visibility = View.VISIBLE
                Glide.with(context).load(cachedUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.imgLogo)
            } else {
                holder.job = CoroutineScope(Dispatchers.IO).launch {
                    searchTmdbLogo(item.name, holder.imgLogo, position, holder)
                }
            }

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    // ✅ FOCO AMARELO + NEON + ZOOM 1.15f
                    holder.tvName.setTextColor(Color.YELLOW)
                    holder.tvName.textSize = 18f
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                    view.elevation = 20f
                    view.setBackgroundResource(R.drawable.bg_focus_neon) // Aplica borda neon
                    if (holder.imgLogo.visibility != View.VISIBLE) holder.tvName.visibility = View.VISIBLE
                    view.alpha = 1.0f
                } else {
                    holder.tvName.setTextColor(Color.WHITE)
                    holder.tvName.textSize = 14f
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    view.elevation = 4f
                    view.setBackgroundResource(0) // Remove borda
                    holder.tvName.visibility = View.GONE
                    view.alpha = 0.8f
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size

        private suspend fun searchTmdbLogo(rawName: String, targetView: ImageView, pos: Int, holder: VH) {
            var cleanName = rawName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
            val sujeiras = listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUB", "MKV", "MP4", "COMPLETE", "S01", "S02", "E01")
            sujeiras.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
            cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

            try {
                val query = URLEncoder.encode(cleanName, "UTF-8")
                // ✅ CORREÇÃO: region=BR adicionado
                val searchJson = URL("https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR").readText()
                val results = JSONObject(searchJson).getJSONArray("results")

                if (results.length() > 0) {
                    var bestResult = results.getJSONObject(0)
                    val seriesId = bestResult.getString("id")
                    val imagesJson = URL("https://api.themoviedb.org/3/tv/$seriesId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null").readText()
                    val logos = JSONObject(imagesJson).getJSONArray("logos")
                    
                    if (logos.length() > 0) {
                        var finalPath = ""
                        // ✅ CORREÇÃO: Prioridade para logo em PT
                        for (i in 0 until logos.length()) {
                            val lg = logos.getJSONObject(i)
                            if (lg.optString("iso_639_1") == "pt") {
                                finalPath = lg.getString("file_path")
                                break
                            }
                        }
                        if (finalPath.isEmpty()) finalPath = logos.getJSONObject(0).getString("file_path")
                        
                        val finalUrl = "https://image.tmdb.org/t/p/w500$finalPath"
                        
                        // ✅ Salva no Cache
                        seriesCachePrefs.edit().putString("logo_$rawName", finalUrl).apply()

                        withContext(Dispatchers.Main) {
                            if (holder.adapterPosition == pos) {
                                targetView.visibility = View.VISIBLE
                                Glide.with(targetView.context).load(finalUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(targetView)
                                holder.tvName.visibility = View.GONE
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }
}
