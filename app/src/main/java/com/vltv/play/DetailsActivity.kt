package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import com.vltv.play.CastMember
import com.vltv.play.CastAdapter
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.* // ✅ Importante para o monitoramento

// ✅ MANTIDO: EpisodeData
data class EpisodeData(
    val streamId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String,
    val videoKey: String? = null
)

class DetailsActivity : AppCompatActivity() {
    private var streamId: Int = 0
    private var name: String = ""
    private var icon: String? = null
    private var rating: String = "0.0"
    private var isSeries: Boolean = false
    private var episodes: List<EpisodeData> = emptyList()
    private var hasResumePosition: Boolean = false
    
    private var serverYoutubeTrailer: String? = null
    private var currentProfile: String = "Padrao"

    private lateinit var imgPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnResume: Button
    private lateinit var btnFavorite: ImageButton
    private var btnDownloadArea: LinearLayout? = null
    private lateinit var imgDownloadState: ImageView
    private lateinit var tvDownloadState: TextView
    private lateinit var imgBackground: ImageView
    private lateinit var tvEpisodesTitle: TextView
    private lateinit var recyclerEpisodes: RecyclerView
    private var tvYear: TextView? = null
    private var btnSettings: Button? = null
    private lateinit var episodesAdapter: EpisodesAdapter
    
    private var btnDownloadAction: LinearLayout? = null
    private var btnFavoriteLayout: LinearLayout? = null
    private var btnTrailerAction: LinearLayout? = null
    private var btnRestartAction: LinearLayout? = null
    private var layoutProgress: LinearLayout? = null
    private var progressBarMovie: ProgressBar? = null
    private var tvTimeRemaining: TextView? = null
    private lateinit var tabLayoutDetails: TabLayout
    private lateinit var recyclerSuggestions: RecyclerView
    
    private var bottomNavigation: BottomNavigationView? = null
    
    private var layoutTabDetails: LinearLayout? = null
    private var tvDetailFullTitle: TextView? = null
    private var tvDetailFullPlot: TextView? = null
    private var tvDetailDuration: TextView? = null
    private var tvDetailReleaseDate: TextView? = null
    private var tvDetailGenre: TextView? = null
    private var tvDetailDirector: TextView? = null

