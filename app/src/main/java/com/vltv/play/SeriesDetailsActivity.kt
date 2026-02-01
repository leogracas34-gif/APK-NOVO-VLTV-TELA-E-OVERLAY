package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.google.android.material.bottomsheet.BottomSheetDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.ArrayList
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Importante: Certifique-se de que CastAdapter e CastMember estão no projeto
import com.vltv.play.CastAdapter
import com.vltv.play.CastMember

// ✅ IMPORTAÇÕES ADICIONADAS PARA A LOGO E LAYOUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    // Views
    private lateinit var imgPoster: ImageView
    private lateinit var imgBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView 
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView // Título "Elenco"
    private lateinit var recyclerCast: RecyclerView 
    private lateinit var tvPlot: TextView
    private lateinit var btnSeasonSelector: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var btnFavoriteSeries: ImageButton

    private lateinit var btnPlaySeries: Button
    private lateinit var btnDownloadEpisodeArea: LinearLayout
    private lateinit var imgDownloadEpisodeState: ImageView
    private lateinit var tvDownloadEpisodeState: TextView

    private lateinit var btnDownloadSeason: Button
    private lateinit var btnResume: Button // Botão Continuar

    // ✅ NOVAS VARIÁVEIS PARA O LAYOUT (INCLUÍDAS)
    private var appBarLayout: AppBarLayout? = null
    private var tabLayout: TabLayout? = null

    // ✅ NOVAS VIEWS (SUGESTÕES E DETALHES)
    private lateinit var recyclerSuggestions: RecyclerView
    private lateinit var llTechBadges: LinearLayout
    private lateinit var tvBadge4k: TextView
    private lateinit var tvBadgeHdr: TextView
    private lateinit var tvBadgeDolby: TextView
    private lateinit var tvBadge51: TextView
    private lateinit var tvReleaseDate: TextView
    private lateinit var tvCreatedBy: TextView

    private var episodesBySeason: Map<String, List<EpisodeStream>> = emptyMap()
    private var sortedSeasons: List<String> = emptyList()
    private var currentSeason: String = ""
    private var currentEpisode: EpisodeStream? = null

    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serie_details)

        // ✅ MODO IMERSIVO ATIVADO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        seriesId = intent.getIntExtra("series_id", 0)
        seriesName = intent.getStringExtra("name") ?: ""
        seriesIcon = intent.getStringExtra("icon")
        seriesRating = intent.getStringExtra("rating") ?: "0.0"

        inicializarViews()
        verificarTecnologias(seriesName) // Verifica se é 4K, HDR, etc no nome

        // ✅ EFEITO DE ALPHA NO SCROLL
        appBarLayout?.addOnOffsetChangedListener { appBar, verticalOffset ->
            val percentage = Math.abs(verticalOffset).toFloat() / appBar.totalScrollRange
            val alphaValue = if (percentage > 0.6f) 0f else 1f - (percentage * 1.5f).coerceAtMost(1f)
            
            tvTitle.alpha = alphaValue
            imgTitleLogo.alpha = alphaValue
            btnPlaySeries.alpha = alphaValue
            btnResume.alpha = alphaValue
            btnFavoriteSeries.alpha = alphaValue
            tvRating.alpha = alphaValue
            tvGenre.alpha = alphaValue
        }

        if (isTelevisionDevice()) {
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility = View.GONE
        }

        tvTitle.text = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text = "Gênero: Buscando..."
        tvCast.text = "Elenco:"
        tvPlot.text = "Carregando sinopse..."

        // CORREÇÃO: Fundo transparente para não criar caixa cinza
        btnSeasonSelector.setBackgroundColor(Color.TRANSPARENT)

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        
        // ✅ LISTA VERTICAL PARA CABER A SINOPSE
        rvEpisodes.layoutManager = LinearLayoutManager(this)

        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = rvEpisodes.findContainingViewHolder(view) as? EpisodeAdapter.VH
                holder?.let {
                    val position = holder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        currentEpisode = (rvEpisodes.adapter as? EpisodeAdapter)?.list?.getOrNull(position)
                        restaurarEstadoDownload()
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        // Configuração da lista de sugestões (Horizontal)
        recyclerSuggestions.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerSuggestions.setHasFixedSize(true)

        val isFavInicial = getFavSeries(this).contains(seriesId)
        atualizarIconeFavoritoSerie(isFavInicial)

        btnFavoriteSeries.setOnClickListener {
            val favs = getFavSeries(this)
            if (favs.contains(seriesId)) {
                favs.remove(seriesId)
            } else {
                favs.add(seriesId)
            }
            saveFavSeries(this, favs)
            atualizarIconeFavoritoSerie(favs.contains(seriesId))
        }

        btnSeasonSelector.setOnClickListener { mostrarSeletorDeTemporada() }

        btnPlaySeries.setOnClickListener {
            val ep = currentEpisode
            if (ep == null) {
                Toast.makeText(this, "Selecione um episódio.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            abrirPlayer(ep, false)
        }

        btnResume.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            abrirPlayer(ep, true)
        }

        restaurarEstadoDownload()
        setupDownloadButtons()
        tentarCarregarLogoCache()
        carregarSeriesInfo()
        sincronizarDadosTMDB()

        // ✅ APLICAÇÃO DE FOCO NOS BOTÕES
        val commonFocus = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                if (v is Button) v.setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                if (v is Button) {
                    v.setBackgroundResource(android.R.drawable.btn_default)
                    v.setTextColor(Color.WHITE)
                } else {
                    v.setBackgroundResource(0)
                }
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlaySeries.onFocusChangeListener = commonFocus
        btnResume.onFocusChangeListener = commonFocus
        btnSeasonSelector.onFocusChangeListener = commonFocus

        // ✅ LÓGICA DE TROCA DE ABAS
        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // EPISÓDIOS
                        rvEpisodes.visibility = View.VISIBLE
                        tvPlot.visibility = View.GONE
                        tvCast.visibility = View.GONE
                        recyclerCast.visibility = View.GONE
                        tvReleaseDate.visibility = View.GONE
                        tvCreatedBy.visibility = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                    }
                    2 -> { // DETALHES
                        rvEpisodes.visibility = View.GONE
                        tvPlot.visibility = View.VISIBLE
                        tvCast.visibility = View.VISIBLE
                        // CORREÇÃO: Tornar o elenco e detalhes visíveis
                        recyclerCast.visibility = View.VISIBLE
                        tvReleaseDate.visibility = View.VISIBLE
                        tvCreatedBy.visibility = View.VISIBLE
                        recyclerSuggestions.visibility = View.GONE
                        tvPlot.setTextColor(Color.WHITE)
                    }
                    1 -> { // SUGESTÕES
                        rvEpisodes.visibility = View.GONE
                        tvPlot.visibility = View.GONE
                        tvCast.visibility = View.GONE
                        recyclerCast.visibility = View.GONE
                        tvReleaseDate.visibility = View.GONE
                        tvCreatedBy.visibility = View.GONE
                        // MOSTRAR SUGESTÕES
                        recyclerSuggestions.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun inicializarViews() {
        appBarLayout = findViewById(R.id.appBar)
        tabLayout = findViewById(R.id.tabLayout)
        
        if (tabLayout?.tabCount == 0) {
            tabLayout?.addTab(tabLayout!!.newTab().setText("EPISÓDIOS"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("SUGESTÕES"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("DETALHES"))
        }

        imgPoster = findViewById(R.id.imgPoster)
        imgBackground = try { findViewById(R.id.imgBackground) } catch (e: Exception) { imgPoster }
        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.visibility = View.INVISIBLE
        imgTitleLogo = findViewById(R.id.imgTitleLogo)
        tvRating = findViewById(R.id.tvRating)
        tvGenre = findViewById(R.id.tvGenre)
        
        // NOVAS VIEWS DE BADGES
        llTechBadges = findViewById(R.id.llTechBadges)
        tvBadge4k = findViewById(R.id.tvBadge4k)
        tvBadgeHdr = findViewById(R.id.tvBadgeHdr)
        tvBadgeDolby = findViewById(R.id.tvBadgeDolby)
        tvBadge51 = findViewById(R.id.tvBadge51)
        
        tvPlot = findViewById(R.id.tvPlot)
        
        // NOVAS VIEWS DE DETALHES
        tvReleaseDate = findViewById(R.id.tvReleaseDate)
        tvCreatedBy = findViewById(R.id.tvCreatedBy)

        tvCast = findViewById(R.id.tvCast)
        recyclerCast = findViewById(R.id.recyclerCast)
        recyclerCast.visibility = View.GONE 
        
        // VIEW DE SUGESTÕES
        recyclerSuggestions = findViewById(R.id.recyclerSuggestions)
        
        btnSeasonSelector = findViewById(R.id.btnSeasonSelector)
        rvEpisodes = findViewById(R.id.recyclerEpisodes)
        btnPlaySeries = findViewById(R.id.btnPlay)
        btnFavoriteSeries = findViewById(R.id.btnFavorite)
        btnResume = findViewById(R.id.btnResume)
        btnDownloadEpisodeArea = findViewById(R.id.btnDownloadArea)
        imgDownloadEpisodeState = findViewById(R.id.imgDownloadState)
        tvDownloadEpisodeState = findViewById(R.id.tvDownloadState)
        btnDownloadSeason = findViewById(R.id.btnDownloadSeason)
    }

    private fun verificarTecnologias(nome: String) {
        val nomeUpper = nome.uppercase()
        var temBadge = false
        
        if (nomeUpper.contains("4K") || nomeUpper.contains("UHD")) {
            tvBadge4k.visibility = View.VISIBLE
            temBadge = true
        }
        if (nomeUpper.contains("HDR")) {
            tvBadgeHdr.visibility = View.VISIBLE
            temBadge = true
        }
        if (nomeUpper.contains("DOLBY") || nomeUpper.contains("VISION")) {
            tvBadgeDolby.visibility = View.VISIBLE
            temBadge = true
        }
        if (nomeUpper.contains("5.1")) {
            tvBadge51.visibility = View.VISIBLE
            temBadge = true
        }
        
        llTechBadges.visibility = if (temBadge) View.VISIBLE else View.GONE
    }

    private fun setupDownloadButtons() {
        btnDownloadEpisodeArea.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            when (downloadState) {
                DownloadState.BAIXAR -> {
                    val eid = ep.id.toIntOrNull() ?: 0
                    if (eid == 0) return@setOnClickListener
                    val url = montarUrlEpisodio(ep)
                    val safeTitle = seriesName.replace("[^a-zA-Z0-9 _.-]".toRegex(), "_").ifBlank { "serie" }
                    val fileName = "${safeTitle}_T${currentSeason}E${ep.episode_num}_${eid}.mp4"
                    DownloadHelper.enqueueDownload(this, url, fileName, logicalId = "series_$eid", type = "series")
                    Toast.makeText(this, "Baixando...", Toast.LENGTH_SHORT).show()
                    setDownloadState(DownloadState.BAIXANDO, ep)
                }
                DownloadState.BAIXANDO -> startActivity(Intent(this, DownloadsActivity::class.java))
                DownloadState.BAIXADO -> startActivity(Intent(this, DownloadsActivity::class.java))
            }
        }

        btnDownloadSeason.setOnClickListener {
            if (currentSeason.isBlank()) return@setOnClickListener
            val lista = episodesBySeason[currentSeason] ?: emptyList()
            if (lista.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Baixar temporada")
                .setMessage("Baixar todos os ${lista.size} episódios?")
                .setPositiveButton("Sim") { _, _ -> baixarTemporadaAtual(lista) }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        var cleanName = seriesName
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        val lixo = listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
        lixo.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

        val encodedName = try { URLEncoder.encode(cleanName, "UTF-8") } catch(e:Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encodedName&language=pt-BR&region=BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val show = results.getJSONObject(0)
                            val tmdbId = show.getInt("id")
                            buscarLogoSerieTraduzida(tmdbId, apiKey)
                            buscarDetalhesTMDB(tmdbId, apiKey)
                            runOnUiThread {
                                val sinopse = show.optString("overview")
                                tvPlot.text = if (sinopse.isNotEmpty()) sinopse else "Sinopse indisponível."
                                val vote = show.optDouble("vote_average", 0.0)
                                if (vote > 0) tvRating.text = "Nota: ${String.format("%.1f", vote)}"
                                val backdropPath = show.optString("backdrop_path")
                                if (backdropPath.isNotEmpty() && imgBackground != imgPoster) {
                                    Glide.with(this@SeriesDetailsActivity)
                                        .load("https://image.tmdb.org/t/p/w1280$backdropPath")
                                        .centerCrop().into(imgBackground)
                                }
                                Glide.with(this@SeriesDetailsActivity).load(seriesIcon).placeholder(R.mipmap.ic_launcher).centerCrop().into(imgPoster)
                            }
                        } else {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                    }
                }
            }
        })
    }

    private fun buscarLogoSerieTraduzida(id: Int, key: String) {
        val imagesUrl = "https://api.themoviedb.org/3/tv/$id/images?api_key=$key&include_image_language=pt,en,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
                            getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
                                .edit().putString("series_logo_$seriesId", finalUrl).apply()
                            runOnUiThread {
                                tvTitle.visibility = View.GONE
                                imgTitleLogo.visibility = View.VISIBLE
                                Glide.with(this@SeriesDetailsActivity).load(finalUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
                            }
                        } else {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                    }
                }
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
            }
        })
    }

    private fun buscarDetalhesTMDB(id: Int, key: String) {
        // ADICIONADO 'similar' e 'content_ratings' NA CHAMADA
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits,similar&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string() ?: return
                try {
                    val d = JSONObject(body)
                    val gs = d.optJSONArray("genres")
                    val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))

                    val credits = d.optJSONObject("credits")
                    val castArray = credits?.optJSONArray("cast")
                    val castNames = mutableListOf<String>()
                    if (castArray != null) {
                        val limit = if (castArray.length() > 10) 10 else castArray.length()
                        for (i in 0 until limit) {
                            castNames.add(castArray.getJSONObject(i).getString("name"))
                        }
                    }

                    // DADOS EXTRAS (DATA E CRIADOR)
                    val firstAirDate = d.optString("first_air_date", "")
                    val createdByArray = d.optJSONArray("created_by")
                    val creatorsList = mutableListOf<String>()
                    if (createdByArray != null) {
                        for (i in 0 until createdByArray.length()) {
                            creatorsList.add(createdByArray.getJSONObject(i).getString("name"))
                        }
                    }

                    // SUGESTÕES (SÉRIES PARECIDAS)
                    val similar = d.optJSONObject("similar")
                    val similarResults = similar?.optJSONArray("results")
                    val sugestoesList = ArrayList<JSONObject>()
                    if (similarResults != null) {
                        for (i in 0 until similarResults.length()) {
                            sugestoesList.add(similarResults.getJSONObject(i))
                        }
                    }

                    runOnUiThread {
                        tvGenre.text = "Gênero: ${if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")}"
                        tvCast.text = "Elenco: ${castNames.joinToString(", ")}"
                        
                        // Preencher Data e Criador
                        if (firstAirDate.isNotEmpty()) {
                            val ano = firstAirDate.split("-")[0]
                            tvReleaseDate.text = "Lançamento: $ano"
                        }
                        if (creatorsList.isNotEmpty()) {
                            tvCreatedBy.text = "Criado por: ${creatorsList.joinToString(", ")}"
                        }

                        // Configurar Adapter de Sugestões
                        if (sugestoesList.isNotEmpty()) {
                            recyclerSuggestions.adapter = SuggestionsAdapter(sugestoesList)
                        }
                    }
                } catch(e: Exception) { }
            }
        })
    }

    private fun tentarCarregarLogoCache() {
        val prefs = getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
        val cachedUrl = prefs.getString("series_logo_$seriesId", null)
        if (cachedUrl != null) {
            tvTitle.visibility = View.GONE
            imgTitleLogo.visibility = View.VISIBLE
            Glide.with(this).load(cachedUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
        }
    }

    override fun onResume() {
        super.onResume()
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        restaurarEstadoDownload()
    }

    private fun isTelevisionDevice() = packageManager.hasSystemFeature("android.software.leanback") || packageManager.hasSystemFeature("android.hardware.type.television")

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun saveFavSeries(context: Context, ids: Set<Int>) {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("fav_series", ids.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavoritoSerie(isFav: Boolean) {
        if (isFav) {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_on)
            btnFavoriteSeries.setColorFilter(Color.parseColor("#FFD700"))
        } else {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_off)
            btnFavoriteSeries.clearColorFilter()
        }
    }

    private fun carregarSeriesInfo() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        XtreamApi.service.getSeriesInfoV2(username, password, seriesId = seriesId)
            .enqueue(object : Callback<SeriesInfoResponse> {
                override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        episodesBySeason = body.episodes ?: emptyMap()
                        sortedSeasons = episodesBySeason.keys.sortedBy { it.toIntOrNull() ?: 0 }
                        if (sortedSeasons.isNotEmpty()) mudarTemporada(sortedSeasons.first())
                        else btnSeasonSelector.text = "Indisponível"
                    }
                }
                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    Toast.makeText(this@SeriesDetailsActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun mostrarSeletorDeTemporada() {
        if (sortedSeasons.isEmpty()) return
        val dialog = BottomSheetDialog(this, R.style.DialogTemporadaTransparente)
        val root = LinearLayout(this)
        root.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.TRANSPARENT)

        val rvSeasons = RecyclerView(this)
        val rvParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 300.toPx())
        rvSeasons.layoutParams = rvParams
        rvSeasons.layoutManager = LinearLayoutManager(this)

        rvSeasons.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                tv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tv.setPadding(40, 25, 40, 25)
                tv.gravity = Gravity.CENTER
                tv.textSize = 24f
                tv.setTextColor(Color.WHITE)
                tv.isFocusable = true
                tv.isClickable = true
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val season = sortedSeasons[position]
                val tv = holder.itemView as TextView
                tv.text = "Temporada $season"
                tv.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                        (v as TextView).setTextColor(Color.YELLOW)
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    } else {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        (v as TextView).setTextColor(Color.WHITE)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
                tv.setOnClickListener { mudarTemporada(season); dialog.dismiss() }
            }
            override fun getItemCount() = sortedSeasons.size
        }

        val btnClose = TextView(this)
        val closeParams = LinearLayout.LayoutParams(80.toPx(), 80.toPx())
        closeParams.topMargin = 20.toPx() 
        btnClose.layoutParams = closeParams
        btnClose.text = "X"
        btnClose.gravity = Gravity.CENTER
        btnClose.textSize = 35f
        btnClose.setTextColor(Color.WHITE)
        btnClose.isFocusable = true
        btnClose.isClickable = true
        
        btnClose.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                (v as TextView).setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                v.setBackgroundResource(0)
                (v as TextView).setTextColor(Color.WHITE)
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        root.addView(rvSeasons)
        root.addView(btnClose) 
        dialog.setContentView(root)
        
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = resources.displayMetrics.heightPixels
                it.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        dialog.show()
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun mudarTemporada(seasonKey: String) {
        currentSeason = seasonKey
        btnSeasonSelector.text = "Temporada $seasonKey ▼"
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            restaurarEstadoDownload()
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val streamId = currentEpisode?.id?.toIntOrNull() ?: 0
            val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
            btnResume.visibility = if (pos > 10000) View.VISIBLE else View.GONE
        }
        rvEpisodes.adapter = EpisodeAdapter(lista) { ep, _ ->
            currentEpisode = ep
            restaurarEstadoDownload()
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val streamId = ep.id.toIntOrNull() ?: 0
            val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
            btnResume.visibility = if (pos > 10000) View.VISIBLE else View.GONE
            abrirPlayer(ep, true)
        }
    }

    private fun abrirPlayer(ep: EpisodeStream, usarResume: Boolean) {
        val streamId = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"

        val lista = episodesBySeason[currentSeason] ?: emptyList()
        val posInList = lista.indexOfFirst { it.id == ep.id }
        val nextEp = if (posInList + 1 < lista.size) lista[posInList + 1] else null

        val mochilaIds = ArrayList<Int>()
        for (item in lista) {
            val idInt = item.id.toIntOrNull() ?: 0
            if (idInt != 0) mochilaIds.add(idInt)
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
        val dur = prefs.getLong("series_resume_${streamId}_dur", 0L)
        val existe = usarResume && pos > 30000L && pos < (dur * 0.95).toLong()

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId)
        intent.putExtra("stream_ext", ext)
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", "T${currentSeason}E${ep.episode_num} - $seriesName")

        if (mochilaIds.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", mochilaIds)
        }

        if (existe) intent.putExtra("start_position_ms", pos)

        if (nextEp != null) {
            intent.putExtra("next_stream_id", nextEp.id.toIntOrNull() ?: 0)
            intent.putExtra("next_channel_name", "T${currentSeason}E${nextEp.episode_num} - $seriesName")
        }

        startActivity(intent)
    }

    private fun montarUrlEpisodio(ep: EpisodeStream): String {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        val server = "http://tvblack.shop"
        val eid = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"
        return "$server/get.php?username=$user&password=$pass&type=series&output=$ext&id=$eid"
    }

    private fun baixarTemporadaAtual(lista: List<EpisodeStream>) {
        for (ep in lista) {
            val eid = ep.id.toIntOrNull() ?: continue
            DownloadHelper.enqueueDownload(this, montarUrlEpisodio(ep), "${seriesName}_T${currentSeason}E${ep.episode_num}.mp4", logicalId = "series_$eid", type = "series")
        }
        Toast.makeText(this, "Baixando episódios...", Toast.LENGTH_LONG).show()
    }

    private fun getProgressText(): String {
        val ep = currentEpisode ?: return "Baixar"
        val eid = ep.id.toIntOrNull() ?: 0
        val progress = DownloadHelper.getDownloadProgress(this, "series_$eid")
        return when (downloadState) {
            DownloadState.BAIXANDO -> "Baixando ${progress}%"
            DownloadState.BAIXADO -> "Baixado"
            else -> "Baixar"
        }
    }

    private fun setDownloadState(state: DownloadState, ep: EpisodeStream?) {
        downloadState = state
        val eid = ep?.id?.toIntOrNull() ?: 0
        if (eid != 0) getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().putString("series_download_state_$eid", state.name).apply()
        when (state) {
            DownloadState.BAIXAR -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_arrow); tvDownloadEpisodeState.text = "Baixar" }
            DownloadState.BAIXANDO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_loading); tvDownloadEpisodeState.text = getProgressText() }
            DownloadState.BAIXADO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_done); tvDownloadEpisodeState.text = "Baixado" }
        }
    }

    private fun restaurarEstadoDownload() {
        val ep = currentEpisode ?: return
        val eid = ep.id.toIntOrNull() ?: 0
        val saved = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getString("series_download_state_$eid", DownloadState.BAIXAR.name)
        setDownloadState(DownloadState.valueOf(saved!!), ep)
    }

    class EpisodeAdapter(val list: List<EpisodeStream>, private val onClick: (EpisodeStream, Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView = v.findViewById(R.id.imgEpisodeThumb)
            // ✅ ADICIONADO: REFERÊNCIA PARA A SINOPSE DO EPISÓDIO
            val tvPlotEp: TextView = v.findViewById(R.id.tvEpisodePlot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.tvTitle.text = "E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
            
            // ✅ EXIBE A SINOPSE DO EPISÓDIO (E NÃO DA SÉRIE)
            holder.tvPlotEp.text = ep.info?.plot ?: "Sem descrição disponível."

            val capaUrl = ep.info?.movie_image ?: ""

            Glide.with(holder.itemView.context)
                .load(capaUrl)
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.black)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.imgThumb)

            holder.itemView.setOnClickListener { onClick(ep, position) }

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                holder.tvTitle.setTextColor(if (hasFocus) Color.YELLOW else Color.WHITE)

                if (hasFocus) {
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(200).start()
                    view.elevation = 15f
                } else {
                    // CORREÇÃO: Usar bg_episodio_item (transparente) em vez de remover o fundo (0)
                    view.setBackgroundResource(R.drawable.bg_episodio_item)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    view.elevation = 4f
                }
            }
        }

        override fun getItemCount() = list.size
    }

    // ✅ ADAPTER SIMPLES PARA AS SUGESTÕES
    inner class SuggestionsAdapter(val items: List<JSONObject>) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(android.R.id.icon) // Vamos usar um layout simples ou criar dinamicamente
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Cria um CardView com ImageView dinamicamente para não precisar de outro XML agora
            val card = androidx.cardview.widget.CardView(parent.context)
            val params = ViewGroup.MarginLayoutParams(130.toPx(), 200.toPx())
            params.setMargins(10, 10, 10, 10)
            card.layoutParams = params
            card.radius = 12f
            card.cardElevation = 4f
            
            val img = ImageView(parent.context)
            img.id = android.R.id.icon
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            card.addView(img)
            
            return ViewHolder(card)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val posterPath = item.optString("poster_path")
            val name = item.optString("name")
            val id = item.optInt("id")
            val rating = item.optDouble("vote_average", 0.0)

            Glide.with(holder.itemView.context)
                .load("https://image.tmdb.org/t/p/w342$posterPath")
                .into(holder.img)

            holder.itemView.setOnClickListener {
                // Ao clicar, tenta abrir a série
                // Nota: Para abrir a série correta do seu servidor, precisaríamos buscar pelo nome no seu servidor.
                // Como não temos essa busca reversa implementada aqui, vou apenas reiniciar a tela com os dados visuais do TMDB
                // O ideal seria implementar uma busca no seu XtreamApi pelo nome 'name'
                Toast.makeText(this@SeriesDetailsActivity, "Sugestão: $name", Toast.LENGTH_SHORT).show()
            }
            
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if(hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).start()
                    v.setBackgroundResource(R.drawable.bg_focus_neon)
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).start()
                    v.setBackgroundResource(0)
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
