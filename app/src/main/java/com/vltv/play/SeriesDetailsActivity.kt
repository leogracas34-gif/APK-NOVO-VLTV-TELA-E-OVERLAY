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
import java.util.concurrent.TimeUnit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Importante: Certifique-se de que CastAdapter e CastMember estão no projeto
import com.vltv.play.CastAdapter
import com.vltv.play.CastMember
import com.vltv.play.data.AppDatabase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    // ✅ VARIÁVEL PARA O PERFIL ATUAL (Isolamento de dados)
    private var currentProfile: String = "Padrao"
    private var youtubeTrailerKey: String? = null

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
    private lateinit var btnDownloadEpisodeArea: LinearLayout
    private lateinit var imgDownloadEpisodeState: ImageView
    private lateinit var tvDownloadEpisodeState: TextView

    private lateinit var btnDownloadSeason: Button
    private lateinit var btnResume: Button

    // NOVAS VIEWS (Estilo Disney+) - Adicionadas sem remover as antigas
    private var btnRestartAction: LinearLayout? = null
    private var btnTrailerAction: LinearLayout? = null
    private var btnFavoriteLayout: LinearLayout? = null
    
    // VIEWS DE PROGRESSO (Igual Filmes)
    private var layoutProgress: LinearLayout? = null
    private var progressBarMovie: ProgressBar? = null
    private var tvTimeRemaining: TextView? = null

    private var appBarLayout: AppBarLayout? = null
    private var tabLayout: TabLayout? = null

    // VIEWS DE SUGESTÕES E DETALHES
    private lateinit var recyclerSuggestions: RecyclerView
    private lateinit var llTechBadges: LinearLayout
    private lateinit var tvBadge4k: TextView
    private lateinit var tvBadgeHdr: TextView
    private lateinit var tvBadgeDolby: TextView
    private lateinit var tvBadge51: TextView
    // Novos Badges
    private var tvBadgeCC: TextView? = null
    private var tvBadgeAD: TextView? = null
    
    private lateinit var tvReleaseDate: TextView
    private lateinit var tvCreatedBy: TextView

    private var episodesBySeason: Map<String, List<EpisodeStream>> = emptyMap()
    private var sortedSeasons: List<String> = emptyList()
    private var currentSeason: String = ""
    private var currentEpisode: EpisodeStream? = null

    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    private var uiMonitorJob: Job? = null
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_details)

        // ✅ RECUPERA O NOME DO PERFIL (Para salvar favoritos na conta certa)
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
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility = View.GONE
        }

        tvTitle.text = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text = "Gênero: Buscando..."
        tvCast.text = "Elenco:"
        tvPlot.text = "Carregando sinopse..."

        // ✅ LÓGICA DO ARQUIVO ANTIGO: Cor de fundo #333333 e Texto Branco
        btnSeasonSelector.setBackgroundColor(Color.parseColor("#333333"))
        btnSeasonSelector.setTextColor(Color.WHITE)

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        
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

        // SUGESTÕES NA VERTICAL (GRID COM 3 COLUNAS)
        recyclerSuggestions.layoutManager = GridLayoutManager(this, 3)
        recyclerSuggestions.setHasFixedSize(true)

        val isFavInicial = getFavSeries(this).contains(seriesId)
        atualizarIconeFavoritoSerie(isFavInicial)

        btnFavoriteSeries.setOnClickListener {
            toggleFav()
        }
        
        // Novo listener para o layout de favorito (botão grande)
        btnFavoriteLayout?.setOnClickListener {
            toggleFav()
        }

        // CHAMA A FUNÇÃO TRAZIDA DO ARQUIVO ANTIGO
        btnSeasonSelector.setOnClickListener { mostrarSeletorDeTemporada() }

        // ✅ CORREÇÃO: Lógica de Play Inteligente (Sempre começa do 1 se não tiver histórico)
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
        
        // NOVOS LISTENERS (Reiniciar e Trailer)
        btnRestartAction?.setOnClickListener {
            val ep = encontrarEpisodioParaAssistir()
            if (ep != null) {
                AlertDialog.Builder(this)
                    .setTitle("Reiniciar")
                    .setMessage("Deseja assistir desde o início?")
                    .setPositiveButton("Sim") { _, _ -> abrirPlayer(ep, false) } // false força o início
                    .setNegativeButton("Não", null)
                    .show()
            }
        }

        btnTrailerAction?.setOnClickListener {
            if (youtubeTrailerKey != null) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$youtubeTrailerKey"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "Trailer indisponível no momento", Toast.LENGTH_SHORT).show()
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
                    v.setTextColor(Color.WHITE)
                } else {
                    v.setBackgroundResource(0)
                }
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlaySeries.onFocusChangeListener = commonFocus
        btnResume.onFocusChangeListener = commonFocus
        
        // ✅ LÓGICA DO ARQUIVO ANTIGO: Foco do Seletor (Amarelo no foco, Branco e Cinza normal)
        btnSeasonSelector.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                (v as TextView).setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                v.setBackgroundColor(Color.parseColor("#333333")) // Mantém cinza ao sair
                (v as TextView).setTextColor(Color.WHITE) // Garante branco
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }

        // LÓGICA DE TROCA DE ABAS
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
                    1 -> { // SUGESTÕES
                        rvEpisodes.visibility = View.GONE
                        tvPlot.visibility = View.GONE
                        tvCast.visibility = View.GONE
                        recyclerCast.visibility = View.GONE
                        tvReleaseDate.visibility = View.GONE
                        tvCreatedBy.visibility = View.GONE
                        recyclerSuggestions.visibility = View.VISIBLE
                    }
                    2 -> { // DETALHES
                        // ✅ CORREÇÃO: Garante visibilidade total das informações
                        rvEpisodes.visibility = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                        
                        tvPlot.visibility = View.VISIBLE
                        tvCast.visibility = View.VISIBLE
                        recyclerCast.visibility = View.VISIBLE
                        tvReleaseDate.visibility = View.VISIBLE
                        tvCreatedBy.visibility = View.VISIBLE
                        
                        tvPlot.setTextColor(Color.WHITE)
                        tvCast.setTextColor(Color.WHITE)
                        tvReleaseDate.setTextColor(Color.WHITE)
                        tvCreatedBy.setTextColor(Color.WHITE)
                        
                        // Foco na aba para não perder navegação
                        tabLayout?.requestFocus()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    // Função auxiliar para Favoritar (Evita código duplicado)
    private fun toggleFav() {
        val favs = getFavSeries(this)
        if (favs.contains(seriesId)) {
            favs.remove(seriesId)
        } else {
            favs.add(seriesId)
        }
        saveFavSeries(this, favs)
        atualizarIconeFavoritoSerie(favs.contains(seriesId))
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
        
        llTechBadges = findViewById(R.id.llTechBadges)
        tvBadge4k = findViewById(R.id.tvBadge4k)
        tvBadgeHdr = findViewById(R.id.tvBadgeHdr)
        tvBadgeDolby = findViewById(R.id.tvBadgeDolby)
        tvBadge51 = findViewById(R.id.tvBadge51)
        
        // Novos Badges Opcionais (Try/Catch para não quebrar se não estiver no XML ainda)
        try { tvBadgeCC = findViewById(R.id.tvBadgeCC) } catch (e: Exception) {}
        try { tvBadgeAD = findViewById(R.id.tvBadgeAD) } catch (e: Exception) {}
        
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
        
        // Inicialização dos Novos Botões (Com segurança)
        try { btnRestartAction = findViewById(R.id.btnRestartAction) } catch(e: Exception) {}
        try { btnTrailerAction = findViewById(R.id.btnTrailerAction) } catch(e: Exception) {}
        try { btnFavoriteLayout = findViewById(R.id.btnFavoriteLayout) } catch(e: Exception) {}
        
        try { layoutProgress = findViewById(R.id.layoutProgress) } catch(e: Exception) {}
        try { progressBarMovie = findViewById(R.id.progressBarMovie) } catch(e: Exception) {}
        try { tvTimeRemaining = findViewById(R.id.tvTimeRemaining) } catch(e: Exception) {}

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
        
        // Ativa os novos se existirem
        tvBadgeCC?.visibility = View.VISIBLE
        tvBadgeAD?.visibility = View.VISIBLE
        
        llTechBadges.visibility = if (temBadge) View.VISIBLE else View.VISIBLE // Força visível para CC/AD
    }

    private fun setupDownloadButtons() {
        btnDownloadEpisodeArea.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            // ✅ CORREÇÃO: Lógica segura de download
            when (downloadState) {
                DownloadState.BAIXAR -> {
                    val eid = ep.id.toIntOrNull() ?: 0
                    if (eid == 0) return@setOnClickListener
                    
                    val url = montarUrlEpisodio(ep)
                    val nomeEp = "T${currentSeason}E${ep.episode_num}"
                    
                    // Chama o novo helper seguro
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
                    iniciarMonitoramentoUI(eid) // Inicia monitoramento visual
                }
                DownloadState.BAIXANDO -> {
                    // Aqui futuramente abriremos a Activity de Downloads
                     Toast.makeText(this, "Já está baixando...", Toast.LENGTH_SHORT).show()
                }
                DownloadState.BAIXADO -> {
                     Toast.makeText(this, "Download concluído!", Toast.LENGTH_SHORT).show()
                }
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
    
    // Novo monitoramento visual (Para não travar em 0%)
    private fun iniciarMonitoramentoUI(streamIdEp: Int) {
        uiMonitorJob?.cancel()
        uiMonitorJob = CoroutineScope(Dispatchers.Main).launch {
            val db = AppDatabase.getDatabase(applicationContext).streamDao()
            while (isActive) {
                val download = withContext(Dispatchers.IO) {
                    db.getDownloadByStreamId(streamIdEp, "series")
                }
                if (download != null) {
                    runOnUiThread {
                        tvDownloadEpisodeState.text = "${download.progress}%"
                        if (download.status == "COMPLETED" || download.status == "BAIXADO") {
                            setDownloadState(DownloadState.BAIXADO, currentEpisode)
                            this@launch.cancel()
                        }
                    }
                }
                delay(1500)
            }
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
                // ✅ CORREÇÃO: Usa o nome limpo em caso de erro
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
                            
                            // Passa o nome limpo para a função de logo
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
                            // ✅ CORREÇÃO: Usa o nome limpo se não achar nada
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
                            // ✅ CORREÇÃO: Usa o nome limpo se não achar logo
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
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits,recommendations,videos&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: return
                try {
                    val d = JSONObject(body)
                    
                    // Lógica para pegar o Trailer (NOVO)
                    val videos = d.optJSONObject("videos")?.optJSONArray("results")
                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                                youtubeTrailerKey = v.getString("key")
                                break
                            }
                        }
                    }
                    
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
                        tvGenre.text = "Gênero: ${if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")}"
                        tvCast.text = "Elenco: ${castNames.joinToString(", ")}"
                        
                        // Preencher Data e Criador e garantir visibilidade
                        if (firstAirDate.isNotEmpty()) {
                            val ano = firstAirDate.split("-")[0]
                            tvReleaseDate.text = "Lançamento: $ano"
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
        btnSeasonSelector.setTextColor(Color.WHITE)
        
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            restaurarEstadoDownload()
            verificarResume() // ✅ Verifica resume com chave do perfil
        }
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
        var maxPos = 0L
        var maxDur = 0L
        
        // Varre todas as temporadas e episódios desta série para ver se o perfil atual assistiu algo
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                val dur = prefs.getLong("${currentProfile}_series_resume_${sid}_dur", 0L)
                if (pos > 10000L) {
                    temHistorico = true
                    maxPos = pos
                    maxDur = dur
                    break
                }
            }
            if (temHistorico) break
        }
        
        runOnUiThread {
            if (temHistorico && maxDur > 0) {
                btnPlaySeries.text = "▶  CONTINUAR"
                btnResume.visibility = View.VISIBLE
                btnRestartAction?.visibility = View.VISIBLE
                layoutProgress?.visibility = View.VISIBLE
                
                // Calcula barra azul
                val pct = ((maxPos.toFloat() / maxDur.toFloat()) * 100).toInt()
                progressBarMovie?.progress = pct
                
                // Calcula texto "Restam X min"
                val restMs = maxDur - maxPos
                val min = TimeUnit.MILLISECONDS.toMinutes(restMs)
                tvTimeRemaining?.text = "Restam ${min}min"
            } else {
                btnPlaySeries.text = "▶  ASSISTIR"
                btnResume.visibility = View.GONE
                btnRestartAction?.visibility = View.GONE
                layoutProgress?.visibility = View.GONE
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

    override fun onDestroy() {
        client.dispatcher.cancelAll()
        uiMonitorJob?.cancel()
        super.onDestroy()
    }

    class EpisodeAdapter(val list: List<EpisodeStream>, private val onClick: (EpisodeStream, Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView = v.findViewById(R.id.imgEpisodeThumb)
            val tvPlotEp: TextView = v.findViewById(R.id.tvEpisodePlot)
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
                    view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(200).start()
                    view.elevation = 15f
                } else {
                    view.setBackgroundResource(R.drawable.bg_episodio_item)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    view.elevation = 4f
                }
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