    private var pbDownloadCircular: ProgressBar? = null

    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    // ✅ NOVO: Controle do Monitoramento em Tempo Real
    private var uiMonitorJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_details)
            configurarTelaTV()
            
            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"
            streamId = intent.getIntExtra("stream_id", 0)
            name = intent.getStringExtra("name") ?: ""
            icon = intent.getStringExtra("icon")
            rating = intent.getStringExtra("rating") ?: "0.0"
            isSeries = intent.getBooleanExtra("is_series", false)
            
            serverYoutubeTrailer = intent.getStringExtra("youtube_trailer")
            if (serverYoutubeTrailer == "null" || serverYoutubeTrailer?.isEmpty() == true) {
                serverYoutubeTrailer = null
            }
            
            inicializarViews()
            setupBottomNavigation()
            carregarConteudo()
            setupEventos()
            setupEpisodesRecycler()
            tentarCarregarTextoCache()
            tentarCarregarLogoCache()
            sincronizarDadosTMDB()
        } catch (e: Exception) {
            Log.e("VLTV_DEBUG", "Erro no onCreate: ${e.message}")
            Toast.makeText(this, "Erro Crítico: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun configurarTelaTV() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        if (isTelevisionDevice()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars()) 
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun inicializarViews() {
        try {
            imgPoster = findViewById(R.id.imgPoster)
            tvTitle = findViewById(R.id.tvTitle)
            imgTitleLogo = findViewById(R.id.imgTitleLogo)
            tvRating = findViewById(R.id.tvRating)
            tvGenre = findViewById(R.id.tvGenre)
            tvCast = findViewById(R.id.tvCast)
            tvPlot = findViewById(R.id.tvPlot)
            btnPlay = findViewById(R.id.btnPlay)
            btnResume = findViewById(R.id.btnResume)
            btnFavorite = findViewById(R.id.btnFavorite)
            btnDownloadArea = findViewById(R.id.btnDownloadArea)
            imgDownloadState = findViewById(R.id.imgDownloadState)
            tvDownloadState = findViewById(R.id.tvDownloadState)
            imgBackground = findViewById(R.id.imgBackground)
            tvEpisodesTitle = findViewById(R.id.tvEpisodesTitle)
            recyclerEpisodes = findViewById(R.id.recyclerEpisodes)
            tvYear = findViewById(R.id.tvYear)
            btnSettings = findViewById(R.id.btnSettings)

            btnDownloadAction = findViewById(R.id.btnDownloadAction)
            btnFavoriteLayout = findViewById(R.id.btnFavoriteLayout)
            btnTrailerAction = findViewById(R.id.btnTrailerAction)
            
            btnRestartAction = findViewById(R.id.btnRestartAction)
            layoutProgress = findViewById(R.id.layoutProgress)
            progressBarMovie = findViewById(R.id.progressBarMovie)
            tvTimeRemaining = findViewById(R.id.tvTimeRemaining)
            tabLayoutDetails = findViewById(R.id.tabLayoutDetails)
            recyclerSuggestions = findViewById(R.id.recyclerSuggestions)
            
            bottomNavigation = findViewById(R.id.bottomNavigation)
            
            layoutTabDetails = findViewById(R.id.layoutTabDetails)
            tvDetailFullTitle = findViewById(R.id.tvDetailFullTitle)
            tvDetailFullPlot = findViewById(R.id.tvDetailFullPlot)
            tvDetailDuration = findViewById(R.id.tvDetailDuration)
            tvDetailReleaseDate = findViewById(R.id.tvDetailReleaseDate)
            tvDetailGenre = findViewById(R.id.tvDetailGenre)
            tvDetailDirector = findViewById(R.id.tvDetailDirector)
            
            if (btnDownloadAction != null) {
                pbDownloadCircular = ProgressBar(this)
                val params = LinearLayout.LayoutParams(24.toPx(), 24.toPx())
                params.setMargins(0,0,0,5)
                pbDownloadCircular?.layoutParams = params
                pbDownloadCircular?.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                pbDownloadCircular?.visibility = View.GONE
                btnDownloadAction?.addView(pbDownloadCircular, 0)
            }

            // ✅ Oculta os botões de download globalmente
            btnDownloadArea?.visibility = View.GONE
            btnDownloadAction?.visibility = View.GONE

            if (isTelevisionDevice()) {
                bottomNavigation?.visibility = View.GONE
            }
            
            btnPlay.isFocusable = true
            btnPlay.requestFocus()
        } catch (e: Exception) {
            Log.e("VLTV_DEBUG", "Erro ao inicializar views: ${e.message}")
            throw e
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    true
                }
                R.id.nav_novidades -> { 
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    true 
                }
                else -> false
            }
        }
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun carregarConteudo() {
        tvRating.text = "⭐ $rating"
        tvPlot.text = "Buscando detalhes..."
        tvGenre.text = "Gênero: ..."
        tvCast.text = "Elenco:"
        Glide.with(this).load(icon).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgPoster)
        Glide.with(this).load(icon).centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL).into(imgBackground)
        
        val isFavInicial = getFavMovies(this).contains(streamId)
        atualizarIconeFavorito(isFavInicial)
        
        if (isSeries) {
            carregarEpisodios() 
        } else {
            tvEpisodesTitle.visibility = View.GONE
            if (serverYoutubeTrailer != null) {
                val extrasList = listOf(EpisodeData(0, 0, 1, "Trailer Oficial", "https://img.youtube.com/vi/$serverYoutubeTrailer/0.jpg", serverYoutubeTrailer))
                episodesAdapter.submitList(extrasList)
                if (tabLayoutDetails.selectedTabPosition == 1) {
                    recyclerEpisodes.visibility = View.VISIBLE
                } else {
                    recyclerEpisodes.visibility = View.GONE
                }
            }
        }
        verificarResume()
        restaurarEstadoDownload()
    }

    // ✅ FUNÇÃO NOVA: Monitora o download em tempo real (Corrige o 0% travado)
    private fun iniciarMonitoramentoUI() {
        if (uiMonitorJob?.isActive == true) return
        
        uiMonitorJob = CoroutineScope(Dispatchers.Main).launch {
            val db = AppDatabase.getDatabase(applicationContext).streamDao()
            val tipo = if (isSeries) "series" else "movie"
            
            while (isActive) {
                val download = withContext(Dispatchers.IO) {
                    db.getDownloadByStreamId(streamId, tipo)
                }

                if (download != null) {
                    if (download.status == "BAIXADO" || download.status == "COMPLETED") {
                        downloadState = DownloadState.BAIXADO
                        atualizarUI_download()
                        this.cancel() // Para se acabou
                    } else if (download.status == "BAIXANDO" || download.status == "RUNNING") {
                        downloadState = DownloadState.BAIXANDO
                        
                        // Atualiza visualmente a porcentagem
                        tvDownloadState.text = "${download.progress}%"
                        imgDownloadState.visibility = View.GONE
                        pbDownloadCircular?.visibility = View.VISIBLE
                    } else if (download.status == "ERRO") {
                        tvDownloadState.text = "ERRO"
                        imgDownloadState.visibility = View.VISIBLE
                        pbDownloadCircular?.visibility = View.GONE
                        this.cancel()
                    }
                }
                delay(1000) // Atualiza a cada 1 segundo
            }
        }
    }

    private fun tentarCarregarTextoCache() {
        val prefs = getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE)
        prefs.getString("title_$streamId", null)?.let { tvTitle.text = it }
        prefs.getString("plot_$streamId", null)?.let { tvPlot.text = it }
        prefs.getString("cast_$streamId", null)?.let { tvCast.text = it }
        prefs.getString("genre_$streamId", null)?.let { tvGenre.text = it }
        prefs.getString("year_$streamId", null)?.let { tvYear?.text = it }
    }

    private fun tentarCarregarLogoCache() {
        val prefs = getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
        val cachedUrl = prefs.getString("movie_logo_$streamId", null)
        if (cachedUrl != null) {
            tvTitle.visibility = View.GONE
            imgTitleLogo.visibility = View.VISIBLE
            Glide.with(this).load(cachedUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
        } else {
            tvTitle.visibility = View.VISIBLE
        }
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        val type = if (isSeries) "tv" else "movie"
        var clean = name.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "").replace(Regex("\\b\\d{4}\\b"), "").trim()
        val lixo = listOf("FHD", "HD", "4K", "H265", "LEG", "DUBLADO")
        lixo.forEach { clean = clean.replace(it, "", ignoreCase = true) }
        val encoded = URLEncoder.encode(clean.replace(Regex("\\s+"), " "), "UTF-8")
        val urlSearch = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&query=$encoded&language=pt-BR&region=BR"
        client.newCall(Request.Builder().url(urlSearch).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val selected = results.getJSONObject(0)
                        val idTmdb = selected.getInt("id")
                        buscarLogoTMDB(idTmdb, type, apiKey)
                        buscarDetalhesCompletos(idTmdb, type, apiKey)
                        runOnUiThread {
                            val tOficial = if (type == "movie") selected.getString("title") else selected.getString("name")
                            val sinopse = selected.optString("overview")
                            val date = if (isSeries) selected.optString("first_air_date") else selected.optString("release_date")
                            tvTitle.text = tOficial
                            if (sinopse.isNotEmpty()) tvPlot.text = sinopse
                            if (date.length >= 4) tvYear?.text = date.substring(0, 4)
                            
                            tvDetailFullTitle?.text = tOficial
                            tvDetailFullPlot?.text = sinopse
                            tvDetailReleaseDate?.text = date
                            
                            getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit()
                                .putString("title_$streamId", tOficial).putString("plot_$streamId", sinopse)
                                .putString("year_$streamId", if (date.length >= 4) date.substring(0, 4) else "").apply()
                        }
                    } else {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE }
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvTitle.visibility = View.VISIBLE }
                }
            }
        })
    }

    private fun buscarLogoTMDB(id: Int, type: String, key: String) {
        val imagesUrl = "https://api.themoviedb.org/3/$type/$id/images?api_key=$key&include_image_language=pt,en,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val logos = JSONObject(body).optJSONArray("logos")
                    if (logos != null && logos.length() > 0) {
                        var finalPath = ""
                        for (i in 0 until logos.length()) {
                            val lg = logos.getJSONObject(i)
                            if (lg.optString("iso_639_1") == "pt") {
                                finalPath = lg.getString("file_path")
                                break
                            }
                        }
                        if (finalPath.isEmpty()) {
                            finalPath = logos.getJSONObject(0).getString("file_path")
                        }
                        val finalUrl = "https://image.tmdb.org/t/p/w500$finalPath"
                        getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit().putString("movie_logo_$streamId", finalUrl).apply()
                        runOnUiThread {
                            tvTitle.visibility = View.GONE
                            imgTitleLogo.visibility = View.VISIBLE
                            Glide.with(this@DetailsActivity).load(finalUrl).into(imgTitleLogo)
                        }
                    }
                } catch (e: Exception) {}
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun buscarDetalhesCompletos(id: Int, type: String, key: String) {
        val detailsUrl = "https://api.themoviedb.org/3/$type/$id?api_key=$key&append_to_response=credits,recommendations,similar,videos&include_video_language=pt,en&language=pt-BR"
        client.newCall(Request.Builder().url(detailsUrl).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val d = JSONObject(body)
                    
                    val gs = d.optJSONArray("genres")
                    val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))
                    
                    val castArray = d.optJSONObject("credits")?.optJSONArray("cast")
                    val castNamesList = mutableListOf<String>()
                    if (castArray != null) {
                        val limit = if (castArray.length() > 10) 10 else castArray.length()
                        for (i in 0 until limit) castNamesList.add(castArray.getJSONObject(i).getString("name"))
                    }

                    val runtime = d.optInt("runtime", 0)
                    val durText = if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else "N/A"

                    val crew = d.optJSONObject("credits")?.optJSONArray("crew")
                    var directorName = "Desconhecido"
                    if (crew != null) {
                        for (i in 0 until crew.length()) {
                            if (crew.getJSONObject(i).optString("job") == "Director") {
                                directorName = crew.getJSONObject(i).getString("name")
                                break
                            }
                        }
                    }

                    var similarArr = d.optJSONObject("recommendations")?.optJSONArray("results")
                    if (similarArr == null || similarArr.length() == 0) {
                        similarArr = d.optJSONObject("similar")?.optJSONArray("results")
                    }
                    
                    val suggestions = mutableListOf<JSONObject>()
                    if (similarArr != null) {
                        for (i in 0 until similarArr.length()) suggestions.add(similarArr.getJSONObject(i))
                    }

                    val extrasList = mutableListOf<EpisodeData>()
                    if (serverYoutubeTrailer == null) {
                        val videosArr = d.optJSONObject("videos")?.optJSONArray("results")
                        if (videosArr != null) {
                            for (i in 0 until videosArr.length()) {
                                val v = videosArr.getJSONObject(i)
                                if (v.optString("site") == "YouTube") {
                                    extrasList.add(EpisodeData(i, 0, i+1, v.getString("name"), "https://img.youtube.com/vi/${v.getString("key")}/0.jpg", v.getString("key")))
                                }
                            }
                        }
                    }

                    runOnUiThread {
                        val g = genresList.joinToString(", ")
                        val e = castNamesList.joinToString("\n")
                        tvGenre.text = "Gênero: $g"
                        tvCast.text = "Elenco: ${castNamesList.take(5).joinToString(", ")}"
                        
                        tvDetailGenre?.text = g
                        tvDetailDuration?.text = durText
                        tvDetailDirector?.text = directorName
                        findViewById<TextView>(R.id.tvCast)?.text = e
                        
                        if (suggestions.isNotEmpty()) {
                            recyclerSuggestions.adapter = SuggestionsAdapter(suggestions)
                        }

                        if (!isSeries && serverYoutubeTrailer == null && extrasList.isNotEmpty()) {
                            episodes = extrasList
                            episodesAdapter.submitList(extrasList)
                            if (tabLayoutDetails.selectedTabPosition == 1) {
                                recyclerEpisodes.visibility = View.VISIBLE
                            } else {
                                recyclerEpisodes.visibility = View.GONE
                            }
                        }

                        getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit()
                            .putString("genre_$streamId", g).putString("cast_$streamId", e).apply()
                    }
                } catch (e: Exception) {}
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun setupEpisodesRecycler() {
        episodesAdapter = EpisodesAdapter { episode ->
            if (episode.videoKey != null) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:${episode.videoKey}"))
                startActivity(intent)
            } else {
                val intent = Intent(this, PlayerActivity::class.java)
                intent.putExtra("stream_id", episode.streamId).putExtra("stream_type", "series")
                intent.putExtra("channel_name", "${name} - S${episode.season}:E${episode.episode}")
                intent.putExtra("icon", episode.thumb)
                intent.putExtra("PROFILE_NAME", currentProfile)
                startActivity(intent)
            }
        }
        recyclerEpisodes.apply {
            layoutManager = if (isTelevisionDevice()) GridLayoutManager(this@DetailsActivity, 6) else LinearLayoutManager(this@DetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = episodesAdapter
            setHasFixedSize(true)
        }
        recyclerSuggestions.layoutManager = GridLayoutManager(this, 3)
    }

    private fun carregarEpisodios() {
        episodes = listOf(EpisodeData(101, 1, 1, "Episódio 1", icon ?: ""))
        episodesAdapter.submitList(episodes)
        tvEpisodesTitle.visibility = View.VISIBLE
        if (tabLayoutDetails.selectedTabPosition == 1) {
            recyclerEpisodes.visibility = View.VISIBLE
        } else {
            recyclerEpisodes.visibility = View.GONE
        }
    }

    private fun setupEventos() {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                if (v is Button) v.setTextColor(android.graphics.Color.YELLOW)
                v.animate().scaleX(1.10f).scaleY(1.10f).setDuration(150).start()
            } else {
                if (v is Button) {
                    v.setBackgroundResource(R.drawable.bg_button_default)
                    v.setTextColor(android.graphics.Color.WHITE)
                } else {
                    v.setBackgroundResource(0)
                }
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlay.onFocusChangeListener = focusListener
        btnFavorite.onFocusChangeListener = focusListener

        btnFavorite.setOnClickListener { toggleFavorite() }
        btnFavoriteLayout?.setOnClickListener { toggleFavorite() }
        btnPlay.setOnClickListener { abrirPlayer(true) }
        
        btnRestartAction?.setOnClickListener { 
            AlertDialog.Builder(this)
                .setTitle("Reiniciar Filme")
                .setMessage("Deseja assistir desde o início?")
                .setPositiveButton("Sim") { _, _ -> abrirPlayer(false) }
                .setNegativeButton("Não", null)
                .show()
        }

        btnDownloadAction?.setOnClickListener { handleDownloadClick() }
        btnTrailerAction?.setOnClickListener { 
            if (serverYoutubeTrailer != null) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$serverYoutubeTrailer"))
                startActivity(intent)
            } else {
                if (tabLayoutDetails.selectedTabPosition != 1) {
                    tabLayoutDetails.getTabAt(1)?.select()
                }
                Toast.makeText(this, "Trailer disponível na aba Extras", Toast.LENGTH_SHORT).show()
            }
        }

        tabLayoutDetails.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // SUGESTÕES
                        recyclerSuggestions.visibility = View.VISIBLE
                        recyclerEpisodes.visibility = View.GONE
                        layoutTabDetails?.visibility = View.GONE
                    }
                    1 -> { // EXTRAS
                        recyclerSuggestions.visibility = View.GONE
                        recyclerEpisodes.visibility = View.VISIBLE
                        layoutTabDetails?.visibility = View.GONE
                    }
                    2 -> { // DETALHES
                        recyclerSuggestions.visibility = View.GONE
                        recyclerEpisodes.visibility = View.GONE
                        layoutTabDetails?.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnSettings?.setOnClickListener { mostrarConfiguracoes() }
    }

    private fun getFavMovies(context: Context): MutableList<Int> {
        val prefs = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        val key = "${currentProfile}_favoritos"
        return prefs.getStringSet(key, emptySet())?.mapNotNull { it.toIntOrNull() }?.toMutableList() ?: mutableListOf()
    }

    private fun saveFavMovies(context: Context, favs: List<Int>) {
        val prefs = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        val key = "${currentProfile}_favoritos"
        prefs.edit().putStringSet(key, favs.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavorito(isFavorite: Boolean) {
        if (isFavorite) {
            btnFavorite.setImageResource(android.R.drawable.btn_star_big_on)
            btnFavorite.setColorFilter(android.graphics.Color.parseColor("#FFD700"))
        } else {
            btnFavorite.setImageResource(android.R.drawable.btn_star_big_off)
            btnFavorite.clearColorFilter()
        }
    }

    private fun toggleFavorite() {
        val favs = getFavMovies(this)
        val isFav = favs.contains(streamId)
        
        atualizarIconeFavorito(!isFav)

        if (isFav) favs.remove(streamId) else favs.add(streamId)
        saveFavMovies(this, favs)
    }

    private fun verificarResume() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val keyPos = "${currentProfile}_movie_resume_${streamId}_pos"
        val keyDur = "${currentProfile}_movie_resume_${streamId}_dur"
        
        val pos = prefs.getLong(keyPos, 0L)
        val totalDur = prefs.getLong(keyDur, 0L)

        if (pos > 30000L && totalDur > 0) {
            btnPlay.text = "▶  CONTINUAR"
            btnRestartAction?.visibility = View.VISIBLE
            layoutProgress?.visibility = View.VISIBLE
            
            val progressPercent = ((pos.toFloat() / totalDur.toFloat()) * 100).toInt()
            progressBarMovie?.progress = progressPercent
            
            val restMs = totalDur - pos
            val hours = TimeUnit.MILLISECONDS.toHours(restMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(restMs) % 60
            tvTimeRemaining?.text = "Restam ${hours}h${minutes}min"
        } else {
            btnPlay.text = "▶  ASSISTIR"
            btnRestartAction?.visibility = View.GONE
            layoutProgress?.visibility = View.GONE
        }
    }

    private fun abrirPlayer(usarResume: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId).putExtra("stream_type", if (isSeries) "series" else "movie").putExtra("channel_name", name)
        intent.putExtra("icon", icon)
        intent.putExtra("PROFILE_NAME", currentProfile)
        
        if (usarResume) {
            val key = "${currentProfile}_movie_resume_${streamId}_pos"
            val pos = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getLong(key, 0L)
            intent.putExtra("start_position_ms", pos)
        } else {
            intent.putExtra("start_position_ms", 0L)
        }
        startActivity(intent)
    }

    private fun restaurarEstadoDownload() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(applicationContext).streamDao()
            val download = db.getDownloadByStreamId(streamId, if (isSeries) "series" else "movie")
            withContext(Dispatchers.Main) {
                if (download != null) {
                    downloadState = if (download.status == "COMPLETED" || download.status == "BAIXADO") DownloadState.BAIXADO else DownloadState.BAIXANDO
                    // ✅ Se estiver baixando, liga o monitor para atualizar o %
                    if (downloadState == DownloadState.BAIXANDO) {
                        iniciarMonitoramentoUI()
                    }
                } else {
                    downloadState = DownloadState.BAIXAR
                }
                atualizarUI_download()
            }
        }
    }

    private fun iniciarDownload() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val url = "${prefs.getString("dns", "")}/movie/${prefs.getString("username", "")}/${prefs.getString("password", "")}/$streamId.mp4"
        
        DownloadHelper.iniciarDownload(this, url, streamId, name, null, icon, false)
        
        downloadState = DownloadState.BAIXANDO
        atualizarUI_download()
        
        val currentCount = prefs.getInt("active_downloads_count", 0)
        prefs.edit().putInt("active_downloads_count", currentCount + 1).apply()
        atualizarNotificacaoDownload()
        
        // ✅ Começa a monitorar imediatamente ao clicar
        iniciarMonitoramentoUI()
    }

    private fun atualizarUI_download() {
        when (downloadState) {
            DownloadState.BAIXAR -> { 
                imgDownloadState.visibility = View.VISIBLE
                pbDownloadCircular?.visibility = View.GONE
                imgDownloadState.setImageResource(android.R.drawable.stat_sys_download)
                tvDownloadState.text = "BAIXAR"
            }
            DownloadState.BAIXANDO -> { 
                imgDownloadState.visibility = View.GONE
                pbDownloadCircular?.visibility = View.VISIBLE
                tvDownloadState.text = "0%" // O monitor vai atualizar isso logo em seguida
            }
            DownloadState.BAIXADO -> { 
                imgDownloadState.visibility = View.VISIBLE
                pbDownloadCircular?.visibility = View.GONE
                imgDownloadState.setImageResource(android.R.drawable.stat_sys_download_done)
                tvDownloadState.text = "BAIXADO"
            }
        }
    }
    
    // ✅ FUNÇÃO ESVAZIADA para não dar mais o erro do "nav_downloads"
    private fun atualizarNotificacaoDownload() {
        // Função desativada pois o botão de Downloads foi removido do rodapé
    }

    private fun handleDownloadClick() {
        when (downloadState) {
            DownloadState.BAIXAR -> iniciarDownload()
            DownloadState.BAIXANDO -> Toast.makeText(this, "Já está baixando...", Toast.LENGTH_SHORT).show()
            DownloadState.BAIXADO -> Toast.makeText(this, "Filme já baixado!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarConfiguracoes() {
        val p = arrayOf("ExoPlayer", "VLC", "MX Player")
        AlertDialog.Builder(this).setTitle("Player").setItems(p) { _, i -> getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().putString("player_preferido", p[i]).apply() }.show()
    }

    private fun isTelevisionDevice() = packageManager.hasSystemFeature("android.software.leanback") || packageManager.hasSystemFeature("android.hardware.type.television")

    override fun onDestroy() {
        client.dispatcher.cancelAll()
        uiMonitorJob?.cancel() // ✅ Para o monitoramento ao sair da tela
        super.onDestroy()
    }

    inner class EpisodesAdapter(private val onEpisodeClick: (EpisodeData) -> Unit) : ListAdapter<EpisodeData, EpisodesAdapter.ViewHolder>(DiffCallback) {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) = h.bind(getItem(p))
        inner class ViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
            fun bind(e: EpisodeData) {
                v.isFocusable = true
                val tvTitleEp = v.findViewById<TextView>(R.id.tvEpisodeTitle)
                tvTitleEp.text = if (e.videoKey != null) "Extra: ${e.title}" else "S${e.season}E${e.episode}: ${e.title}"
                Glide.with(v.context).load(e.thumb).centerCrop().into(v.findViewById(R.id.imgEpisodeThumb))
                v.setOnClickListener { onEpisodeClick(e) }
            }
        }
    }

    inner class SuggestionsAdapter(val items: List<JSONObject>) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(android.R.id.icon)
            val tv: TextView = v.findViewById(android.R.id.text1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(130.toPx(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(10, 10, 10, 10) }
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isClickable = true
            }
            val card = androidx.cardview.widget.CardView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(130.toPx(), 190.toPx())
                radius = 8f
            }
            val img = ImageView(parent.context).apply { id = android.R.id.icon; scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(img)
            val tv = TextView(parent.context).apply { id = android.R.id.text1; setTextColor(android.graphics.Color.WHITE); textSize = 10f; gravity = android.view.Gravity.CENTER; maxLines = 2 }
            container.addView(card); container.addView(tv)
            return ViewHolder(container)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val path = item.optString("poster_path")
            Glide.with(holder.itemView.context).load("https://image.tmdb.org/t/p/w342$path").into(holder.img)
            holder.tv.text = item.optString("title")
            holder.itemView.setOnClickListener {
                val intent = Intent(holder.itemView.context, DetailsActivity::class.java)
                intent.putExtra("stream_id", item.optInt("id"))
                intent.putExtra("name", item.optString("title"))
                intent.putExtra("icon", "https://image.tmdb.org/t/p/w342$path")
                intent.putExtra("PROFILE_NAME", currentProfile)
                holder.itemView.context.startActivity(intent)
            }
        }
        override fun getItemCount() = items.size
        private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        private object DiffCallback : DiffUtil.ItemCallback<EpisodeData>() {
            override fun areItemsTheSame(o: EpisodeData, n: EpisodeData) = o.streamId == n.streamId
            override fun areContentsTheSame(o: EpisodeData, n: EpisodeData) = o == n
        }
    }

    override fun onResume() {
        super.onResume()
        restaurarEstadoDownload()
        verificarResume()
        atualizarNotificacaoDownload() // ✅ Atualiza ao voltar
    }
}
