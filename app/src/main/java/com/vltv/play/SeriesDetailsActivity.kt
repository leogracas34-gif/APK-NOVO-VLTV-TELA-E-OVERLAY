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
import androidx.recyclerview.widget.GridLayoutManager
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView // ADICIONADO PARA O MENU

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    // ✅ VARIÁVEL PARA O PERFIL ATUAL (Isolamento de dados)
    private var currentProfile: String = "Padrao"

    // Views
    private lateinit var imgPoster: ImageView
    private lateinit var imgBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView 
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var recyclerCast: RecyclerView 
    private lateinit var tvPlot: TextView
    private lateinit var btnSeasonSelector: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var btnFavoriteSeries: ImageButton

    private lateinit var btnPlaySeries: Button
    
    // --- ÁREA DE DOWNLOAD ANTIGA (COMENTADA PARA NÃO DAR ERRO) ---
    // private lateinit var btnDownloadEpisodeArea: LinearLayout
    // private lateinit var imgDownloadEpisodeState: ImageView
    // private lateinit var tvDownloadEpisodeState: TextView

    private lateinit var btnDownloadSeason: Button
    private lateinit var btnResume: Button

    private var appBarLayout: AppBarLayout? = null
    private var tabLayout: TabLayout? = null
    private var bottomNavigation: BottomNavigationView? = null // MENU DE RODAPÉ

    // VIEWS DE SUGESTÕES E DETALHES
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

        // ✅ RECUPERA O NOME DO PERFIL
        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        // MODO IMERSIVO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        seriesId = intent.getIntExtra("series_id", 0)
        seriesName = intent.getStringExtra("name") ?: ""
        seriesIcon = intent.getStringExtra("icon")
        seriesRating = intent.getStringExtra("rating") ?: "0.0"

        inicializarViews()
        setupBottomNavigation() // CONFIGURA O MENU
        verificarTecnologias(seriesName)

        // EFEITO DE ALPHA NO SCROLL
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
            // btnDownloadEpisodeArea.visibility = View.GONE // REMOVIDO POIS A VIEW NÃO EXISTE MAIS
            btnDownloadSeason.visibility = View.GONE
            bottomNavigation?.visibility = View.GONE // Esconde menu na TV
        }

        tvTitle.text = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text = "Gênero: Buscando..."
        // tvCast.text = "Elenco:" // Pode falhar se o tvCast for null, deixei quieto
        tvPlot.text = "Carregando sinopse..."

        // Cor do seletor ajustada para o XML novo
        // btnSeasonSelector.setBackgroundColor(Color.parseColor("#333333")) // REMOVIDO PARA USAR O DESIGN DO XML

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        rvEpisodes.isNestedScrollingEnabled = false // IMPORTANTE PARA O SCROLLVIEW
        
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

        // SUGESTÕES
        recyclerSuggestions.layoutManager = GridLayoutManager(this, 3)
        recyclerSuggestions.setHasFixedSize(true)
        recyclerSuggestions.isNestedScrollingEnabled = false

        // ELENCO
        recyclerCast.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)

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
            val epEncontrado = encontrarEpisodioParaAssistir()
            if (epEncontrado != null) {
                abrirPlayer(epEncontrado, false)
            } else {
                Toast.makeText(this, "Nenhum episódio encontrado.", Toast.LENGTH_SHORT).show()
            }
        }

        btnResume.setOnClickListener {
            val epParaContinuar = encontrarEpisodioParaContinuar()
            if (epParaContinuar != null) {
                abrirPlayer(epParaContinuar, true)
            }
        }

        restaurarEstadoDownload()
        setupDownloadButtons()
        tentarCarregarLogoCache()
        carregarSeriesInfo()
        sincronizarDadosTMDB()

        // FOCO NOS BOTÕES
        val commonFocus = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                if (v is Button) v.setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                if (v is Button) {
                    v.setBackgroundResource(android.R.drawable.btn_default)
                    v.setTextColor(Color.BLACK) // AJUSTADO PARA O BOTÃO BRANCO
                } else {
                    v.setBackgroundResource(0)
                }
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlaySeries.onFocusChangeListener = commonFocus
        btnResume.onFocusChangeListener = commonFocus
        
        btnSeasonSelector.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                (v as TextView).setTextColor(Color.YELLOW)
            } else {
                v.setBackgroundResource(R.drawable.bg_search_rounded) // AJUSTADO PARA O XML NOVO
                v.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
                (v as TextView).setTextColor(Color.WHITE)
            }
        }

        // LÓGICA DE TROCA DE ABAS
        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // EPISÓDIOS
                        rvEpisodes.visibility = View.VISIBLE
                        findViewById<View>(R.id.seasonHeaderContainer)?.visibility = View.VISIBLE // MOSTRA SELETOR
                        tvPlot.visibility = View.VISIBLE // No Disney a sinopse fica em cima, visivel
                        // tvCast.visibility = View.GONE
                        recyclerCast.visibility = View.GONE
                        // tvReleaseDate.visibility = View.GONE
                        // tvCreatedBy.visibility = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                    }
                    1 -> { // SUGESTÕES
                        rvEpisodes.visibility = View.GONE
                        findViewById<View>(R.id.seasonHeaderContainer)?.visibility = View.GONE
                        tvPlot.visibility = View.GONE
                        // tvCast.visibility = View.GONE
                        recyclerCast.visibility = View.GONE
                        // tvReleaseDate.visibility = View.GONE
                        // tvCreatedBy.visibility = View.GONE
                        recyclerSuggestions.visibility = View.VISIBLE
                    }
                    2 -> { // DETALHES
                        rvEpisodes.visibility = View.GONE
                        findViewById<View>(R.id.seasonHeaderContainer)?.visibility = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                        
                        tvPlot.visibility = View.VISIBLE
                        // tvCast.visibility = View.VISIBLE
                        recyclerCast.visibility = View.VISIBLE
                        tvReleaseDate.visibility = View.VISIBLE
                        tvCreatedBy.visibility = View.VISIBLE
                        
                        tvPlot.setTextColor(Color.WHITE)
                        // tvCast.setTextColor(Color.WHITE)
                        tvReleaseDate.setTextColor(Color.WHITE)
                        tvCreatedBy.setTextColor(Color.WHITE)
                        
                        tabLayout?.requestFocus()
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
        bottomNavigation = findViewById(R.id.bottomNavigation) // MENU RODAPÉ
        
        if (tabLayout?.tabCount == 0) {
            tabLayout?.addTab(tabLayout!!.newTab().setText("EPISÓDIOS"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("SUGESTÕES"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("DETALHES"))
        }

        imgPoster = findViewById(R.id.imgPoster) // Está gone no xml, mas existe
        imgBackground = try { findViewById(R.id.imgBackground) } catch (e: Exception) { imgPoster }
        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.visibility = View.INVISIBLE
        imgTitleLogo = findViewById(R.id.imgTitleLogo)
        tvRating = findViewById(R.id.tvRating)
        tvGenre = findViewById(R.id.tvGenre)
        
        llTechBadges = findViewById(R.id.llTechBadges)
        tvBadge4k = findViewById(R.id.tvBadge4k)
        tvBadgeHdr = findViewById(R.id.tvBadgeHdr)
        tvBadgeDolby = findViewById(R.id.tvBadgeDolby)
        tvBadge51 = findViewById(R.id.tvBadge51)
        
        tvPlot = findViewById(R.id.tvPlot)
        
        tvReleaseDate = findViewById(R.id.tvReleaseDate)
        tvCreatedBy = findViewById(R.id.tvCreatedBy)

        tvCast = findViewById(R.id.tvCast)
        recyclerCast = findViewById(R.id.recyclerCast)
        recyclerCast.visibility = View.GONE 
        
        recyclerSuggestions = findViewById(R.id.recyclerSuggestions)
        
        btnSeasonSelector = findViewById(R.id.btnSeasonSelector)
        rvEpisodes = findViewById(R.id.recyclerEpisodes)
        btnPlaySeries = findViewById(R.id.btnPlay)
        btnFavoriteSeries = findViewById(R.id.btnFavorite)
        btnResume = findViewById(R.id.btnResume)
        
        // --- COMENTADO PARA NÃO CRASHAR O APP ---
        // btnDownloadEpisodeArea = findViewById(R.id.btnDownloadArea)
        // imgDownloadEpisodeState = findViewById(R.id.imgDownloadState)
        // tvDownloadEpisodeState = findViewById(R.id.tvDownloadState)
        
        btnDownloadSeason = findViewById(R.id.btnDownloadSeason)
    }

    // ADICIONADO: CONFIGURAÇÃO DO MENU
    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Voltar para Home (limpando a pilha se necessário)
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    true
                }
                R.id.nav_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
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
        // --- COMENTADO PARA NÃO DAR CRASH ---
        /*
        btnDownloadEpisodeArea.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            when (downloadState) {
                DownloadState.BAIXAR -> {
                    val eid = ep.id.toIntOrNull() ?: 0
                    if (eid == 0) return@setOnClickListener
                    
                    val url = montarUrlEpisodio(ep)
                    val nomeEp = "T${currentSeason}E${ep.episode_num}"
                    
                    DownloadHelper.iniciarDownload(
                        context = this,
                        url = url,
                        streamId = eid,
                        nomePrincipal = seriesName,
                        nomeEpisodio = nomeEp,
                        imagemUrl = seriesIcon,
                        isSeries = true
                    )
                    
                    Toast.makeText(this, "Adicionado aos Downloads!", Toast.LENGTH_SHORT).show()
                    setDownloadState(DownloadState.BAIXANDO, ep)
                }
                DownloadState.BAIXANDO -> {
                     Toast.makeText(this, "Já está baixando...", Toast.LENGTH_SHORT).show()
                }
                DownloadState.BAIXADO -> {
                     Toast.makeText(this, "Download concluído!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        */

        btnDownloadSeason.setOnClickListener {
            if (currentSeason.isBlank()) return@setOnClickListener
            val lista = episodesBySeason[currentSeason] ?: emptyList()
            if (lista.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Baixar Temporada")
                .setMessage("Baixar todos os ${lista.size} episódios?")
                .setPositiveButton("Sim") { _, _ -> baixarTemporadaAtual(lista) }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        var cleanName = seriesName
        
        // VASSOURA: Limpeza do nome para busca e display
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        val lixo = listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
        lixo.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

        val encodedName = try { URLEncoder.encode(cleanName, "UTF-8") } catch(e:Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encodedName&language=pt-BR&region=BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val show = results.getJSONObject(0)
                            val tmdbId = show.getInt("id")
                            
                            buscarLogoSerieTraduzida(tmdbId, apiKey, cleanName)
                            
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
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
                    }
                }
            }
        })
    }

    private fun buscarLogoSerieTraduzida(id: Int, key: String, nomeLimpo: String) {
        val imagesUrl = "https://api.themoviedb.org/3/tv/$id/images?api_key=$key&include_image_language=pt,en,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
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
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                    }
                }
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
            }
        })
    }

    private fun buscarDetalhesTMDB(id: Int, key: String) {
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits,recommendations&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: return
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

                    val similar = d.optJSONObject("recommendations")
                    val similarResults = similar?.optJSONArray("results")
                    val sugestoesList = ArrayList<JSONObject>()
                    if (similarResults != null) {
                        for (i in 0 until similarResults.length()) {
                            sugestoesList.add(similarResults.getJSONObject(i))
                        }
                    }

                    runOnUiThread {
                        tvGenre.text = if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")
                        // tvCast.text = "Elenco: ${castNames.joinToString(", ")}"
                        
                        // Preencher Data e Criador e garantir visibilidade
                        if (firstAirDate.isNotEmpty()) {
                            val ano = firstAirDate.split("-")[0]
                            tvReleaseDate.text = ano
                            tvReleaseDate.visibility = View.VISIBLE
                        }
                        if (creatorsList.isNotEmpty()) {
                            tvCreatedBy.text = "Criado por: ${creatorsList.joinToString(", ")}"
                            tvCreatedBy.visibility = View.VISIBLE
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
        
        // ✅ CORREÇÃO: Garante que o botão continuar apareça imediatamente ao voltar
        verificarResume()
    }

    private fun isTelevisionDevice() = packageManager.hasSystemFeature("android.software.leanback") || packageManager.hasSystemFeature("android.hardware.type.television")

    // ✅ FAVORITOS ISOLADOS POR PERFIL
    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        // Usa a chave baseada no perfil atual
        val key = "${currentProfile}_fav_series"
        val set = prefs.getStringSet(key, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun saveFavSeries(context: Context, ids: Set<Int>) {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        // Usa a chave baseada no perfil atual
        val key = "${currentProfile}_fav_series"
        prefs.edit().putStringSet(key, ids.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavoritoSerie(isFav: Boolean) {
        if (isFav) {
            btnFavoriteSeries.setImageResource(android.R.drawable.ic_menu_delete) // TROQUEI PARA UM ÍCONE QUE INDICA REMOVER
            btnFavoriteSeries.alpha = 1.0f
        } else {
            btnFavoriteSeries.setImageResource(android.R.drawable.ic_input_add)
            btnFavoriteSeries.alpha = 1.0f
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
                        if (sortedSeasons.isNotEmpty()) {
                            mudarTemporada(sortedSeasons.first())
                            // ✅ CORREÇÃO: Verifica se há algo para continuar assim que carrega
                            verificarResume()
                        }
                        else {
                            btnSeasonSelector.text = "Indisponível"
                            btnSeasonSelector.setTextColor(Color.WHITE)
                        }
                    }
                }
                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    Toast.makeText(this@SeriesDetailsActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ✅ FUNÇÃO CORRIGIDA PARA A TEMPORADA 1 SEMPRE APARECER NO TOPO
    private fun mostrarSeletorDeTemporada() {
        if (sortedSeasons.isEmpty()) return

        val dialog = BottomSheetDialog(this, R.style.DialogTemporadaTransparente)
        
        val root = RelativeLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            500.toPx()
        )
        root.setBackgroundColor(Color.TRANSPARENT)

        val btnClose = ImageButton(this)
        btnClose.id = View.generateViewId()
        val closeParams = RelativeLayout.LayoutParams(65.toPx(), 65.toPx())
        closeParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        closeParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        closeParams.setMargins(0, 0, 0, 30.toPx())
        btnClose.layoutParams = closeParams
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnClose.setColorFilter(Color.WHITE)
        btnClose.background = null
        btnClose.scaleType = ImageView.ScaleType.FIT_CENTER
        btnClose.setPadding(10.toPx(), 10.toPx(), 10.toPx(), 10.toPx())
        btnClose.isFocusable = true
        btnClose.isClickable = true
        btnClose.setOnClickListener { dialog.dismiss() }

        btnClose.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                v.setBackgroundResource(0)
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }

        val rvSeasons = RecyclerView(this)
        val rvParams = RelativeLayout.LayoutParams(250.toPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
        
        // ✅ CORREÇÃO: Alinhado ao topo para garantir a Temporada 1 no topo da lista
        rvParams.addRule(RelativeLayout.ALIGN_PARENT_TOP) 
        rvParams.addRule(RelativeLayout.ABOVE, btnClose.id)
        rvParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        rvParams.setMargins(0, 10.toPx(), 0, 10.toPx())
        
        rvSeasons.layoutParams = rvParams
        rvSeasons.layoutManager = LinearLayoutManager(this)

        rvSeasons.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                tv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tv.setPadding(20, 35, 20, 35)
                tv.gravity = Gravity.CENTER
                tv.textSize = 22f
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
                        v.setBackgroundResource(R.drawable.bg_focus_neon)
                        (v as TextView).setTextColor(Color.YELLOW)
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    } else {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        (v as TextView).setTextColor(Color.WHITE)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }

                tv.setOnClickListener {
                    mudarTemporada(season)
                    dialog.dismiss()
                }
            }
            override fun getItemCount() = sortedSeasons.size
        }

        root.addView(btnClose)
        root.addView(rvSeasons)

        dialog.setContentView(root)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.peekHeight = 500.toPx() 
            it.setBackgroundColor(Color.TRANSPARENT)
        }

        dialog.show()
        
        rvSeasons.postDelayed({
            rvSeasons.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }, 150)
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun mudarTemporada(seasonKey: String) {
        currentSeason = seasonKey
        btnSeasonSelector.text = "Temporada $seasonKey ▼"
        
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            restaurarEstadoDownload()
            verificarResume() // ✅ Verifica resume com chave do perfil
        }
        
        // PASSA O ONCLICK CORRETO PARA O ADAPTER
        rvEpisodes.adapter = EpisodeAdapter(lista) { ep, _ ->
            currentEpisode = ep
            restaurarEstadoDownload()
            verificarResume()
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
        // ✅ Chave de resume isolada por perfil
        val keyResume = "${currentProfile}_series_resume_${streamId}_pos"
        val pos = prefs.getLong(keyResume, 0L)
        
        val existe = usarResume && pos > 30000L

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId)
        intent.putExtra("stream_ext", ext)
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", "T${currentSeason}E${ep.episode_num} - $seriesName")
        
        // ✅ PASSA O PERFIL PARA O PLAYER
        intent.putExtra("PROFILE_NAME", currentProfile)

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

    // ✅ CORREÇÃO: Função para encontrar o primeiro episódio ou o último assistido para o botão ASSISTIR
    private fun encontrarEpisodioParaAssistir(): EpisodeStream? {
        // Tenta encontrar se tem algum resume salvo em qualquer episódio da série
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                if (pos > 10000L) {
                    currentSeason = season
                    currentEpisode = ep
                    return ep
                }
            }
        }
        
        // Se não tem nada assistido, pega o Episódio 1 da Temporada 1
        if (sortedSeasons.isNotEmpty()) {
            val s1 = sortedSeasons.first()
            val eps = episodesBySeason[s1]
            if (!eps.isNullOrEmpty()) {
                currentSeason = s1
                currentEpisode = eps.first()
                return eps.first()
            }
        }
        return null
    }

    // ✅ CORREÇÃO: Função específica para o botão CONTINUAR
    private fun encontrarEpisodioParaContinuar(): EpisodeStream? {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                if (pos > 10000L) return ep
            }
        }
        return null
    }

    // ✅ RESUME ISOLADO POR PERFIL (Função auxiliar)
    private fun verificarResume() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        var temHistorico = false
        
        // Varre todas as temporadas e episódios desta série para ver se o perfil atual assistiu algo
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                if (pos > 10000L) {
                    temHistorico = true
                    break
                }
            }
            if (temHistorico) break
        }
        
        runOnUiThread {
            if (temHistorico) {
                btnPlaySeries.visibility = View.GONE
                btnResume.visibility = View.VISIBLE
            } else {
                btnPlaySeries.visibility = View.VISIBLE
                btnResume.visibility = View.GONE
            }
        }
    }

    private fun montarUrlEpisodio(ep: EpisodeStream): String {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        // ✅ CORREÇÃO CRÍTICA: Pegando o DNS real que está salvo no app (Dynamic DNS)
        val server = prefs.getString("dns", "") ?: ""
        val eid = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"
        return "$server/get.php?username=$user&password=$pass&type=series&output=$ext&id=$eid"
    }

    private fun baixarTemporadaAtual(lista: List<EpisodeStream>) {
        for (ep in lista) {
            val eid = ep.id.toIntOrNull() ?: continue
            val url = montarUrlEpisodio(ep)
            val nomeEp = "T${currentSeason}E${ep.episode_num}"
            
            // ✅ CORREÇÃO: Usa o helper novo
            DownloadHelper.iniciarDownload(
                context = this,
                url = url,
                streamId = eid,
                nomePrincipal = seriesName,
                nomeEpisodio = nomeEp,
                imagemUrl = seriesIcon,
                isSeries = true
            )
        }
        Toast.makeText(this, "Baixando episódios em segundo plano...", Toast.LENGTH_LONG).show()
    }

    // ✅ CORREÇÃO: Simplificado para não depender de SharedPreferences
    private fun getProgressText(): String {
        return "Baixando..." 
    }

    private fun setDownloadState(state: DownloadState, ep: EpisodeStream?) {
        // ESSA FUNÇÃO ATUALIZAVA A TELA ANTIGA. AGORA APENAS SALVA NO PREF,
        // POIS A TELA DEVE ATUALIZAR APENAS O ÍCONE NO RECYCLERVIEW
        downloadState = state
        val eid = ep?.id?.toIntOrNull() ?: 0
        if (eid != 0) getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().putString("series_download_state_$eid", state.name).apply()
        
        // COMENTADO PARA NÃO CRASHAR (ELEMENTOS VISUAIS REMOVIDOS)
        /*
        when (state) {
            DownloadState.BAIXAR -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_arrow); tvDownloadEpisodeState.text = "Baixar" }
            DownloadState.BAIXANDO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_loading); tvDownloadEpisodeState.text = getProgressText() }
            DownloadState.BAIXADO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_done); tvDownloadEpisodeState.text = "Baixado" }
        }
        */
    }

    private fun restaurarEstadoDownload() {
        val ep = currentEpisode ?: return
        val eid = ep.id.toIntOrNull() ?: 0
        val saved = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getString("series_download_state_$eid", DownloadState.BAIXAR.name)
        setDownloadState(DownloadState.valueOf(saved!!), ep)
    }

    override fun onDestroy() {
        client.dispatcher.cancelAll()
        super.onDestroy()
    }

    class EpisodeAdapter(val list: List<EpisodeStream>, private val onClick: (EpisodeStream, Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView = v.findViewById(R.id.imgEpisodeThumb)
            val tvPlotEp: TextView = v.findViewById(R.id.tvEpisodePlot)
            
            // Tenta achar o botão de download individual (se existir no item_episode.xml)
            val btnDownload: View? = try { v.findViewById(R.id.imgDownload) } catch(e:Exception) { null }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.tvTitle.text = "E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
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
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(200).start()
                    view.elevation = 15f
                } else {
                    view.setBackgroundResource(R.drawable.bg_episodio_item)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    view.elevation = 4f
                }
            }
            
            // SE O BOTÃO DE DOWNLOAD EXISTIR NO XML, CONFIGURA O CLIQUE DELE
            holder.btnDownload?.setOnClickListener {
                // LÓGICA DE DOWNLOAD INDIVIDUAL AQUI SE NECESSÁRIO
                // (Por enquanto o clique no item abre o player)
            }
        }

        override fun getItemCount() = list.size
    }

    inner class SuggestionsAdapter(val items: List<JSONObject>) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(android.R.id.icon) 
            val tvName: TextView = v.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val container = LinearLayout(parent.context)
            container.orientation = LinearLayout.VERTICAL
            val params = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(12, 12, 12, 12)
            container.layoutParams = params
            container.gravity = Gravity.CENTER_HORIZONTAL
            container.isFocusable = true
            container.isClickable = true

            val card = androidx.cardview.widget.CardView(parent.context)
            val cardParams = LinearLayout.LayoutParams(130.toPx(), 200.toPx())
            card.layoutParams = cardParams
            card.radius = 12f
            card.cardElevation = 4f
            
            val img = ImageView(parent.context)
            img.id = android.R.id.icon
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            card.addView(img)

            val tv = TextView(parent.context)
            tv.id = android.R.id.text1
            val tvParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tvParams.topMargin = 8
            tv.layoutParams = tvParams
            tv.setTextColor(Color.WHITE)
            tv.textSize = 12f
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            
            container.addView(card)
            container.addView(tv)
            
            return ViewHolder(container)
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

            holder.tvName.text = name

            holder.itemView.setOnClickListener {
                val intent = Intent(holder.itemView.context, SeriesDetailsActivity::class.java)
                intent.putExtra("series_id", id)
                intent.putExtra("name", name)
                intent.putExtra("icon", "https://image.tmdb.org/t/p/w342$posterPath")
                intent.putExtra("rating", rating.toString())
                // ✅ PASSA O PERFIL PARA A PRÓXIMA TELA
                intent.putExtra("PROFILE_NAME", currentProfile)
                holder.itemView.context.startActivity(intent)
            }
            
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if(hasFocus) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).start()
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
