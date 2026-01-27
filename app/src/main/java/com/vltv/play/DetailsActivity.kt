package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
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
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import com.vltv.play.CastMember
import com.vltv.play.CastAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EpisodeData(
    val streamId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String
)

class DetailsActivity : AppCompatActivity() {

    private var streamId: Int = 0
    private var name: String = ""
    private var icon: String? = null
    private var rating: String = "0.0"
    private var isSeries: Boolean = false
    private var episodes: List<EpisodeData> = emptyList()
    private var hasResumePosition: Boolean = false

    private lateinit var imgPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnResume: Button
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnDownloadArea: LinearLayout
    private lateinit var imgDownloadState: ImageView
    private lateinit var tvDownloadState: TextView
    private lateinit var imgBackground: ImageView
    private lateinit var tvEpisodesTitle: TextView
    private lateinit var recyclerEpisodes: RecyclerView
    
    private var tvYear: TextView? = null
    private var btnSettings: Button? = null

    private lateinit var episodesAdapter: EpisodesAdapter
    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        configurarTelaTV()
        
        streamId = intent.getIntExtra("stream_id", 0)
        name = intent.getStringExtra("name") ?: ""
        icon = intent.getStringExtra("icon")
        rating = intent.getStringExtra("rating") ?: "0.0"
        isSeries = intent.getBooleanExtra("is_series", false)

        inicializarViews()
        carregarConteudo()
        setupEventos()
        setupEpisodesRecycler()

        // ✅ Lógica Netflix: Carrega o cache completo (Logo + Textos) antes de ir na internet
        tentarCarregarTudoCache()

