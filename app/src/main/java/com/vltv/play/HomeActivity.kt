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

// ✅ IMPORTAÇÕES CAST (Necessário biblioteca Google Cast)
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

// ✅ FIREBASE ATIVADO
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    
    // ✅ VARIÁVEL DE PERFIL INTEGRADA
    private var currentProfile: String = "Padrao"

    // ✅ INSTÂNCIA DO BANCO DE DADOS ROOM
    private val database by lazy { AppDatabase.getDatabase(this) }

    // --- VARIÁVEIS DO BANNER (ADAPTADO PARA VIEWPAGER2) ---
    private var listaBannerItems: List<Any> = emptyList()
    private var bannerJob: Job? = null
    private var currentBannerIndex = 0
    private lateinit var bannerAdapter: BannerAdapter // Adapter novo para o carrossel

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

        // ✅ SETUP CAST BUTTON (CHROMECAST)
        // Se der erro de compilação aqui, é porque falta a dependência no build.gradle
        try {
            // Inicializa o contexto do Cast para garantir que o botão funcione
             CastContext.getSharedInstance(this)
            // Conecta o botão do XML ao serviço do Google Cast
            CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.mediaRouteButton)
        } catch (e: Exception) {
            // Se falhar (ex: sem biblioteca ou sem play services), esconde o botão
            binding.mediaRouteButton?.visibility = View.GONE
            e.printStackTrace()
        }

        // ✅ INICIALIZA O NOVO LAYOUT DISNEY
        setupViewPagerBanner()
        setupBottomNavigation()

        setupClicks() // Mantido, mas adaptado para a nova navegação
        setupFirebaseRemoteConfig()
        
        carregarDadosLocaisImediato()
        sincronizarConteudoSilenciosamente()
        carregarListasDaHome()

        // ✅ LÓGICA KIDS
        val isKidsMode = intent.getBooleanExtra("IS_KIDS_MODE", false)
        if (isKidsMode) {
            currentProfile = "Kids"
            // Delay pequeno para garantir que a UI carregou
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    binding.cardKids.performClick()
                    Toast.makeText(this, "Modo Kids Ativado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }, 500)
        }
    }

    // 🔥 FIX CORRETO: DETECTA CELULAR vs TV (MELHORADO PARA TELAS GRANDES DE CELULAR)
    private fun configurarOrientacaoAutomatica() {
        if (isTVDevice()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // 🔥 DETECÇÃO PRECISA: TV REAL vs CELULAR COM TELA GRANDE
    private fun isTVDevice(): Boolean {
        return try {
            // ✅ PRIORIDADE 1: FEATURES DE TV (MAIS CONFIÁVEL)
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packageManager.hasSystemFeature("android.hardware.type.television") ||
            
            // ✅ PRIORIDADE 2: UI MODE TELEVISION
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == 
            Configuration.UI_MODE_TYPE_TELEVISION ||
            
            // ✅ PRIORIDADE 3: DENSIDADE + TAMANHO (MELHORADO)
            isRealTVSize()
            
        } catch (e: Exception) {
            false
        }
    }

    // 🔥 NOVO: DETECTA TV REAL (CORRIGE PROBLEMA DE CELULARES GRANDES)
    private fun isRealTVSize(): Boolean {
        return try {
            val displayMetrics = resources.displayMetrics
            val widthDp = displayMetrics.widthPixels / displayMetrics.density
            val heightDp = displayMetrics.heightPixels / displayMetrics.density
            
            // ✅ TVs reais: > 600dp LARGURA em landscape + baixa densidade
            val isLargeWidth = widthDp > 600
            val isLowDensity = displayMetrics.densityDpi < DisplayMetrics.DENSITY_XHIGH // < 320dpi
            val isTVSize = isLargeWidth && isLowDensity
            
            isTVSize
        } catch (e: Exception) {
            false
        }
    }

    // ✅ CONFIGURAÇÃO DO CARROSSEL ESTILO DISNEY (VIEWPAGER2)
    // ADICIONADO ?. (Null Safety) para evitar crash se o layout mudar
    private fun setupViewPagerBanner() {
        bannerAdapter = BannerAdapter(emptyList())
        binding.bannerViewPager?.adapter = bannerAdapter
        // Mantém 3 páginas na memória para rolar suave
        binding.bannerViewPager?.offscreenPageLimit = 3
        
        // Efeito de Profundidade e Margem (ver pontinha do próximo)
        val compositePageTransformer = CompositePageTransformer()
        compositePageTransformer.addTransformer(MarginPageTransformer(40))
        compositePageTransformer.addTransformer { page, position ->
            val r = 1 - abs(position)
            page.scaleY = 0.85f + r * 0.15f // Item central maior (1.0), laterais menores (0.85)
        }
        binding.bannerViewPager?.setPageTransformer(compositePageTransformer)

        // Callback para loop infinito
        binding.bannerViewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentBannerIndex = position
                // Reinicia o timer do auto-scroll ao tocar
                bannerJob?.cancel()
                iniciarCicloBanner()
            }
        })
    }

    // ✅ CONFIGURAÇÃO DO MENU INFERIOR
    // ADICIONADO ?. (Null Safety) e removido NestedScrollView que não tem ID
    private fun setupBottomNavigation() {
        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Removido smoothScrollTo pois o ID nestedScrollView não existe no XML fornecido
                    true
                }
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
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

    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- BANNER OTIMIZADO PARA VIEWPAGER ---
    private fun prepararBannerDosRecentes(filmes: List<VodEntity>, series: List<SeriesEntity>) {
        val mixLançamentos = (filmes + series).shuffled().take(10) // Pega 10 misturados
        
        if (mixLançamentos.isNotEmpty()) {
            listaBannerItems = mixLançamentos
            bannerAdapter.updateList(listaBannerItems) // Atualiza o Adapter
            iniciarCicloBanner()
        } else {
            // Se não tiver local, tenta buscar do TMDB (Trendings)
            carregarBannerAlternado() 
        }
    }

    private fun iniciarCicloBanner() {
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            while (true) {
                delay(8000)
                if (listaBannerItems.isNotEmpty()) {
                    // Verifica se binding.bannerViewPager é nulo antes de acessar
                    var nextIndex = (binding.bannerViewPager?.currentItem ?: 0) + 1
                    if (nextIndex >= listaBannerItems.size) nextIndex = 0
                    binding.bannerViewPager?.setCurrentItem(nextIndex, true)
                } else {
                    break
                }
            }
        }
    }

    // 🧹 VASSOURA MELHORADA (PT-BR IPTV)
    private fun limparNomeParaTMDB(nome: String): String {
        return nome.replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b"), "")
                   .replace(Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)"), "")
                   .replace(Regex("\\s+"), " ")
                   .trim()
                   .take(50) // Limita tamanho para UI
    }

    // --- FUNÇÕES DE BUSCA TMDB ADAPTADAS PARA O ADAPTER ---
    // Agora aceitam as Views de destino como parâmetro, pois estão dentro do ViewHolder
    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String, internalId: Int, targetImg: ImageView, targetLogo: ImageView, targetTitle: TextView) {
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
                            Glide.with(this@HomeActivity)
                                .load("https://image.tmdb.org/t/p/original$backdropPath")
                                .centerCrop()
                                .placeholder(R.drawable.bg_gradient_black) // ✅ VOLTOU O GRADIENTE
                                .into(targetImg)
                        } catch (e: Exception) {}
                    }
                    
                    buscarLogoOverlayHome(tmdbId, tipo, internalId, isSeries, targetLogo, targetTitle)
                } else {
                    withContext(Dispatchers.Main) {
                        try {
                            Glide.with(this@HomeActivity)
                                .load(fallback)
                                .centerCrop()
                                .placeholder(R.drawable.bg_gradient_black) // ✅ VOLTOU O GRADIENTE
                                .into(targetImg)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    try {
                        Glide.with(this@HomeActivity)
                            .load(fallback)
                            .centerCrop()
                            .placeholder(R.drawable.bg_gradient_black) // ✅ VOLTOU O GRADIENTE
                            .into(targetImg)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, internalId: Int, isSeries: Boolean, targetLogo: ImageView, targetTitle: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                val imagesJson = URL(imagesUrl).readText()
                val imagesObj = JSONObject(imagesJson)

                if (imagesObj.has("logos") && imagesObj.getJSONArray("logos").length() > 0) {
                    val logos = imagesObj.getJSONArray("logos")
                    var logoPath: String? = null
                    for (i in 0 until logos.length()) {
                        val logo = logos.getJSONObject(i)
                        if (logo.optString("iso_639_1") == "pt") {
                            logoPath = logo.getString("file_path")
                            break
                        }
                    }
                    if (logoPath == null) logoPath = logos.getJSONObject(0).getString("file_path")
                    
                    val fullLogoUrl = "https://image.tmdb.org/t/p/w500$logoPath"

                    // ✅ SALVA NO BANCO PARA PRÓXIMA VEZ (INSTANTÂNEO)
                    try {
                        if (isSeries) {
                            database.streamDao().updateSeriesLogo(internalId, fullLogoUrl)
                        } else {
                            database.streamDao().updateVodLogo(internalId, fullLogoUrl)
                        }
                    } catch(e: Exception) {
                        e.printStackTrace()
                    }

                    withContext(Dispatchers.Main) {
                        // ✅ SUBSTITUI TEXTO PELO LOGO NO ADAPTER
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

    // ✅ MANTIDA TODA A LÓGICA DE SINCRONIZAÇÃO ORIGINAL
    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val vodResponse = URL(vodUrl).readText()
                val vodArray = org.json.JSONArray(vodResponse)
                val vodEntities = mutableListOf<VodEntity>()
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

                for (i in 0 until vodArray.length()) {
                    val obj = vodArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                        vodEntities.add(VodEntity(
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
                }
                database.streamDao().insertVodStreams(vodEntities)

                val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                val seriesResponse = URL(seriesUrl).readText()
                val seriesArray = org.json.JSONArray(seriesResponse)
                val seriesEntities = mutableListOf<SeriesEntity>()

                for (i in 0 until seriesArray.length()) {
                    val obj = seriesArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                        seriesEntities.add(SeriesEntity(
                            series_id = obj.optInt("series_id"),
                            name = nome,
                            cover = obj.optString("cover"),
                            rating = obj.optString("rating"),
                            category_id = obj.optString("category_id"),
                            last_modified = obj.optLong("last_modified")
                        ))
                    }
                }
                database.streamDao().insertSeriesStreams(seriesEntities)

                val liveUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_live_streams"
                val liveResponse = URL(liveUrl).readText()
                val liveArray = org.json.JSONArray(liveResponse)
                val liveEntities = mutableListOf<LiveStreamEntity>()

                for (i in 0 until liveArray.length()) {
                    val obj = liveArray.getJSONObject(i)
                    liveEntities.add(LiveStreamEntity(
                        stream_id = obj.optInt("stream_id"),
                        name = obj.optString("name"),
                        stream_icon = obj.optString("stream_icon"),
                        epg_channel_id = obj.optString("epg_channel_id"),
                        category_id = obj.optString("category_id")
                    ))
                }
                database.streamDao().insertLiveStreams(liveEntities)

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
        
        // Mantive a lógica original
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                // Configuração remota carregada
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (listaBannerItems.isEmpty()) {
            // carregarBannerAlternado()
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
        // Adaptada para incluir o ? no bannerViewPager
        if (isTelevisionDevice()) {
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus() // Sobe para o Banner
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

    private fun carregarBannerAlternado() {
        // Mantido
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
                        intent.putExtra("PROFILE_NAME", currentProfile)
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

    // ✅ FIX: LÓGICA DE CONTINUAR ASSISTINDO AGORA CONECTADA AO BANCO DE DADOS CORRETO
    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ✅ BUSCA NO ROOM (Database) usando o nome correto da função que está no seu StreamDao.kt
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                
                // Converte para VodItem removendo o parâmetro 'isSeries' que não existe no seu VodItem.kt
                val vodItems = historyList.map { 
                    VodItem(
                        id = it.stream_id.toString(), 
                        name = it.name, 
                        streamIcon = it.icon ?: ""
                    ) 
                }

                withContext(Dispatchers.Main) {
                    if (vodItems.isNotEmpty()) {
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            // Ao clicar, abre o Player ou Detalhes
                            val intent = Intent(this@HomeActivity, PlayerActivity::class.java)
                            intent.putExtra("stream_id", selected.id.toIntOrNull() ?: 0)
                            // Removido stream_type pois não temos como saber pelo VodItem atual
                            intent.putExtra("channel_name", selected.name)
                            intent.putExtra("icon", selected.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ CLASSE INTERNA: ADAPTER PARA O BANNER (VIEWPAGER2)
    inner class BannerAdapter(private var items: List<Any>) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

        fun updateList(newItems: List<Any>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            // Usa o layout XML 'item_banner_home.xml'
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false)
            return BannerViewHolder(view)
        }

        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount(): Int = items.size

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
                    title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false; logoSalva = item.logo_url
                } else if (item is SeriesEntity) {
                    title = item.name; icon = item.cover ?: ""; id = item.series_id; isSeries = true; logoSalva = item.logo_url
                }

                val cleanTitle = limparNomeParaTMDB(title)
                
                // Configuração inicial (Texto visível, logo oculta)
                tvTitle.text = cleanTitle
                tvTitle.visibility = View.VISIBLE
                imgLogo.visibility = View.GONE

                // Se já tem logo salva no banco, usa ela direto
                if (!logoSalva.isNullOrEmpty()) {
                    tvTitle.visibility = View.GONE
                    imgLogo.visibility = View.VISIBLE
                    try {
                        Glide.with(itemView.context).load(logoSalva).into(imgLogo)
                    } catch (e: Exception) {}
                }

                // Carrega imagem de fundo TMDB
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
