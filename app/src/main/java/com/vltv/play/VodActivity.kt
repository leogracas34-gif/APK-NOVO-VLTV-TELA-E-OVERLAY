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
import android.graphics.Color

// ✅ MANTIDO: Sem redeclaração de EpisodeData
class VodActivity : AppCompatActivity() {
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var gridCachePrefs: SharedPreferences
    private var cachedCategories: List<LiveCategory>? = null
    private val moviesCache = mutableMapOf<String, List<VodStream>>()
    private var favMoviesCache: List<VodStream>? = null
    private var categoryAdapter: VodCategoryAdapter? = null
    private var moviesAdapter: VodAdapter? = null

    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
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
        gridCachePrefs = getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)

        val searchInput = findViewById<View>(R.id.etSearchContent)
        searchInput?.isFocusableInTouchMode = false
        searchInput?.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            startActivity(intent)
        }

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50) // ✅ CACHE PARA NAVEGAÇÃO RÁPIDA
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER // ✅ REMOVE TREMEDEIRA
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvMovies.layoutManager = GridLayoutManager(this, 5)
        rvMovies.isFocusable = true
        rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(100) // ✅ CACHE PARA EVITAR LAG NOS PÔSTERES

        rvCategories.requestFocus()
        carregarCategorias()
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvCategories.smoothScrollToPosition(0)
        }
        rvMovies.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvMovies.smoothScrollToPosition(0)
        }
    }

    private fun preLoadImages(filmes: List<VodStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            val limitPosters = if (filmes.size > 40) 40 else filmes.size
            for (i in 0 until limitPosters) {
                val url = filmes[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@VodActivity).load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .priority(Priority.LOW)
                            .preload(200, 300)
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
        XtreamApi.service.getVodCategories(username, password)
            .enqueue(object : retrofit2.Callback<List<LiveCategory>> {
                override fun onResponse(call: retrofit2.Call<List<LiveCategory>>, response: retrofit2.Response<List<LiveCategory>>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val originais = response.body()!!
                        var categorias = mutableListOf<LiveCategory>()
                        categorias.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                        categorias.addAll(originais)
                        cachedCategories = categorias
                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            categorias = categorias.filterNot { isAdultName(it.name) }.toMutableList()
                        }
                        aplicarCategorias(categorias)
                    }
                }
                override fun onFailure(call: retrofit2.Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
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
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(call: retrofit2.Call<List<VodStream>>, response: retrofit2.Response<List<VodStream>>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var filmes = response.body()!!
                        moviesCache[categoria.id] = filmes
                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            filmes = filmes.filterNot { isAdultName(it.name) || isAdultName(it.title) }
                        }
                        aplicarFilmes(filmes)
                        preLoadImages(filmes)
                    }
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun carregarFilmesFavoritos() {
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        val listaFavoritosInstantanea = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        aplicarFilmes(listaFavoritosInstantanea)
    }

    private fun aplicarFilmes(filmes: List<VodStream>) {
        moviesAdapter = VodAdapter(filmes, { abrirDetalhes(it) }, { mostrarMenuDownload(it) })
        rvMovies.adapter = moviesAdapter
    }

    private fun abrirDetalhes(filme: VodStream) {
        val intent = Intent(this@VodActivity, DetailsActivity::class.java)
        intent.putExtra("stream_id", filme.id)
        intent.putExtra("name", filme.name)
        intent.putExtra("icon", filme.icon)
        intent.putExtra("rating", filme.rating ?: "0.0")
        startActivity(intent)
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val prefsFav = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        return prefsFav.getStringSet("favoritos", emptySet())?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download) {
                Toast.makeText(this, "Baixando: ${filme.name}", Toast.LENGTH_LONG).show()
            }
            true
        }
        popup.show()
    }

    inner class VodCategoryAdapter(private val list: List<LiveCategory>, private val onClick: (LiveCategory) -> Unit) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {
        private var selectedPos = 0
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.tvName.text = item.name
            val isSel = selectedPos == p
            h.tvName.setTextColor(getColor(if (isSel) R.color.red_primary else R.color.gray_text))
            h.tvName.setBackgroundColor(if (isSel) 0xFF252525.toInt() else 0x00000000)
            h.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    h.tvName.setTextColor(Color.YELLOW) // ✅ AMARELO NO FOCO
                    h.tvName.textSize = 20f // ✅ TEXTO MAIOR NA TV
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(150).start()
                } else {
                    h.tvName.textSize = 16f
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    view.setBackgroundResource(0)
                    if (selectedPos != h.adapterPosition) h.tvName.setTextColor(getColor(R.color.gray_text))
                }
            }
            h.itemView.setOnClickListener {
                notifyItemChanged(selectedPos)
                selectedPos = h.adapterPosition
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }
        override fun getItemCount() = list.size
    }

    inner class VodAdapter(private val list: List<VodStream>, private val onClick: (VodStream) -> Unit, private val onDownloadClick: (VodStream) -> Unit) : RecyclerView.Adapter<VodAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
            var job: Job? = null
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_vod, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.job?.cancel()
            h.tvName.text = item.name
            h.tvName.visibility = View.VISIBLE
            h.imgLogo.visibility = View.GONE
            h.imgLogo.setImageDrawable(null)
            Glide.with(h.itemView.context).load(item.icon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .into(h.imgPoster)

            val cachedUrl = gridCachePrefs.getString("logo_${item.name}", null)
            if (cachedUrl != null) {
                h.tvName.visibility = View.GONE
                h.imgLogo.visibility = View.VISIBLE
                Glide.with(h.itemView.context).load(cachedUrl).into(h.imgLogo)
            } else {
                h.job = CoroutineScope(Dispatchers.IO).launch {
                    val url = searchTmdbLogoSilently(item.name)
                    if (url != null) {
                        withContext(Dispatchers.Main) {
                            if (h.adapterPosition == p) {
                                h.tvName.visibility = View.GONE
                                h.imgLogo.visibility = View.VISIBLE
                                Glide.with(h.itemView.context).load(url).into(h.imgLogo)
                            }
                        }
                    }
                }
            }

            h.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    h.tvName.setTextColor(Color.YELLOW) // ✅ AMARELO NO FILME
                    h.tvName.textSize = 18f // ✅ NOME DO FILME MAIOR
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                    view.elevation = 20f
                    if (h.imgLogo.visibility != View.VISIBLE) h.tvName.visibility = View.VISIBLE
                } else {
                    h.tvName.setTextColor(Color.WHITE)
                    h.tvName.textSize = 14f
                    view.setBackgroundResource(0)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    view.elevation = 4f
                    h.tvName.visibility = View.GONE
                }
            }
            h.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size

        private suspend fun searchTmdbLogoSilently(rawName: String): String? {
            val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
            val cleanName = rawName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "").replace(Regex("\\b\\d{4}\\b"), "").trim()
            try {
                val searchJson = URL("https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR").readText()
                val results = JSONObject(searchJson).getJSONArray("results")
                if (results.length() > 0) {
                    val id = results.getJSONObject(0).getString("id")
                    val imgJson = URL("https://api.themoviedb.org/3/movie/$id/images?api_key=$apiKey&include_image_language=pt,en,null").readText()
                    val logos = JSONObject(imgJson).getJSONArray("logos")
                    if (logos.length() > 0) {
                        var logoPath: String? = null
                        for (i in 0 until logos.length()) {
                            val logoObj = logos.getJSONObject(i)
                            if (logoObj.optString("iso_639_1") == "pt") {
                                logoPath = logoObj.getString("file_path")
                                break
                            }
                        }
                        if (logoPath == null) logoPath = logos.getJSONObject(0).getString("file_path")
                        val finalUrl = "https://image.tmdb.org/t/p/w500$logoPath"
                        gridCachePrefs.edit().putString("logo_$rawName", finalUrl).apply()
                        return finalUrl
                    }
                }
            } catch (e: Exception) {}
            return null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
