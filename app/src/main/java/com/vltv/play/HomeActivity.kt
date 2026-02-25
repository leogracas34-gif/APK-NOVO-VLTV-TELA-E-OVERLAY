package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

// ✅ IMPORTAÇÕES CAST
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// ✅ FIREBASE
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    
    // ✅ VARIÁVEL DE PERFIL
    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    // ✅ INSTÂNCIA DO BANCO DE DADOS ROOM
    private val database by lazy { AppDatabase.getDatabase(this) }

    // --- VARIÁVEIS DO BANNER ---
    private var listaCompletaParaSorteio: List<Any> = emptyList()
    private lateinit var bannerAdapter: BannerAdapter
    private val REQUEST_CODE_CAST_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🚨 PROTEÇÃO CONTRA CRASH NO INÍCIO
        try {
            // 🔥 DETECÇÃO MELHORADA: CELULAR vs TV
            configurarOrientacaoAutomatica()
            
            binding = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // ✅ COLOQUE ESTE BLOCO AQUI
            try {
                CastContext.getSharedInstance(this)
                binding.mediaRouteButton?.let { btn ->
                    CastButtonFactory.setUpMediaRouteButton(applicationContext, btn)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            verificarPermissoesCast() // ✅ CHAME A FUNÇÃO AQUI
            

            // ✅ LÓGICA DE PERFIL GLOBAL (INCLUSÃO)
            // Primeiro tenta ler do SharedPreferences (a "caderneta") para garantir sincronia
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("last_profile_name", null)
            val savedIcon = prefs.getString("last_profile_icon", null)

            // Prioridade 1: O que veio no Intent (vindo da ProfilesActivity)
            // Prioridade 2: O que está na caderneta (SharedPreferences)
            // Prioridade 3: Padrão
            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: savedName ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON") ?: savedIcon

            // ✅ CORREÇÃO 1: BARRA DE NAVEGAÇÃO FIXA (BOTÕES VISÍVEIS)
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false 
            // REMOVIDO: windowInsetsController?.hide(...) -> Isso garante que a barra preta com botões fique visível

            DownloadHelper.registerReceiver(this)

            // ✅ SETUP CAST BUTTON (PROTEGIDO)
            try {
                CastContext.getSharedInstance(this)
                binding.mediaRouteButton?.let { btn ->
                    CastButtonFactory.setUpMediaRouteButton(applicationContext, btn)
                    btn.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ✅ INICIALIZA O LAYOUT
            setupSingleBanner()
            setupBottomNavigation()

            setupClicks() 
            setupFirebaseRemoteConfig()
            
            // ✅ CARREGAMENTO OTIMIZADO (TURBO)
            carregarDadosLocaisImediato()
            sincronizarConteudoSilenciosamente()

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

        } catch (e: Exception) {
            e.printStackTrace()
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

    // ✅ CONFIGURAÇÃO DO BANNER ESTÁTICO
    private fun setupSingleBanner() {
        bannerAdapter = BannerAdapter(emptyList())
        binding.bannerViewPager?.adapter = bannerAdapter
        binding.bannerViewPager?.isUserInputEnabled = false
    }

    private fun setupBottomNavigation() {
        // ✅ ATUALIZA O NOME E O ÍCONE NO MENU INFERIOR
        binding.bottomNavigation?.let { nav ->
            // Reforço: buscar direto da caderneta para garantir que se o Leandro trocou a foto, ela apareça aqui
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val finalName = prefs.getString("last_profile_name", currentProfile)
            val finalIcon = prefs.getString("last_profile_icon", currentProfileIcon)

            val profileItem = nav.menu.findItem(R.id.nav_profile)
            profileItem?.title = finalName

            if (!finalIcon.isNullOrEmpty()) {
                Glide.with(this)
                    .asBitmap()
                    .load(finalIcon)
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL) // Garante uso do cache para rapidez
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            profileItem?.icon = BitmapDrawable(resources, resource)
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }

        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false 
                }
                // ✅ Aponta para a nova tela de Novidades
                R.id.nav_novidades -> {
                    val intent = Intent(this, NovidadesActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }
    }

    // ✅ CARREGA DADOS DO DATABASE (OTIMIZADO PARA PERFORMANCE)
    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Pega do banco local (Room)
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        // 🚀 TURBO: Otimização de RecyclerView
                        binding.rvRecentlyAdded.setHasFixedSize(true)
                        binding.rvRecentlyAdded.setItemViewCacheSize(20)
                        
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
                        // 🚀 TURBO: Otimização de RecyclerView
                        binding.rvRecentSeries.setHasFixedSize(true)
                        binding.rvRecentSeries.setItemViewCacheSize(20)

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
                    
                    // Salva a lista completa para sortear no onResume
                    listaCompletaParaSorteio = (localMovies + localSeries)
                    sortearBannerUnico()
                    
                    // 🚀 ATIVA O MODO SUPERSONICO
                    ativarModoSupersonico(movieItems, seriesItems)

                    // ✅ AQUI: Carrega o "Continuar Assistindo" com a lógica nova
                    carregarContinuarAssistindoLocal()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 🚀 MODO VELOCIDADE DA LUZ: Baixa imagens com cache RGB_565 (Mais leve)
    private fun ativarModoSupersonico(filmes: List<VodItem>, series: List<VodItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            val preloadList = filmes.take(20) + series.take(20)
            
            for (item in preloadList) {
                try {
                    if (!item.streamIcon.isNullOrEmpty()) {
                        Glide.with(applicationContext)
                            .load(item.streamIcon) 
                            .format(DecodeFormat.PREFER_RGB_565) // 🚀 Otimização de Memória
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .preload(180, 270) 
                    }
                } catch (e: Exception) { }
            }
        }
    }

    // ✅ SORTEIO DE BANNER ÚNICO
    private fun sortearBannerUnico() {
        if (listaCompletaParaSorteio.isNotEmpty()) {
            val itemSorteado = listaCompletaParaSorteio.random()
            bannerAdapter.updateList(listOf(itemSorteado))
        } else {
            carregarBannerAlternado() 
        }
    }

    private fun limparNomeParaTMDB(nome: String): String {
        return nome.replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b"), "")
                   .replace(Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)"), "")
                   .replace(Regex("\\s+"), " ")
                   .trim()
                   .take(50)
    }

    // ✅ LÓGICA HÍBRIDA
    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String, internalId: Int, targetImg: ImageView, targetLogo: ImageView, targetTitle: TextView) {
        
        // 🚀 1. CARREGAMENTO INSTANTÂNEO COM GLIDE OTIMIZADO
        try {
            targetImg.scaleType = ImageView.ScaleType.CENTER_CROP
            
            Glide.with(this@HomeActivity)
                .load(fallback)
                .centerCrop()
                .dontAnimate()
                .format(DecodeFormat.PREFER_RGB_565) // 🚀 ECONOMIA DE MEMÓRIA
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(targetImg)
        } catch (e: Exception) {}

        // 🚀 2. BUSCA MELHORIA NO TMDB
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
                            if (backdropPath != "null" && backdropPath.isNotEmpty()) {
                                Glide.with(this@HomeActivity)
                                    .load("https://image.tmdb.org/t/p/original$backdropPath")
                                    .centerCrop()
                                    .dontAnimate()
                                    .format(DecodeFormat.PREFER_RGB_565) // 🚀 ECONOMIA DE MEMÓRIA
                                    .placeholder(targetImg.drawable)
                                    .into(targetImg)
                            }
                        } catch (e: Exception) {}
                    }
                    
                    buscarLogoOverlayHome(tmdbId, tipo, internalId, isSeries, targetLogo, targetTitle)
                }
            } catch (e: Exception) {}
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, internalId: Int, isSeries: Boolean, targetLogo: ImageView, targetTitle: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,null"
                
                val imagesJson = URL(imagesUrl).readText()
                val imagesObj = JSONObject(imagesJson)

                if (imagesObj.has("logos") && imagesObj.getJSONArray("logos").length() > 0) {
                    val logos = imagesObj.getJSONArray("logos")
                    var bestPath: String? = null
                    
                    for (i in 0 until logos.length()) {
                        val logo = logos.getJSONObject(i)
                        if (logo.optString("iso_639_1") == "pt") {
                            bestPath = logo.getString("file_path")
                            break
                        }
                    }
                    
                    if (bestPath == null) {
                        for (i in 0 until logos.length()) {
                            val logo = logos.getJSONObject(i)
                            val lang = logo.optString("iso_639_1")
                            if (lang == "null" || lang == "xx") {
                                bestPath = logo.getString("file_path")
                                break
                            }
                        }
                    }

                    if (bestPath != null) {
                        val fullLogoUrl = "https://image.tmdb.org/t/p/w500$bestPath"

                        try {
                            if (isSeries) {
                                database.streamDao().updateSeriesLogo(internalId, fullLogoUrl)
                            } else {
                                database.streamDao().updateVodLogo(internalId, fullLogoUrl)
                            }
                        } catch(e: Exception) {}

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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ SINCRONIZAÇÃO OTIMIZADA
    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000) // Delay para não travar a abertura
            
            try {
                // --- 1. FILMES ---
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
                    
                    if (vodBatch.size >= 50) {
                        database.streamDao().insertVodStreams(vodBatch)
                        vodBatch.clear()
                        
                        if (!firstVodBatchLoaded) {
                            withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
                            firstVodBatchLoaded = true
                        }
                    }
                }
                if (vodBatch.isNotEmpty()) {
                    database.streamDao().insertVodStreams(vodBatch)
                }
                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }

                // --- 2. SÉRIES ---
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

                    if (seriesBatch.size >= 50) {
                        database.streamDao().insertSeriesStreams(seriesBatch)
                        seriesBatch.clear()
                        
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

                // --- 3. LIVE ---
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
        // 🔥 PROTEÇÃO TAMBÉM NO ONRESUME
        try {
            // ✅ CARREGA O PERFIL GLOBAL AO VOLTAR PARA A TELA
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            currentProfile = prefs.getString("last_profile_name", currentProfile) ?: "Padrao"
            currentProfileIcon = prefs.getString("last_profile_icon", currentProfileIcon)

            sortearBannerUnico()
            carregarContinuarAssistindoLocal()
            atualizarNotificacaoDownload()
            setupBottomNavigation() // ✅ Atualiza a foto/nome ao voltar
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // ✅ FUNÇÃO ESVAZIADA para não dar erro do "nav_downloads"
    private fun atualizarNotificacaoDownload() {
        // Função desativada pois o botão de Downloads foi removido do rodapé
    }

    private fun setupClicks() {
        fun isTelevisionDevice(): Boolean {
            return packageManager.hasSystemFeature("android.hardware.type.television") ||
                   packageManager.hasSystemFeature("android.software.leanback") ||
                   (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        }

        // --- Configuração dos cliques ---
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
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardMovies -> {
                        val intent = Intent(this, VodActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardSeries -> {
                        val intent = Intent(this, SeriesActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardKids -> {
                        val intent = Intent(this, KidsActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", "Kids")
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                }
            }
        }
        
        if (isTelevisionDevice()) {
            // Lógica de D-PAD para TV
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

    // ✅ FUNÇÃO COM PROTEÇÃO ANTI-CRASH E OTIMIZAÇÃO
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
                    val tmdbId = item.getString("id")

                    if (backdropPath != "null" && backdropPath.isNotBlank()) {
                        val imageUrl = "https://image.tmdb.org/t/p/original$backdropPath"
                        withContext(Dispatchers.Main) {
                            try {
                                // 🔴 FIX: Busca segura pelo ID do Banner
                                val imgBannerView = binding.root.findViewById<ImageView>(R.id.imgBanner)
                                
                                if (imgBannerView != null) {
                                    Glide.with(this@HomeActivity)
                                        .load(imageUrl)
                                        .centerCrop()
                                        .dontAnimate()
                                        .format(DecodeFormat.PREFER_RGB_565)
                                        .into(imgBannerView)
                                    imgBannerView.visibility = View.VISIBLE
                                }
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

    // ✅ FIX DEFINITIVO 2 & 3: Substitui Episódio por Série (Capa + ID Correto) - COM FALLBACK
    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Busca o histórico do Room Database (que contém ID do Episódio)
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                
                val vodItems = mutableListOf<VodItem>()
                val seriesMap = mutableMapOf<String, Boolean>()
                // Set para não repetir a mesma série várias vezes se tiver vários episódios
                val seriesJaAdicionadas = mutableSetOf<String>()

                for (item in historyList) {
                    var finalId = item.stream_id.toString()
                    var finalName = item.name
                    var finalIcon = item.icon ?: ""
                    val isSeries = item.is_series

                    if (isSeries) {
                        try {
                            // 1. LIMPEZA DE PREFIXOS NO INÍCIO (T1E1, S01E01, 1x01)
                            // A Regex "^..." garante que só remove se estiver no começo
                            var cleanName = item.name.replace(Regex("(?i)^(S\\d+E\\d+|T\\d+E\\d+|\\d+x\\d+|E\\d+)\\s*(-|:)?\\s*"), "")
                            
                            // 2. REMOVE DOIS PONTOS (Pega só o que vem antes de :)
                            // Ex: "Destiladores: Mestre..." -> "Destiladores"
                            if (cleanName.contains(":")) {
                                cleanName = cleanName.substringBefore(":")
                            }
                            
                            // 3. LIMPEZA DE SUFIXOS (Temporada, Episódio no final)
                            cleanName = cleanName.replace(Regex("(?i)\\s+(S\\d+|T\\d+|E\\d+|Ep\\d+|Temporada|Season|Episode|Capitulo|\\d+x\\d+).*"), "")
                            
                            // 4. SEPARADOR " - " (Pega o que vem antes, se houver)
                            if (cleanName.contains(" - ")) {
                                cleanName = cleanName.substringBefore(" - ")
                            }
                            
                            cleanName = cleanName.trim()

                            // 5. BUSCA NO BANCO
                            val cursor = database.openHelper.writableDatabase.query(
                                "SELECT series_id, name, cover FROM series_streams WHERE name LIKE ? LIMIT 1", 
                                arrayOf("%$cleanName%")
                            )
                            
                            if (cursor.moveToFirst()) {
                                val realSeriesId = cursor.getInt(0).toString()
                                val realName = cursor.getString(1)
                                val realCover = cursor.getString(2)
                                
                                // Se já adicionamos essa série na lista, PULA para não duplicar
                                if (seriesJaAdicionadas.contains(realSeriesId)) {
                                    cursor.close()
                                    continue 
                                }

                                // SUBSTITUI O EPISÓDIO PELA SÉRIE PAI ENCONTRADA
                                finalId = realSeriesId
                                finalName = realName
                                finalIcon = realCover
                                seriesJaAdicionadas.add(realSeriesId)
                            } else {
                                // SE NÃO ACHOU A SÉRIE PAI NO BANCO, MANTÉM O EPISÓDIO
                                // MAS NÃO DEIXA SUMIR DA LISTA.
                            }
                            cursor.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // Se der erro, segue com o item original para não sumir
                        }
                    }

                    // Se for filme (isSeries false) ou se achou a série pai (ou falhou e manteve o original), adiciona
                    vodItems.add(VodItem(finalId, finalName, finalIcon))
                    seriesMap[finalId] = isSeries
                }

                withContext(Dispatchers.Main) {
                    val tvTitle = binding.root.findViewById<TextView>(R.id.tvContinueWatching)
                    if (vodItems.isNotEmpty()) {
                        tvTitle?.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            val isSeries = seriesMap[selected.id] ?: false
                            
                            val intent = if (isSeries) {
                                // ✅ ID DA SÉRIE PAI -> SeriesDetailsActivity (Usa "series_id")
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", selected.id.toIntOrNull() ?: 0)
                                }
                            } else {
                                // ✅ ID DO FILME -> DetailsActivity (Usa "stream_id")
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
                        tvTitle?.visibility = View.GONE
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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
            if (items.isNotEmpty()) {
                holder.bind(items[0])
            }
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
                tvTitle.text = cleanTitle
                tvTitle.visibility = View.VISIBLE
                imgLogo.visibility = View.GONE

                if (!logoSalva.isNullOrEmpty()) {
                    tvTitle.visibility = View.GONE
                    imgLogo.visibility = View.VISIBLE
                    try {
                        Glide.with(itemView.context).load(logoSalva).into(imgLogo)
                    } catch (e: Exception) {}
                }

                buscarImagemBackgroundTMDB(cleanTitle, isSeries, icon, id, imgBanner, imgLogo, tvTitle)

                btnPlay.setOnClickListener {
                     val intent = if (isSeries) Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
                                  else Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                     intent.putExtra("name", title)
                     intent.putExtra("icon", icon)
                     intent.putExtra("PROFILE_NAME", currentProfile)
                     intent.putExtra("is_series", isSeries)
                     startActivity(intent)
                }
                itemView.setOnClickListener { btnPlay.performClick() }
            }
        }
    }

        // ✅ COLE ESTAS DUAS FUNÇÕES ANTES DA ÚLTIMA CHAVE DA CLASSE
    private fun verificarPermissoesCast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissoes = arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            val faltamPermissoes = permissoes.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltamPermissoes) {
                ActivityCompat.requestPermissions(this, permissoes, REQUEST_CODE_CAST_PERMISSIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CODE_CAST_PERMISSIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Busca de dispositivos ativada", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
