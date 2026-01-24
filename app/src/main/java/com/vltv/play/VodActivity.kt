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

// --- IMPORTAÇÕES ADICIONADAS PARA A BUSCA DE LOGO ---
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

class VodActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView

    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences

    // Cache em memória
    private var cachedCategories: List<LiveCategory>? = null
    private val moviesCache = mutableMapOf<String, List<VodStream>>() // key = categoryId
    private var favMoviesCache: List<VodStream>? = null

    private var categoryAdapter: VodCategoryAdapter? = null
    private var moviesAdapter: VodAdapter? = null

    // Função para checar se é TV Box ou Celular
    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == 
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- AQUI ESTÁ A MÁGICA ---
        // Agora usamos o molde SEM PLAYER. A lista vai ocupar a tela toda.
        setContentView(R.layout.activity_vod) 
        // --------------------------

        val windowInsetsController = WindowCompat.getInsetsController(window,
        window.decorView)
        windowInsetsController?.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvMovies = findViewById(R.id.rvChannels) // O ID continua o mesmo no XML novo
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        // ✅ FOCO TV + D-PAD PERFEITO
        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvMovies.layoutManager = GridLayoutManager(this, 5)
        rvMovies.isFocusable = true
        rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvMovies.setHasFixedSize(true)

        // ✅ Foco inicial categorias TV
        rvCategories.requestFocus()

        carregarCategorias()
    }

    // ✅ MELHOR NAVEGAÇÃO ENTRE RECYCLERVIEWS (TV)
    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rvCategories.smoothScrollToPosition(0)
            }
        }
        
        rvMovies.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rvMovies.smoothScrollToPosition(0)
            }
        }
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
        // Usa cache se já tiver
        cachedCategories?.let { categoriasCacheadas ->
            aplicarCategorias(categoriasCacheadas)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getVodCategories(username, password)
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
                                category_id = "FAV",
                                category_name = "FAVORITOS"
                            )
                        )
                        categorias.addAll(originais)

                        // cache bruto
                        cachedCategories = categorias

                        // se controle parental ligado, remove categorias adultas
                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            categorias = categorias.filterNot { cat ->
                                isAdultName(cat.name)
                            }.toMutableList()
                        }

                        aplicarCategorias(categorias)
                    } else {
                        Toast.makeText(
                            this@VodActivity,
                            "Erro ao carregar categorias",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@VodActivity,
                        "Falha de conexão",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) {
            Toast.makeText(this, "Nenhuma categoria disponível.", Toast.LENGTH_SHORT).show()
            rvCategories.adapter = VodCategoryAdapter(emptyList()) {}
            rvMovies.adapter = VodAdapter(emptyList(), {}, {})
            return
        }

        categoryAdapter = VodCategoryAdapter(categorias) { categoria ->
            if (categoria.id == "FAV") {
                carregarFilmesFavoritos()
            } else {
                carregarFilmes(categoria)
            }
        }
        rvCategories.adapter = categoryAdapter

        val primeiraCategoriaNormal = categorias.firstOrNull { it.id != "FAV" }
        if (primeiraCategoriaNormal != null) {
            carregarFilmes(primeiraCategoriaNormal)
        } else {
            tvCategoryTitle.text = "FAVORITOS"
            carregarFilmesFavoritos()
        }
    }

    private fun carregarFilmes(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name

        // cache por categoria
        moviesCache[categoria.id]?.let { filmesCacheados ->
            aplicarFilmes(filmesCacheados)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<VodStream>> {
                override fun onResponse(
                    call: Call<List<VodStream>>,
                    response: Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var filmes = response.body()!!

                        moviesCache[categoria.id] = filmes

                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            filmes = filmes.filterNot { vod ->
                                isAdultName(vod.name) || isAdultName(vod.title)
                            }
                        }

                        aplicarFilmes(filmes)
                    }
                }

                override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun carregarFilmesFavoritos() {
        tvCategoryTitle.text = "FAVORITOS"

        // usa cache se já montou uma vez
        favMoviesCache?.let { favoritosCacheados ->
            aplicarFilmes(favoritosCacheados)
            return
        }

        progressBar.visibility = View.VISIBLE

        val favIds = getFavMovies(this)
        if (favIds.isEmpty()) {
            progressBar.visibility = View.GONE
            aplicarFilmes(emptyList())
            Toast.makeText(this, "Nenhum filme favorito.", Toast.LENGTH_SHORT).show()
            return
        }

        XtreamApi.service.getVodStreams(username, password, categoryId = "0")
            .enqueue(object : Callback<List<VodStream>> {
                override fun onResponse(
                    call: Call<List<VodStream>>,
                    response: Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var todos = response.body()!!
                        todos = todos.filter { favIds.contains(it.id) }

                        if (ParentalControlManager.isEnabled(this@VodActivity)) {
                            todos = todos.filterNot { vod ->
                                isAdultName(vod.name) || isAdultName(vod.title)
                            }
                        }

                        favMoviesCache = todos
                        aplicarFilmes(todos)
                    }
                }

                override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun aplicarFilmes(filmes: List<VodStream>) {
        moviesAdapter = VodAdapter(
            filmes,
            onClick = { filme -> abrirDetalhes(filme) },
            onDownloadClick = { filme -> mostrarMenuDownload(filme) }
        )
        rvMovies.adapter = moviesAdapter
    }

    private fun abrirDetalhes(filme: VodStream) {
        val intent = Intent(this@VodActivity, DetailsActivity::class.java)
        intent.putExtra("stream_id", filme.id)
        intent.putExtra("stream_ext", filme.extension ?: "mp4")
        intent.putExtra("name", filme.name)
        intent.putExtra("icon", filme.icon)
        intent.putExtra("rating", filme.rating ?: "0.0")
        intent.putExtra("channel_name", filme.name)
        startActivity(intent)
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val set = prefs.getStringSet("fav_movies", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    // ================= MENU DOWNLOAD =================

    private fun mostrarMenuDownload(filme: VodStream) {
        val anchor = findViewById<View>(android.R.id.content)
        val popup = PopupMenu(this, anchor)
        menuInflater.inflate(R.menu.menu_download, popup.menu)

        val downloadId = filme.id
        val estaBaixando = prefs.getBoolean("downloading_$downloadId", false)

        popup.menu.findItem(R.id.action_download).isVisible = !estaBaixando
        popup.menu.findItem(R.id.action_pause).isVisible = estaBaixando
        popup.menu.findItem(R.id.action_cancel).isVisible = estaBaixando

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_download -> {
                    iniciarDownloadReal(filme)
                    true
                }

                R.id.action_pause -> {
                    pausarDownload(filme.id)
                    true
                }

                R.id.action_cancel -> {
                    cancelarDownload(filme.id)
                    true
                }

                R.id.action_meus_downloads -> {
                    abrirDownloadsPremium(filme)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun iniciarDownloadReal(filme: VodStream) {
        val dns = prefs.getString("dns", "") ?: ""
        val base = if (dns.endsWith("/")) dns else "$dns/"
        val url = "${base}movie/$username/$password/${filme.id}.${filme.extension ?: "mp4"}"

        prefs.edit()
            .putBoolean("downloading_${filme.id}", true)
            .apply()

        Toast.makeText(this, "Baixando: ${filme.name}", Toast.LENGTH_LONG).show()
        // aqui depois entra DownloadManager ou ExoPlayer offline usando 'url'
    }

    private fun pausarDownload(streamId: Int) {
        prefs.edit().putBoolean("downloading_$streamId", false).apply()
        Toast.makeText(this, "Download pausado", Toast.LENGTH_SHORT).show()
    }

    private fun cancelarDownload(streamId: Int) {
        prefs.edit().remove("downloading_$streamId").apply()
        Toast.makeText(this, "Download cancelado", Toast.LENGTH_SHORT).show()
    }

    private fun abrirDownloadsPremium(filme: VodStream) {
        Toast.makeText(
            this,
            "Meus downloads (premium) – ${filme.name}",
            Toast.LENGTH_LONG
        ).show()
    }

    // ================= ADAPTERS =================

    inner class VodCategoryAdapter(
        private val list: List<LiveCategory>,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {

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

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary))
                    holder.tvName.setBackgroundColor(0xFF252525.toInt())
                } else {
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

    // ================= ADAPTER TURBINADO COM LOGO TMDB =================
    inner class VodAdapter(
        private val list: List<VodStream>,
        private val onClick: (VodStream) -> Unit,
        private val onDownloadClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<VodAdapter.VH>() {

        private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
            val imgDownload: ImageView = v.findViewById(R.id.imgDownload)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vod, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            
            // 1. Esconde o texto feio imediatamente
            holder.tvName.visibility = View.GONE
            holder.imgLogo.setImageDrawable(null) // Limpa logo antiga (reciclagem)
            holder.imgLogo.visibility = View.INVISIBLE

            // 2. Carrega o Pôster do IPTV (Fundo)
            val context = holder.itemView.context
            if (isTelevision(context)) {
                // Modo TV: Usa thumbnail para economizar memória e não travar
                Glide.with(context)
                    .load(item.icon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(200, 300)
                    .priority(Priority.HIGH)
                    .thumbnail(0.1f)
                    .placeholder(R.drawable.bg_logo_placeholder)
                    .error(R.drawable.bg_logo_placeholder)
                    .centerCrop()
                    .into(holder.imgPoster)
            } else {
                // Modo Celular: Qualidade total
                Glide.with(context)
                    .load(item.icon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .priority(Priority.IMMEDIATE)
                    .placeholder(R.drawable.bg_logo_placeholder)
                    .error(R.drawable.bg_logo_placeholder)
                    .centerCrop()
                    .into(holder.imgPoster)
            }

            // 3. BUSCA LOGO NO TMDB (Estilo Disney)
            searchTmdbLogo(item.name, holder.imgLogo)

            // 4. Animações de Foco (Zoom e Controle)
            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    view.elevation = 10f
                    // Se não tiver logo, mostra o texto quando foca, para o usuário saber o nome
                    if (holder.imgLogo.drawable == null) holder.tvName.visibility = View.VISIBLE
                    holder.tvName.setTextColor(0xFF00C6FF.toInt()) // Azul Neon
                    view.alpha = 1.0f
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    view.elevation = 4f
                    holder.tvName.visibility = View.GONE
                    holder.tvName.setTextColor(0xFFFFFFFF.toInt())
                    view.alpha = 0.8f
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
            holder.imgDownload.setOnClickListener { onDownloadClick(item) }
        }

        override fun getItemCount() = list.size

        // --- FUNÇÃO DE BUSCA COM LIMPEZA SUPER PODEROSA ---
        private fun searchTmdbLogo(rawName: String, targetView: ImageView) {
            
            // 1. A VASSOURA (Limpeza do Nome)
            var cleanName = rawName

            // Passo A: Remove tudo que estiver entre parenteses (), colchetes [] ou chaves {}
            // Ex: "Filme (2019)" ou "[FHD] Filme" vira "Filme"
            cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")

            // Passo B: Remove anos soltos (4 números seguidos, ex: "2019" no inicio ou fim)
            // Remove se for "2019 " ou " 2019"
            cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")

            // Passo C: Remove sujeiras comuns de IPTV
            val sujeiras = listOf(
                "FHD", "HD", "SD", "4K", "8K", "H265", "H.265",
                "LEG", "DUBLADO", "DUB", "LEGENDADO", "DUAL", 
                "|", "-", "_", ".", "MKV", "MP4", "AVI"
            )
            
            sujeiras.forEach { lixo ->
                cleanName = cleanName.replace(lixo, "", ignoreCase = true)
            }

            // Passo D: Remove espaços duplos e laterais que sobraram
            cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

            // Se o nome ficou vazio (ex: só tinha lixo), usa o original
            if (cleanName.length < 2) cleanName = rawName

            // 2. Dispara a busca no TMDB com o nome limpo
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val query = URLEncoder.encode(cleanName, "UTF-8")
                    
                    // Busca ID
                    val searchUrl = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$query&language=pt-BR"
                    val searchJson = URL(searchUrl).readText()
                    val searchObj = JSONObject(searchJson)
                    val results = searchObj.getJSONArray("results")

                    if (results.length() > 0) {
                        val movieId = results.getJSONObject(0).getString("id")
                        
                        // Busca LOGO (Pede 'en' e 'pt' pois logos PNG geralmente são classificadas como 'en')
                        val imagesUrl = "https://api.themoviedb.org/3/movie/$movieId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                        val imagesJson = URL(imagesUrl).readText()
                        val imagesObj = JSONObject(imagesJson)
                        
                        if (imagesObj.has("logos")) {
                            val logos = imagesObj.getJSONArray("logos")
                            if (logos.length() > 0) {
                                val logoPath = logos.getJSONObject(0).getString("file_path")
                                val fullLogoUrl = "https://image.tmdb.org/t/p/w500$logoPath"

                                withContext(Dispatchers.Main) {
                                    targetView.visibility = View.VISIBLE
                                    Glide.with(targetView.context)
                                        .load(fullLogoUrl)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .fitCenter()
                                        .into(targetView)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Se der erro, não faz nada (o texto aparecerá no foco como backup)
                }
            }
        }
    }

    // ✅ BACK = SAIR (TV + Celular)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
