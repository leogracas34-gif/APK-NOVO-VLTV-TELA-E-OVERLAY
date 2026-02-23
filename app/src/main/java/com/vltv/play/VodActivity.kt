package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.google.android.material.bottomnavigation.BottomNavigationView // ✅ Menu Importado
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

// --- IMPORTAÇÕES PARA SUPORTE AOS 6 DNS ---
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray

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
    private var categoryAdapter: VodCategoryAdapter? = null
    private var moviesAdapter: VodAdapter? = null
    
    private var currentProfile: String = "Padrao"
    private var bottomNavigation: BottomNavigationView? = null // ✅ Variável do Menu

    private val database by lazy { AppDatabase.getDatabase(this) }

    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)

        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        // ✅ CORREÇÃO: Mostra botões no celular
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        if (isTelevision(this)) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        rvCategories = findViewById(R.id.rvCategories)
        rvMovies = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation) // ✅ Vincula o Menu
        gridCachePrefs = getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)

        setupBottomNavigation() // ✅ Ativa cliques do Menu

        val searchInput = findViewById<View>(R.id.etSearchContent)
        searchInput?.isFocusableInTouchMode = false
        searchInput?.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            intent.putExtra("PROFILE_NAME", currentProfile)
            // ✅ NOVA LINHA: Avisa a tela de pesquisa que o usuário está buscando FILMES
            intent.putExtra("tipo_pesquisa", "filmes")
            startActivity(intent)
        }

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        setupRecyclerFocus()

        if (isTelevision(this)) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvMovies.layoutManager = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE // Esconde na TV
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvMovies.layoutManager = GridLayoutManager(this, 3) 
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvMovies.isFocusable = true
        rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(100)

        rvCategories.requestFocus()
        carregarCategorias()
    }
    
    // ✅ LÓGICA DO MENU CORRIGIDA
    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> { startActivity(Intent(this, SearchActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) }); true }
                // ✅ Aponta para a nova tela de Novidades
                R.id.nav_novidades -> { 
                    startActivity(Intent(this, NovidadesActivity::class.java).apply { 
                        putExtra("PROFILE_NAME", currentProfile) 
                    })
                    true 
                }
                R.id.nav_profile -> { startActivity(Intent(this, SettingsActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) }); true }
                else -> false
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        atualizarNotificacaoDownload()
    }

    // ✅ FUNÇÃO ESVAZIADA para não dar o erro do "nav_downloads"
    private fun atualizarNotificacaoDownload() {
        // Função desativada pois o botão de Downloads foi removido do rodapé
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvCategories.smoothScrollToPosition(0) }
        rvMovies.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvMovies.smoothScrollToPosition(0) }
    }

    private fun preLoadImages(filmes: List<VodStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            val limitPosters = if (filmes.size > 40) 40 else filmes.size
            for (i in 0 until limitPosters) {
                val url = filmes[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@VodActivity).asBitmap().load(url).format(DecodeFormat.PREFER_RGB_565).diskCacheStrategy(DiskCacheStrategy.ALL).priority(Priority.LOW).preload(180, 270)
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

    // 🔥 FUNÇÃO ATUALIZADA PARA SUPORTAR TODOS OS 6 DNS (LISTA OU OBJETO)
    private fun carregarCategorias() {
        cachedCategories?.let { aplicarCategorias(it); return }
        progressBar.visibility = View.VISIBLE
        
        XtreamApi.service.getVodCategories(username, password).enqueue(object : retrofit2.Callback<ResponseBody> {
            override fun onResponse(call: retrofit2.Call<ResponseBody>, response: retrofit2.Response<ResponseBody>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val rawJson = response.body()!!.string()
                        val listaCategorias = mutableListOf<LiveCategory>()
                        val gson = Gson()

                        if (rawJson.trim().startsWith("[")) {
                            val listType = object : TypeToken<List<LiveCategory>>() {}.type
                            val list: List<LiveCategory> = gson.fromJson(rawJson, listType)
                            listaCategorias.addAll(list)
                        } else if (rawJson.trim().startsWith("{")) {
                            val jsonObject = JSONObject(rawJson)
                            val keys = jsonObject.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val catJson = jsonObject.getJSONObject(key).toString()
                                val category: LiveCategory = gson.fromJson(catJson, LiveCategory::class.java)
                                listaCategorias.add(category)
                            }
                        }

                        var categorias = mutableListOf<LiveCategory>()
                        categorias.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                        categorias.addAll(listaCategorias)
                        cachedCategories = categorias
                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            categorias = categorias.filterNot { isAdultName(it.name) }.toMutableList()
                        }
                        aplicarCategorias(categorias)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@VodActivity, "Erro ao processar categorias", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) { 
                progressBar.visibility = View.GONE 
                Toast.makeText(this@VodActivity, "Falha na conexão", Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allLocal = database.streamDao().searchVod("") 
                val filtradosLocal = allLocal.filter { it.category_id == categoria.id }
                withContext(Dispatchers.Main) {
                    if (filtradosLocal.isNotEmpty() && moviesCache[categoria.id] == null) {
                        val items = filtradosLocal.map { VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating) }
                        aplicarFilmes(items)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        moviesCache[categoria.id]?.let { aplicarFilmes(it); preLoadImages(it); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id).enqueue(object : retrofit2.Callback<List<VodStream>> {
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val entities = filmes.map { VodEntity(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating, categoria.id, System.currentTimeMillis()) }
                            database.streamDao().insertVodStreams(entities)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
            override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) { progressBar.visibility = View.GONE }
        })
    }

    private fun carregarFilmesFavoritos() {
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        if (favIds.isEmpty()) { aplicarFilmes(emptyList()); return }
        val listaNoCache = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) { aplicarFilmes(listaNoCache); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = "0").enqueue(object : retrofit2.Callback<List<VodStream>> {
            override fun onResponse(call: retrofit2.Call<List<VodStream>>, response: retrofit2.Response<List<VodStream>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val todosOsFilmes = response.body()!!
                    val favoritosFiltrados = todosOsFilmes.filter { favIds.contains(it.id) }
                    moviesCache["ALL_FOR_FAV"] = todosOsFilmes 
                    aplicarFilmes(favoritosFiltrados)
                    preLoadImages(favoritosFiltrados)
                }
            }
            override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) { progressBar.visibility = View.GONE; aplicarFilmes(listaNoCache) }
        })
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
        intent.putExtra("PROFILE_NAME", currentProfile)
        startActivity(intent)
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val prefsFav = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        val key = "${currentProfile}_favoritos"
        return prefsFav.getStringSet(key, emptySet())?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download) Toast.makeText(this, "Baixando: ${filme.name}", Toast.LENGTH_LONG).show()
            true
        }
        popup.show()
    }

    inner class VodCategoryAdapter(private val list: List<LiveCategory>, private val onClick: (LiveCategory) -> Unit) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {
        private var selectedPos = 0
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tvName: TextView = v.findViewById(R.id.tvName) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.tvName.text = item.name
            val isSel = selectedPos == p
            h.tvName.setTextColor(getColor(if (isSel) R.color.red_primary else R.color.gray_text))
            h.tvName.setBackgroundColor(if (isSel) 0xFF252525.toInt() else 0x00000000)
            h.itemView.setOnClickListener { val oldPos = selectedPos; selectedPos = h.adapterPosition; notifyItemChanged(oldPos); notifyItemChanged(selectedPos); onClick(item) }
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
            Glide.with(h.itemView.context).asBitmap().load(item.icon).format(DecodeFormat.PREFER_RGB_565).override(180, 270).diskCacheStrategy(DiskCacheStrategy.ALL).placeholder(R.drawable.bg_logo_placeholder).centerCrop().into(h.imgPoster)

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
                                Glide.with(h.itemView.context).load(url).format(DecodeFormat.PREFER_RGB_565).override(180, 100).into(h.imgLogo)
                            }
                        }
                    }
                }
            }
            h.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size

        private suspend fun searchTmdbLogoSilently(rawName: String): String? {
            val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
            val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
            val year = yearRegex.find(rawName)?.value
            var cleanName = rawName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "").replace(yearRegex, "").trim()
            try {
                var searchUrl = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR&include_adult=false"
                if (year != null) searchUrl += "&year=$year"
                val results = JSONObject(URL(searchUrl).readText()).getJSONArray("results")
                if (results.length() > 0) {
                    val id = results.getJSONObject(0).getString("id")
                    val logos = JSONObject(URL("https://api.themoviedb.org/3/movie/$id/images?api_key=$apiKey&include_image_language=pt,en,null").readText()).getJSONArray("logos")
                    if (logos.length() > 0) {
                        var logoPath: String? = null
                        for (i in 0 until logos.length()) { if (logos.getJSONObject(i).optString("iso_639_1") == "pt") { logoPath = logos.getJSONObject(i).getString("file_path"); break } }
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
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
