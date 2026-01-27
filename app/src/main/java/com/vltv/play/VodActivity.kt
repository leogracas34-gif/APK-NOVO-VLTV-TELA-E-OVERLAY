package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class EpisodeData(
    val streamId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String
)

class VodActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView

    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var logoCachePrefs: SharedPreferences // ✅ Cache para logos

    private var cachedCategories: List<LiveCategory>? = null
    private val moviesCache = mutableMapOf<String, List<VodStream>>()
    private var favMoviesCache: List<VodStream>? = null

    private var categoryAdapter: VodCategoryAdapter? = null
    private var moviesAdapter: VodAdapter? = null

    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == 
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod) 

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvMovies = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        logoCachePrefs = getSharedPreferences("vltv_logo_links", Context.MODE_PRIVATE) // ✅ Inicializa cache de logos

        val searchInput = findViewById<View>(R.id.etSearchContent)
        searchInput?.isFocusableInTouchMode = false
        searchInput?.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).putExtra("initial_query", ""))
        }

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvMovies.layoutManager = GridLayoutManager(this, 5)

        carregarCategorias()
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvCategories.smoothScrollToPosition(0) }
        rvMovies.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvMovies.smoothScrollToPosition(0) }
    }

    // ✅ PRELOAD OTIMIZADO: Evita gargalo de rede
    private fun preLoadImages(filmes: List<VodStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            val limit = if (filmes.size > 25) 25 else filmes.size
            for (i in 0 until limit) {
                val url = filmes[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@VodActivity).load(url).diskCacheStrategy(DiskCacheStrategy.ALL).priority(Priority.LOW).preload(200, 300)
                    }
                }
            }
        }
    }

    private fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("+18") || n.contains("adult") || n.contains("xxx") || n.contains("hot") || n.contains("sexo")
    }

    private fun carregarCategorias() {
        cachedCategories?.let { aplicarCategorias(it); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodCategories(username, password).enqueue(object : Callback<List<LiveCategory>> {
            override fun onResponse(call: Call<List<LiveCategory>>, response: Response<List<LiveCategory>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val categorias = mutableListOf<LiveCategory>()
                    categorias.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                    categorias.addAll(response.body()!!)
                    cachedCategories = categorias
                    aplicarCategorias(if (ParentalControlManager.isEnabled(this@VodActivity)) categorias.filterNot { isAdultName(it.name) } else categorias)
                }
            }
            override fun onFailure(call: Call, t: Throwable) { progressBar.visibility = View.GONE }
        })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        categoryAdapter = VodCategoryAdapter(categorias) { categoria ->
            if (categoria.id == "FAV") carregarFilmesFavoritos() else carregarFilmes(categoria)
        }
        rvCategories.adapter = categoryAdapter
        categorias.firstOrNull { it.id != "FAV" }?.let { carregarFilmes(it) }
    }

    private fun carregarFilmes(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        moviesCache[categoria.id]?.let { aplicarFilmes(it); preLoadImages(it); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id).enqueue(object : Callback<List<VodStream>> {
            override fun onResponse(call: Call<List<VodStream>>, response: Response<List<VodStream>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val filmes = if (ParentalControlManager.isEnabled(this@VodActivity)) response.body()!!.filterNot { isAdultName(it.name) } else response.body()!!
                    moviesCache[categoria.id] = filmes
                    aplicarFilmes(filmes)
                    preLoadImages(filmes)
                }
            }
            override fun onFailure(call: Call, t: Throwable) { progressBar.visibility = View.GONE }
        })
    }

    private fun carregarFilmesFavoritos() {
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        val lista = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        aplicarFilmes(lista)
    }

    private fun aplicarFilmes(filmes: List<VodStream>) {
        moviesAdapter = VodAdapter(filmes, { abrirDetalhes(it) }, { mostrarMenuDownload(it) })
        rvMovies.adapter = moviesAdapter
    }

    private fun abrirDetalhes(filme: VodStream) {
        val intent = Intent(this, DetailsActivity::class.java)
        intent.putExtra("stream_id", filme.id).putExtra("name", filme.name).putExtra("icon", filme.icon).putExtra("rating", filme.rating ?: "0.0")
        startActivity(intent)
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        return context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).getStringSet("favoritos", emptySet())?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download) { Toast.makeText(this, "Baixando...", Toast.LENGTH_SHORT).show() }; true
        }
        popup.show()
    }

    // ================= ADAPTERS =================

    inner class VodCategoryAdapter(private val list: List<LiveCategory>, private val onClick: (LiveCategory) -> Unit) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {
        private var selectedPos = 0
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tvName: TextView = v.findViewById(R.id.tvName) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.tvName.text = item.name
            val isSel = selectedPos == p
            h.tvName.setTextColor(getColor(if (isSel) R.color.red_primary else R.color.gray_text))
            h.itemView.setOnClickListener { notifyItemChanged(selectedPos); selectedPos = h.adapterPosition; notifyItemChanged(selectedPos); onClick(item) }
        }
        override fun getItemCount() = list.size
    }

    // ✅ ADAPTER TURBINADO: Resolve o pisca-pisca e a lentidão
    inner class VodAdapter(private val list: List<VodStream>, private val onClick: (VodStream) -> Unit, private val onDownloadClick: (VodStream) -> Unit) : RecyclerView.Adapter<VodAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
            var job: Job? = null // Para cancelar buscas antigas no scroll
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_vod, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.job?.cancel() // ✅ CANCELA busca do item anterior (Essencial para parar o pisca)

            h.tvName.text = item.name
            h.tvName.visibility = View.VISIBLE // Texto visível por padrão
            h.imgLogo.visibility = View.GONE
            h.imgLogo.setImageDrawable(null)

            Glide.with(h.itemView.context).load(item.icon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.bg_logo_placeholder)
                .into(h.imgPoster)

            // ✅ LÓGICA DE CACHE DE LOGO: Se já buscou uma vez, carrega instantâneo
            val cachedLogoUrl = logoCachePrefs.getString("logo_${item.name}", null)
            if (cachedLogoUrl != null) {
                h.tvName.visibility = View.GONE
                h.imgLogo.visibility = View.VISIBLE
                Glide.with(h.itemView.context).load(cachedLogoUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(h.imgLogo)
            } else {
                // Se não tem no cache, busca no fundo
                h.job = CoroutineScope(Dispatchers.IO).launch {
                    val url = searchTmdbLogoSilently(item.name)
                    if (url != null) {
                        withContext(Dispatchers.Main) {
                            if (h.adapterPosition == p) { // Confere se ainda é o mesmo item
                                h.tvName.visibility = View.GONE
                                h.imgLogo.visibility = View.VISIBLE
                                Glide.with(h.itemView.context).load(url).into(h.imgLogo)
                            }
                        }
                    }
                }
            }

            h.itemView.setOnClickListener { onClick(item) }
            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.1f else 1.0f).scaleY(if (hasFocus) 1.1f else 1.0f).setDuration(150).start()
            }
        }
        override fun getItemCount() = list.size

        // ✅ BUSCA SILENCIOSA COM CACHE: Só vai na internet se precisar
        private suspend fun searchTmdbLogoSilently(rawName: String): String? {
            val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
            val cleanName = rawName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "").replace(Regex("\\b\\d{4}\\b"), "").trim()
            try {
                val query = URLEncoder.encode(cleanName, "UTF-8")
                val searchJson = URL("https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$query&language=pt-BR").readText()
                val results = JSONObject(searchJson).getJSONArray("results")
                if (results.length() > 0) {
                    val id = results.getJSONObject(0).getString("id")
                    val imgJson = URL("https://api.themoviedb.org/3/movie/$id/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null").readText()
                    val logos = JSONObject(imgJson).getJSONArray("logos")
                    if (logos.length() > 0) {
                        val finalUrl = "https://image.tmdb.org/t/p/w500${logos.getJSONObject(0).getString("file_path")}"
                        logoCachePrefs.edit().putString("logo_$rawName", finalUrl).apply() // ✅ SALVA NO CACHE
                        return finalUrl
                    }
                }
            } catch (e: Exception) {}
            return null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