        sincronizarDadosTMDB()
    }

    private fun configurarTelaTV() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        if (isTelevisionDevice()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun inicializarViews() {
        imgPoster = findViewById(R.id.imgPoster)
        tvTitle = findViewById(R.id.tvTitle)
        
        tvTitle.visibility = View.VISIBLE 
        tvTitle.text = name

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

        if (isTelevisionDevice()) {
            btnDownloadArea.visibility = View.GONE
        }

        btnPlay.isFocusable = true
        btnResume.isFocusable = true
        btnFavorite.isFocusable = true
        btnSettings?.isFocusable = true
        btnPlay.requestFocus()
    }

    private fun carregarConteudo() {
        tvRating.text = "⭐ $rating"
        if (tvPlot.text.isNullOrEmpty() || tvPlot.text == "...") tvPlot.text = "Carregando..."
        tvYear?.text = "" 

        Glide.with(this).load(icon).placeholder(android.R.drawable.ic_menu_gallery).into(imgPoster)
        Glide.with(this).load(icon).centerCrop().into(imgBackground)

        val isFavInicial = getFavMovies(this).contains(streamId)
        atualizarIconeFavorito(isFavInicial)

        if (isSeries) {
            carregarEpisodios()
        } else {
            tvEpisodesTitle.visibility = View.GONE
            recyclerEpisodes.visibility = View.GONE
        }

        verificarResume()
        restaurarEstadoDownload()
    }

    // ✅ NOVO: Tenta carregar TUDO o que já foi salvo para este StreamId (Instantâneo)
    private fun tentarCarregarTudoCache() {
        val prefs = getSharedPreferences("vltv_data_cache", Context.MODE_PRIVATE)
        val cachedUrl = prefs.getString("logo_$streamId", null)
        val cachedTitle = prefs.getString("title_$streamId", null)
        val cachedPlot = prefs.getString("plot_$streamId", null)
        val cachedCast = prefs.getString("cast_$streamId", null)
        val cachedGenre = prefs.getString("genre_$streamId", null)
        val cachedYear = prefs.getString("year_$streamId", null)

        val imgLogo = findViewById<ImageView>(R.id.imgTitleLogo)

        if (cachedTitle != null) {
            tvTitle.text = cachedTitle
            tvPlot.text = cachedPlot
            tvCast.text = cachedCast
            tvGenre.text = cachedGenre
            tvYear?.text = cachedYear
        }

        if (cachedUrl != null && imgLogo != null) {
            imgLogo.visibility = View.VISIBLE
            tvTitle.visibility = View.GONE
            Glide.with(this)
                .load(cachedUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.IMMEDIATE)
                .dontAnimate()
                .placeholder(imgLogo.drawable)
                .into(imgLogo)
        }
    }

    private fun sincronizarDadosTMDB() {
        val prefs = getSharedPreferences("vltv_data_cache", Context.MODE_PRIVATE)
        val tmdbId = prefs.getInt("tmdb_id_$streamId", -1)
        val type = if (isSeries) "tv" else "movie"
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"

        // ✅ Atalho: Se já conhecemos o ID, pula a pesquisa e vai direto nos dados
        if (tmdbId != -1) {
            buscarLogoOverlayTraduzida(tmdbId, type, apiKey)
            buscarDetalhesCompletos(tmdbId, type, apiKey)
            return
        }

        var cleanName = name
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        val lixo = listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
        lixo.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        val searchName = cleanName.trim().replace(Regex("\\s+"), " ")
        
        val encodedName = try { URLEncoder.encode(searchName, "UTF-8") } catch(e:Exception) { searchName }
        val url = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&query=$encodedName&language=pt-BR"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            
                            var selectedMovie = results.getJSONObject(0)
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val tmdbTitle = if (type == "movie") item.optString("title") else item.optString("name")
                                if (tmdbTitle.equals(searchName, ignoreCase = true)) {
                                    selectedMovie = item
                                    break
                                }
                            }

                            val idTmdb = selectedMovie.getInt("id")
                            prefs.edit().putInt("tmdb_id_$streamId", idTmdb).apply()

                            buscarLogoOverlayTraduzida(idTmdb, type, apiKey)
                            buscarDetalhesCompletos(idTmdb, type, apiKey)

                            runOnUiThread {
                                val tituloOficial = if (selectedMovie.has("title")) selectedMovie.getString("title") else selectedMovie.optString("name")
                                if (tituloOficial.isNotEmpty()) {
                                    tvTitle.text = tituloOficial
                                    prefs.edit().putString("title_$streamId", tituloOficial).apply()
                                }
                                
                                val date = if (isSeries) selectedMovie.optString("first_air_date") else selectedMovie.optString("release_date")
                                if (date.length >= 4) {
                                    val yr = date.substring(0, 4)
                                    tvYear?.text = yr
                                    prefs.edit().putString("year_$streamId", yr).apply()
                                }
                                val sinopse = selectedMovie.optString("overview")
                                if (sinopse.isNotEmpty()) {
                                    tvPlot.text = sinopse
                                    prefs.edit().putString("plot_$streamId", sinopse).apply()
                                }
                                val vote = selectedMovie.optDouble("vote_average", 0.0)
                                if (vote > 0) tvRating.text = "⭐ ${String.format("%.1f", vote)}"
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        })
    }

    private fun buscarLogoOverlayTraduzida(id: Int, type: String, key: String) {
        val imagesUrl = "https://api.themoviedb.org/3/$type/$id/images?api_key=$key&include_image_language=pt,en,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val obj = JSONObject(body)
                        val logos = obj.optJSONArray("logos")
                        if (logos != null && logos.length() > 0) {
                            var logoPath: String? = null
                            for (i in 0 until logos.length()) {
                                val logo = logos.getJSONObject(i)
                                if (logo.optString("iso_639_1") == "pt") {
                                    logoPath = logo.getString("file_path")
                                    break
                                }
                            }
                            if (logoPath == null) logoPath = logos.getJSONObject(0).getString("file_path")
                            val finalUrl = "https://image.tmdb.org/t/p/w500$logoPath"
                            
                            getSharedPreferences("vltv_data_cache", Context.MODE_PRIVATE)
                                .edit().putString("logo_$streamId", finalUrl).apply()

                            runOnUiThread {
                                val imgLogo = findViewById<ImageView>(R.id.imgTitleLogo)
                                if (imgLogo != null) {
                                    tvTitle.visibility = View.GONE
                                    imgLogo.visibility = View.VISIBLE
                                    Glide.with(this@DetailsActivity)
                                        .load(finalUrl)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .dontAnimate()
                                        .placeholder(imgLogo.drawable) 
                                        .into(imgLogo)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun buscarDetalhesCompletos(id: Int, type: String, key: String) {
        val detailUrl = "https://api.themoviedb.org/3/$type/$id?api_key=$key&append_to_response=credits&language=pt-BR"
        client.newCall(Request.Builder().url(detailUrl).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val d = JSONObject(body)
                        val gs = d.optJSONArray("genres")
                        val genresList = mutableListOf<String>()
                        if (gs != null) {
                            for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))
                        }
                        
                        val castArray = d.optJSONObject("credits")?.optJSONArray("cast")
                        val castNamesList = mutableListOf<String>()
                        if (castArray != null) {
                            val limit = if (castArray.length() > 15) 15 else castArray.length()
                            for (i in 0 until limit) {
                                castNamesList.add(castArray.getJSONObject(i).getString("name"))
                            }
                        }
                        
                        runOnUiThread {
                            val gn = "Gênero: ${if (genresList.isEmpty()) "Diversos" else genresList.joinToString(", ")}"
                            tvGenre.text = gn
                            val el = if (castNamesList.isEmpty()) "Elenco: Indisponível" else "Elenco: " + castNamesList.joinToString(", ")
                            tvCast.text = el
                            
                            getSharedPreferences("vltv_data_cache", Context.MODE_PRIVATE).edit()
                                .putString("genre_$streamId", gn)
                                .putString("cast_$streamId", el)
                                .apply()

                            findViewById<RecyclerView>(R.id.recyclerCast).adapter = null
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun setupEpisodesRecycler() {
        episodesAdapter = EpisodesAdapter { episode ->
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra("stream_id", episode.streamId)
            intent.putExtra("stream_type", "series")
            intent.putExtra("channel_name", "${name} - S${episode.season}:E${episode.episode}")
            startActivity(intent)
        }
        recyclerEpisodes.apply {
            layoutManager = if (isTelevisionDevice()) GridLayoutManager(this@DetailsActivity, 6) else LinearLayoutManager(this@DetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = episodesAdapter
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun carregarEpisodios() {
        episodes = listOf(EpisodeData(101, 1, 1, "Episódio 1", icon ?: ""))
        episodesAdapter.submitList(episodes)
        tvEpisodesTitle.visibility = View.VISIBLE
        recyclerEpisodes.visibility = View.VISIBLE
    }

    private fun setupEventos() {
        val zoomFocus = View.OnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.1f else 1.0f
            v.scaleY = if (hasFocus) 1.1f else 1.0f
        }
        btnPlay.onFocusChangeListener = zoomFocus
        btnResume.onFocusChangeListener = zoomFocus
        btnFavorite.onFocusChangeListener = zoomFocus
        btnSettings?.onFocusChangeListener = zoomFocus

        btnFavorite.setOnClickListener { toggleFavorite() }
        btnPlay.setOnClickListener { abrirPlayer(false) }
        btnResume.setOnClickListener { abrirPlayer(true) }
        btnDownloadArea.setOnClickListener { handleDownloadClick() }
        btnSettings?.setOnClickListener { mostrarConfiguracoes() }
        
        if (isTelevisionDevice()) {
            imgPoster.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
                    abrirPlayer(false); true
                } else false
            }
        }
    }

    private fun getFavMovies(context: Context): MutableList<Int> {
        val prefs = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        return prefs.getStringSet("favoritos", emptySet())?.mapNotNull { it.toIntOrNull() }?.toMutableList() ?: mutableListOf()
    }

    private fun saveFavMovies(context: Context, favs: List<Int>) {
        val prefs = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favoritos", favs.map { it.toString() }.toSet()).apply()
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
        if (isFav) favs.remove(streamId) else favs.add(streamId)
        saveFavMovies(this, favs)
        atualizarIconeFavorito(!isFav)
    }

    private fun verificarResume() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val pos = prefs.getLong("movie_resume_${streamId}_pos", 0L)
        btnResume.visibility = if (pos > 30000L) View.VISIBLE else View.GONE
    }

    private fun abrirPlayer(usarResume: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId)
        intent.putExtra("stream_type", if (isSeries) "series" else "movie")
        intent.putExtra("channel_name", name)
        if (usarResume) {
            val pos = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getLong("movie_resume_${streamId}_pos", 0L)
            intent.putExtra("start_position_ms", pos)
        }
        startActivity(intent)
    }

    private fun restaurarEstadoDownload() {
        val prefs = getSharedPreferences("vltv_downloads", Context.MODE_PRIVATE)
        val estado = prefs.getString("download_state_$streamId", "BAIXAR")
        try { downloadState = DownloadState.valueOf(estado ?: "BAIXAR") } catch(e: Exception) { downloadState = DownloadState.BAIXAR }
        atualizarUI_download()
    }

    private fun iniciarDownload() {
        downloadState = DownloadState.BAIXANDO
        atualizarUI_download()
        Handler(Looper.getMainLooper()).postDelayed({
            downloadState = DownloadState.BAIXADO
            atualizarUI_download()
            getSharedPreferences("vltv_downloads", Context.MODE_PRIVATE).edit().putString("download_state_$streamId", "BAIXADO").apply()
        }, 3000)
    }

    private fun atualizarUI_download() {
        when (downloadState) {
            DownloadState.BAIXAR -> { imgDownloadState.setImageResource(android.R.drawable.stat_sys_download); tvDownloadState.text = "Baixar" }
            DownloadState.BAIXANDO -> { imgDownloadState.setImageResource(android.R.drawable.ic_media_play); tvDownloadState.text = "Baixando..." }
            DownloadState.BAIXADO -> { imgDownloadState.setImageResource(android.R.drawable.stat_sys_download_done); tvDownloadState.text = "Baixado" }
        }
    }

    private fun handleDownloadClick() {
        if (downloadState == DownloadState.BAIXAR) iniciarDownload()
        else if (downloadState == DownloadState.BAIXADO) Toast.makeText(this, "Já baixado", Toast.LENGTH_SHORT).show()
    }

    private fun mostrarConfiguracoes() {
        val players = arrayOf("ExoPlayer", "VLC", "MX Player")
        AlertDialog.Builder(this).setTitle("Player").setItems(players) { _, i ->
            getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().putString("player_preferido", players[i]).apply()
        }.show()
    }

    private fun isTelevisionDevice() = packageManager.hasSystemFeature("android.software.leanback") || packageManager.hasSystemFeature("android.hardware.type.television")

    inner class EpisodesAdapter(private val onEpisodeClick: (EpisodeData) -> Unit) : ListAdapter<EpisodeData, EpisodesAdapter.ViewHolder>(DiffCallback) {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) = h.bind(getItem(p))
        
        inner class ViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
            fun bind(e: EpisodeData) {
                v.isFocusable = true
                v.setOnFocusChangeListener { _, hasFocus ->
                    v.scaleX = if (hasFocus) 1.1f else 1.0f
                    v.scaleY = if (hasFocus) 1.1f else 1.0f
                }
                
                v.findViewById<TextView>(R.id.tvEpisodeTitle).text = "S${e.season}E${e.episode}: ${e.title}"
                Glide.with(v.context).load(e.thumb).centerCrop().into(v.findViewById(R.id.imgEpisodeThumb))
                v.setOnClickListener { onEpisodeClick(e) }
            }
        }
    }

    companion object {
        private object DiffCallback : DiffUtil.ItemCallback<EpisodeData>() {
            override fun areItemsTheSame(o: EpisodeData, n: EpisodeData) = o.streamId == n.streamId
            override fun areContentsTheSame(o: EpisodeData, n: EpisodeData) = o == n
        }
    }

    override fun onResume() { super.onResume(); restaurarEstadoDownload(); verificarResume() }
}
