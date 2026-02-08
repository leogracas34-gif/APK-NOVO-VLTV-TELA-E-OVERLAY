package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.random.Random

// ✅ IMPORTAÇÕES CAST
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

// ✅ FIREBASE
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    
    // ✅ VARIÁVEL DE PERFIL
    private var currentProfile: String = "Padrao"

    // ✅ INSTÂNCIA DO BANCO DE DADOS ROOM
    private val database by lazy { AppDatabase.getDatabase(this) }

    // --- VARIÁVEIS DO BANNER ---
    private var listaBannerItems: List<Any> = emptyList()
    private var bannerJob: Job? = null
    private var currentBannerIndex = 0
    private lateinit var bannerAdapter: BannerAdapter 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 DETECÇÃO MELHORADA: CELULAR vs TV
        configurarOrientacaoAutomatica()
        
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ RECUPERA O PERFIL
        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        DownloadHelper.registerReceiver(this)

        // ✅ SETUP CAST BUTTON (PROTEGIDO)
        try {
             CastContext.getSharedInstance(this)
            binding.mediaRouteButton?.let { btn ->
                CastButtonFactory.setUpMediaRouteButton(applicationContext, btn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ✅ INICIALIZA O LAYOUT
        setupViewPagerBanner()
        setupBottomNavigation()

        setupClicks() 
        setupFirebaseRemoteConfig()
        
        // ✅ CARREGAMENTO EM PARALELO PARA VELOCIDADE
        carregarDadosLocaisImediato()
        sincronizarConteudoSilenciosamente()
        carregarListasDaHome()

        // ✅ LÓGICA KIDS
        val isKidsMode = intent.getBooleanExtra("IS_KIDS_MODE", false)
        if (isKidsMode) {
            currentProfile = "Kids"
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    binding.cardKids.performClick()
                    Toast.makeText(this, "Modo Kids Ativado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }, 500)
        }
    }

    private fun configurarOrientacaoAutomatica() {
        if (isTVDevice()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun isTVDevice(): Boolean {
        return try {
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packageManager.hasSystemFeature("android.hardware.type.television") ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == 
            Configuration.UI_MODE_TYPE_TELEVISION ||
            isRealTVSize()
        } catch (e: Exception) {
            false
        }
    }

    private fun isRealTVSize(): Boolean {
        return try {
            val displayMetrics = resources.displayMetrics
            val widthDp = displayMetrics.widthPixels / displayMetrics.density
            val isLargeWidth = widthDp > 600
            val isLowDensity = displayMetrics.densityDpi < DisplayMetrics.DENSITY_XHIGH
            val isTVSize = isLargeWidth && isLowDensity
            isTVSize
        } catch (e: Exception) {
            false
        }
    }

    // ✅ CONFIGURAÇÃO DO CARROSSEL (Visual Disney 3D + Profundidade + Loop Infinito)
    private fun setupViewPagerBanner() {
        bannerAdapter = BannerAdapter(emptyList())
        binding.bannerViewPager?.adapter = bannerAdapter
        // Mantém 3 páginas na memória para não recarregar
        binding.bannerViewPager?.offscreenPageLimit = 3
        
        val compositePageTransformer = CompositePageTransformer()
        // Margem de 40dp entre os itens
        compositePageTransformer.addTransformer(MarginPageTransformer(40))
        // Efeito de escala (Zoom no centro)
        compositePageTransformer.addTransformer { page, position ->
            val r = 1 - abs(position)
            page.scaleY = 0.85f + r * 0.15f 
        }
        binding.bannerViewPager?.setPageTransformer(compositePageTransformer)

        binding.bannerViewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentBannerIndex = position
                bannerJob?.cancel()
                iniciarCicloBanner()
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    false 
                }
                // ✅ BOTÃO DOWNLOADS (Igual Disney+)
                R.id.nav_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    false
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }
    }

    // ✅ CARREGA DADOS DO DATABASE IMEDIATAMENTE + ATIVA MODO SUPERSONICO
    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Pega do banco local (Room)
                val localMovies = database.streamDao().getRecentVods(20)
                // Mapeia usando stream_icon
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                // Mapeia usando cover (CORREÇÃO CRÍTICA)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        binding.rvRecentlyAdded.adapter = HomeRowAdapter(movieItems) { selectedItem ->
                            val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", false)
                            startActivity(intent)
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        binding.rvRecentSeries.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", true)
                            startActivity(intent)
                        }
                    }
                    
                    prepararBannerDosRecentes(localMovies, localSeries)
                    
                    // 🚀 ATIVA O MODO SUPERSONICO (PRELOAD DAS LISTAS INFERIORES)
                    ativarModoSupersonico(movieItems, seriesItems)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 🚀 MODO VELOCIDADE DA LUZ: Baixa as imagens das listas inferiores silenciosamente
    private fun ativarModoSupersonico(filmes: List<VodItem>, series: List<VodItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            // Pega os primeiros 15 filmes e 15 séries para deixar na memória RAM
            val preloadList = filmes.take(15) + series.take(15)
            
            for (item in preloadList) {
                try {
                    // O Glide baixa a imagem do DATABASE para a memória antes de você ver
                    if (!item.streamIcon.isNullOrEmpty()) {
                        Glide.with(applicationContext)
                            .load(item.streamIcon) 
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .preload(180, 270) 
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun prepararBannerDosRecentes(filmes: List<VodEntity>, series: List<SeriesEntity>) {
        // Mistura filmes e séries para o banner
        val mixLançamentos = (filmes + series).shuffled().take(10)
        
        if (mixLançamentos.isNotEmpty()) {
            listaBannerItems = mixLançamentos
            bannerAdapter.updateList(listaBannerItems)
            
            // 🔥 POSICIONA NO MEIO PARA PERMITIR SCROLL ESQUERDA/DIREITA INFINITO
            val middle = Integer.MAX_VALUE / 2
            val startPos = middle - (middle % listaBannerItems.size)
            binding.bannerViewPager?.setCurrentItem(startPos, false)
            
            iniciarCicloBanner()
        } else {
            // Se o banco estiver vazio, tenta o online
            carregarBannerAlternado() 
        }
    }

    private fun iniciarCicloBanner() {
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            while (true) {
                delay(8000) // 8 segundos por banner
                if (listaBannerItems.isNotEmpty()) {
                    // 🔥 Só avança para a próxima posição (sempre para frente no infinito)
                    val nextIndex = (binding.bannerViewPager?.currentItem ?: 0) + 1
                    binding.bannerViewPager?.setCurrentItem(nextIndex, true)
                } else {
                    break
                }
            }
        }
    }

    private fun limparNomeParaTMDB(nome: String): String {
        return nome.replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b"), "")
                   .replace(Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)"), "")
                   .replace(Regex("\\s+"), " ")
                   .trim()
                   .take(50)
    }

    // ✅ LÓGICA HÍBRIDA: DATABASE PRIMEIRO (INSTANTÂNEO), DEPOIS TMDB
    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String, internalId: Int, targetImg: ImageView, targetLogo: ImageView, targetTitle: TextView) {
        
        // 🚀 1. CARREGAMENTO INSTANTÂNEO (Igual ao VodActivity/SeriesActivity)
        try {
            Glide.with(this@HomeActivity)
                .load(fallback) // Carrega do stream_icon ou cover
                .centerCrop()
                .placeholder(R.drawable.bg_gradient_black)
                .format(DecodeFormat.PREFER_RGB_565) // Formato mais rápido
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(targetImg)
        } catch (e: Exception) {}

        // 🚀 2. BUSCA MELHORIA NO TMDB (EM SEGUNDO PLANO)
        val tipo = if (isSeries) "tv" else "movie"
        val nomeLimpo = limparNomeParaTMDB(nome)
        val query = URLEncoder.encode(nomeLimpo, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(url).readText()
                val results = JSONObject(response).getJSONArray("results")
                if (results.length() > 0) {
                    val backdropPath = results.getJSONObject(0).optString("backdrop_path")
                    val tmdbId = results.getJSONObject(0).optString("id")
                    
                    withContext(Dispatchers.Main) {
                        try {
                            // Só troca a imagem se o TMDB tiver uma capa de fundo (backdrop) válida
                            if (backdropPath != "null" && backdropPath.isNotEmpty()) {
                                Glide.with(this@HomeActivity)
                                    .load("https://image.tmdb.org/t/p/original$backdropPath")
                                    .centerCrop()
                                    .placeholder(targetImg.drawable) // Usa a imagem atual para não piscar
                                    .into(targetImg)
                            }
                        } catch (e: Exception) {}
                    }
                    
                    buscarLogoOverlayHome(tmdbId, tipo, internalId, isSeries, targetLogo, targetTitle)
                }
            } catch (e: Exception) {
                // Se falhar, não faz nada (já temos a imagem do banco)
            }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, internalId: Int, isSeries: Boolean, targetLogo: ImageView, targetTitle: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ✅ CORREÇÃO: Pede APENAS Português na URL.
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt"
                
                val imagesJson = URL(imagesUrl).readText()
                val imagesObj = JSONObject(imagesJson)

                // ✅ CORREÇÃO: Só entra se tiver logos (e como filtramos, se tiver, é PT).
                if (imagesObj.has("logos") && imagesObj.getJSONArray("logos").length() > 0) {
                    val logos = imagesObj.getJSONArray("logos")
                    
                    // Pega direto a primeira (Garantido ser PT)
                    val logoPath = logos.getJSONObject(0).getString("file_path")
                    val fullLogoUrl = "https://image.tmdb.org/t/p/w500$logoPath"

                    // Salva no banco (Lógica mantida)
                    try {
                        if (isSeries) {
                            database.streamDao().updateSeriesLogo(internalId, fullLogoUrl)
                        } else {
                            database.streamDao().updateVodLogo(internalId, fullLogoUrl)
                        }
                    } catch(e: Exception) {}

                    // Mostra na tela (Lógica mantida)
                    withContext(Dispatchers.Main) {
                        targetTitle.visibility = View.GONE
                        targetLogo.visibility = View.VISIBLE
                        try {
                            Glide.with(this@HomeActivity)
                                .load(fullLogoUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .into(targetLogo)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ SINCRONIZAÇÃO OTIMIZADA: Salva em Lotes (Chunking) para evitar tela preta
    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // --- 1. FILMES (Salva a cada 50) ---
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val vodResponse = URL(vodUrl).readText()
                val vodArray = org.json.JSONArray(vodResponse)
                val vodBatch = mutableListOf<VodEntity>()
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")
                var firstVodBatchLoaded = false

                for (i in 0 until vodArray.length()) {
                    val obj = vodArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                        vodBatch.add(VodEntity(
                            stream_id = obj.optInt("stream_id"),
                            name = nome,
                            title = obj.optString("name"),
                            stream_icon = obj.optString("stream_icon"),
                            container_extension = obj.optString("container_extension"),
                            rating = obj.optString("rating"),
                            category_id = obj.optString("category_id"),
                            added = obj.optLong("added")
                        ))
                    }
                    
                    // ⚡ TRUQUE: A cada 50 filmes, salva e atualiza a tela
                    if (vodBatch.size >= 50) {
                        database.streamDao().insertVodStreams(vodBatch)
                        vodBatch.clear()
                        
                        // Só chama a atualização de tela no PRIMEIRO lote para ser instantâneo
                        if (!firstVodBatchLoaded) {
                            withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
                            firstVodBatchLoaded = true
                        }
                    }
                }
                // Salva o restante
                if (vodBatch.isNotEmpty()) {
                    database.streamDao().insertVodStreams(vodBatch)
                }
                // Atualiza tela ao final dos filmes
                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }

                // --- 2. SÉRIES (Salva a cada 50) ---
                val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                val seriesResponse = URL(seriesUrl).readText()
                val seriesArray = org.json.JSONArray(seriesResponse)
                val seriesBatch = mutableListOf<SeriesEntity>()
                var firstSeriesBatchLoaded = false

                for (i in 0 until seriesArray.length()) {
                    val obj = seriesArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                        seriesBatch.add(SeriesEntity(
                            series_id = obj.optInt("series_id"),
                            name = nome,
                            cover = obj.optString("cover"),
                            rating = obj.optString("rating"),
                            category_id = obj.optString("category_id"),
                            last_modified = obj.optLong("last_modified")
                        ))
                    }

                    // ⚡ TRUQUE: A cada 50 séries, salva
                    if (seriesBatch.size >= 50) {
                        database.streamDao().insertSeriesStreams(seriesBatch)
                        seriesBatch.clear()
                        
                        // Atualiza a tela rápido se for o primeiro lote
                        if (!firstSeriesBatchLoaded) {
                            withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
                            firstSeriesBatchLoaded = true
                        }
                    }
                }
                if (seriesBatch.isNotEmpty()) {
                    database.streamDao().insertSeriesStreams(seriesBatch)
                }
                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }

                // --- 3. LIVE (Canais) ---
                val liveUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_live_streams"
                val liveResponse = URL(liveUrl).readText()
                val liveArray = org.json.JSONArray(liveResponse)
                val liveBatch = mutableListOf<LiveStreamEntity>()

                for (i in 0 until liveArray.length()) {
                    val obj = liveArray.getJSONObject(i)
                    liveBatch.add(LiveStreamEntity(
                        stream_id = obj.optInt("stream_id"),
                        name = obj.optString("name"),
                        stream_icon = obj.optString("stream_icon"),
                        epg_channel_id = obj.optString("epg_channel_id"),
                        category_id = obj.optString("category_id")
                    ))
                    
                    if (liveBatch.size >= 100) {
                        database.streamDao().insertLiveStreams(liveBatch)
                        liveBatch.clear()
                    }
                }
                if (liveBatch.isNotEmpty()) {
                    database.streamDao().insertLiveStreams(liveBatch)
                }

                withContext(Dispatchers.Main) {
                    carregarDadosLocaisImediato()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { 
            minimumFetchIntervalInSeconds = 60 
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                // Configuração remota carregada
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (listaBannerItems.isEmpty()) {
            carregarBannerAlternado()
        }
        
        carregarContinuarAssistindoLocal()

        try {
             // Try catch original mantido
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupClicks() {
        fun isTelevisionDevice(): Boolean {
            return packageManager.hasSystemFeature("android.hardware.type.television") ||
                   packageManager.hasSystemFeature("android.software.leanback") ||
                   (resources.configuration.uiMode and
                   Configuration.UI_MODE_TYPE_MASK) ==
                   Configuration.UI_MODE_TYPE_TELEVISION
        }

        // --- Configuração dos cliques dos cards de Categoria ---
        val cards = listOf(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardKids)
        
        cards.forEach { card ->
            card.isFocusable = true
            card.isClickable = true
            
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                } else {
                    card.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            
            card.setOnClickListener {
                when (card.id) {
                    R.id.cardLiveTv -> {
                        val intent = Intent(this, LiveTvActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", true)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        startActivity(intent)
                    }
                    R.id.cardMovies -> {
                        val intent = Intent(this, VodActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        startActivity(intent)
                    }
                    R.id.cardSeries -> {
                        val intent = Intent(this, SeriesActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        startActivity(intent)
                    }
                    R.id.cardKids -> {
                        val intent = Intent(this, KidsActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", "Kids")
                        startActivity(intent)
                    }
                }
            }
        }
        
        // --- Lógica de Navegação TV (D-PAD) ---
        if (isTelevisionDevice()) {
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus() 
                    true
                } else false
            }
            
            binding.cardMovies.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardLiveTv.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus()
                    true
                } else false
            }
            
            binding.cardSeries.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardKids.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus()
                    true
                } else false
            }

            binding.cardKids.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus()
                    true
                } else false
            }
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair e desconectar?")
            .setPositiveButton("Sim") { _, _ ->
                val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()

                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Não", null)
            .show()
    }

    // ✅ FUNÇÃO RECUPERADA (Trending TMDB caso banco vazio)
    private fun carregarBannerAlternado() {
        val prefs = getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE)
        val ultimoTipo = prefs.getString("ultimo_tipo_banner", "tv") ?: "tv"
        val tipoAtual = if (ultimoTipo == "tv") "movie" else "tv"
        prefs.edit().putString("ultimo_tipo_banner", tipoAtual).apply()

        val urlString = "https://api.themoviedb.org/3/trending/$tipoAtual/day?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonTxt = URL(urlString).readText()
                val json = JSONObject(jsonTxt)
                val results = json.getJSONArray("results")

                if (results.length() > 0) {
                    val randomIndex = Random.nextInt(results.length())
                    val item = results.getJSONObject(randomIndex)

                    val tituloOriginal = if (item.has("title")) item.getString("title")
                    else if (item.has("name")) item.getString("name")
                    else "Destaque"

                    val overview = if (item.has("overview")) item.getString("overview") else ""
                    val backdropPath = item.getString("backdrop_path")
                    val prefixo = if (tipoAtual == "movie") "Filme em Alta: " else "Série em Alta: "
                    val tmdbId = item.getString("id")

                    if (backdropPath != "null" && backdropPath.isNotBlank()) {
                        val imageUrl = "https://image.tmdb.org/t/p/original$backdropPath"
                        withContext(Dispatchers.Main) {
                            try {
                                Glide.with(this@HomeActivity)
                                    .load(imageUrl)
                                    .centerCrop()
                                    .into(binding.root.findViewById<ImageView>(R.id.imgBanner) ?: return@withContext)
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mostrarDialogoSair()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun carregarListasDaHome() {
        carregarContinuarAssistindoLocal()
        carregarFilmesRecentes()
        carregarSeriesRecentes()
    }

    private fun carregarFilmesRecentes() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = database.streamDao().getRecentVods(20).map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }
                withContext(Dispatchers.Main) {
                    binding.rvRecentlyAdded.adapter = HomeRowAdapter(list) { selectedItem ->
                        val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                        intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                        intent.putExtra("name", selectedItem.name)
                        intent.putExtra("icon", selectedItem.streamIcon)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("is_series", false)
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun carregarSeriesRecentes() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = database.streamDao().getRecentSeries(20).map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }
                withContext(Dispatchers.Main) {
                    binding.rvRecentSeries.adapter = HomeRowAdapter(list) { selectedItem ->
                        val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                        intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                        intent.putExtra("name", selectedItem.name)
                        intent.putExtra("icon", selectedItem.streamIcon)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("is_series", true)
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ FIX CORRETO: LÓGICA CONTINUAR ASSISTINDO COM VISIBILIDADE DINÂMICA DO TÍTULO
    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Busca o histórico do Room Database (tem o campo is_series)
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                
                // Mapeia para VodItem (visual)
                val vodItems = historyList.map { 
                    VodItem(
                        id = it.stream_id.toString(), 
                        name = it.name, 
                        streamIcon = it.icon ?: ""
                    ) 
                }

                // Mapa auxiliar para saber o tipo no clique
                val seriesMap = historyList.associate { it.stream_id.toString() to it.is_series }

                withContext(Dispatchers.Main) {
                    val tvTitle = binding.root.findViewById<TextView>(R.id.tvContinueWatching)
                    
                    if (vodItems.isNotEmpty()) {
                        // ✅ SE TEM ITENS: MOSTRA A LISTA E O TÍTULO
                        tvTitle?.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            
                            val isSeries = seriesMap[selected.id] ?: false
                            
                            val intent = if (isSeries) {
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", selected.id.toIntOrNull() ?: 0)
                                }
                            } else {
                                Intent(this@HomeActivity, DetailsActivity::class.java).apply {
                                    putExtra("stream_id", selected.id.toIntOrNull() ?: 0)
                                }
                            }
                            
                            intent.putExtra("name", selected.name)
                            intent.putExtra("icon", selected.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        // ❌ SE NÃO TEM NADA: ESCONDE O TÍTULO E A LISTA
                        tvTitle?.visibility = View.GONE
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ CLASSE INTERNA: ADAPTER DO BANNER (COM LOOP INFINITO)
    inner class BannerAdapter(private var items: List<Any>) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

        fun updateList(newItems: List<Any>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false)
            return BannerViewHolder(view)
        }

        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
            // 🔥 TRUQUE DO INFINITO: Usa o resto da divisão para repetir os itens
            if (items.isEmpty()) return
            val realPosition = position % items.size
            val item = items[realPosition]
            holder.bind(item)
        }

        // 🔥 Retorna um número gigante para permitir scroll "infinito"
        override fun getItemCount(): Int = if (items.isEmpty()) 0 else Integer.MAX_VALUE

        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View = itemView.findViewById(R.id.btnBannerPlay)

            fun bind(item: Any) {
                var title = ""
                var icon = ""
                var id = 0
                var isSeries = false
                var logoSalva: String? = null

                if (item is VodEntity) {
                    // Mapeamento correto: stream_icon para filmes
                    title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false; logoSalva = item.logo_url
                } else if (item is SeriesEntity) {
                    // Mapeamento correto: cover para séries
                    title = item.name; icon = item.cover ?: ""; id = item.series_id; isSeries = true; logoSalva = item.logo_url
                }

                val cleanTitle = limparNomeParaTMDB(title)
                
                // Configuração inicial (Texto visível)
                tvTitle.text = cleanTitle
                tvTitle.visibility = View.VISIBLE
                imgLogo.visibility = View.GONE

                // ✅ SE JÁ TEM LOGO NO DATABASE, MOSTRA AGORA (ZERO DELAY)
                if (!logoSalva.isNullOrEmpty()) {
                    tvTitle.visibility = View.GONE
                    imgLogo.visibility = View.VISIBLE
                    try {
                        Glide.with(itemView.context).load(logoSalva).into(imgLogo)
                    } catch (e: Exception) {}
                }

                // ✅ CARREGA A IMAGEM DO PAINEL (DATABASE) AGORA!
                buscarImagemBackgroundTMDB(cleanTitle, isSeries, icon, id, imgBanner, imgLogo, tvTitle)

                // Clique no botão Assistir
                btnPlay.setOnClickListener {
                     val intent = if (isSeries) Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
                                  else Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                     intent.putExtra("name", title)
                     intent.putExtra("icon", icon)
                     intent.putExtra("PROFILE_NAME", currentProfile)
                     intent.putExtra("is_series", isSeries)
                     startActivity(intent)
                }
                
                // Clique no Banner inteiro também abre
                itemView.setOnClickListener { btnPlay.performClick() }
            }
        }
    }
}
