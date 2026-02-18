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
import androidx.viewpager2.widget.ViewPager2 // Adicionado para corrigir erro do Banner
import androidx.cardview.widget.CardView // Adicionado para corrigir erro dos Cards

// ✅ NOVAS IMPORTAÇÕES PARA NAVEGAÇÃO
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🚨 PROTEÇÃO CONTRA CRASH NO INÍCIO
        try {
            // 🔥 DETECÇÃO MELHORADA: CELULAR vs TV
            configurarOrientacaoAutomatica()
            
            binding = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // ✅ CONFIGURAÇÃO DO NAVCONTROLLER (ADICIONADO)
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val navController = navHostFragment?.navController
            if (navController != null) {
                binding.bottomNavigation?.setupWithNavController(navController)
            }

            // ✅ RECUPERA O PERFIL E A FOTO
            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON")

            // ✅ CORREÇÃO 1: BARRA DE NAVEGAÇÃO FIXA (BOTÕES VISÍVEIS)
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false 

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
                        // FIX: Uso seguro do findViewById
                        val cardKids = findViewById<CardView>(R.id.cardKids)
                        cardKids?.performClick()
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
        // FIX: Uso seguro do findViewById
        val bannerViewPager = findViewById<ViewPager2>(R.id.bannerViewPager)
        bannerViewPager?.adapter = bannerAdapter
        bannerViewPager?.isUserInputEnabled = false
    }

    private fun setupBottomNavigation() {
        // ✅ ATUALIZA O NOME E O ÍCONE NO MENU INFERIOR
        binding.bottomNavigation?.let { nav ->
            val profileItem = nav.menu.findItem(R.id.nav_profile)
            profileItem?.title = currentProfile

            if (!currentProfileIcon.isNullOrEmpty()) {
                Glide.with(this)
                    .asBitmap()
                    .load(currentProfileIcon)
                    .circleCrop()
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            profileItem?.icon = BitmapDrawable(resources, resource)
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }
        
        // Listener mantido mas comentado onde causava conflito com o NavController
        // O NavController gerencia os cliques agora.
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
                    // FIX: Uso seguro do findViewById para evitar crash de compilação
                    val rvRecentMovies = findViewById<RecyclerView>(R.id.rvRecentlyAdded)
                    val rvRecentSeries = findViewById<RecyclerView>(R.id.rvRecentSeries)

                    if (movieItems.isNotEmpty()) {
                        rvRecentMovies?.setHasFixedSize(true)
                        rvRecentMovies?.setItemViewCacheSize(20)
                        
                        rvRecentMovies?.adapter = HomeRowAdapter(movieItems) { selectedItem ->
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
                        rvRecentSeries?.setHasFixedSize(true)
                        rvRecentSeries?.setItemViewCacheSize(20)

                        rvRecentSeries?.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", true)
                            startActivity(intent)
                        }
                    }
                    
                    listaCompletaParaSorteio = (localMovies + localSeries)
                    sortearBannerUnico()
                    ativarModoSupersonico(movieItems, seriesItems)
                    carregarContinuarAssistindoLocal()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun ativarModoSupersonico(filmes: List<VodItem>, series: List<VodItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            val preloadList = filmes.take(20) + series.take(20)
            
            for (item in preloadList) {
                try {
                    if (!item.streamIcon.isNullOrEmpty()) {
                        Glide.with(applicationContext)
                            .load(item.streamIcon) 
                            .format(DecodeFormat.PREFER_RGB_565) 
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .preload(180, 270) 
                    }
                } catch (e: Exception) { }
            }
        }
    }

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

    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String, internalId: Int, targetImg: ImageView, targetLogo: ImageView, targetTitle: TextView) {
        try {
            targetImg.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this@HomeActivity)
                .load(fallback)
                .centerCrop()
                .dontAnimate()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(targetImg)
        } catch (e: Exception) {}

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
                                    .format(DecodeFormat.PREFER_RGB_565)
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
                            bestPath = logo.getString("file_path"); break
                        }
                    }
                    if (bestPath == null) {
                        for (i in 0 until logos.length()) {
                            val logo = logos.getJSONObject(i)
                            val lang = logo.optString("iso_639_1")
                            if (lang == "null" || lang == "xx") {
                                bestPath = logo.getString("file_path"); break
                            }
                        }
                    }

                    if (bestPath != null) {
                        val fullLogoUrl = "https://image.tmdb.org/t/p/w500$bestPath"
                        try {
                            if (isSeries) database.streamDao().updateSeriesLogo(internalId, fullLogoUrl)
                            else database.streamDao().updateVodLogo(internalId, fullLogoUrl)
                        } catch(e: Exception) {}

                        withContext(Dispatchers.Main) {
                            targetTitle.visibility = View.GONE
                            targetLogo.visibility = View.VISIBLE
                            try {
                                Glide.with(this@HomeActivity).load(fullLogoUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(targetLogo)
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""; val user = prefs.getString("username", "") ?: ""; val pass = prefs.getString("password", "") ?: ""
        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000)
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
                        database.streamDao().insertVodStreams(vodBatch); vodBatch.clear()
                        if (!firstVodBatchLoaded) { withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }; firstVodBatchLoaded = true }
                    }
                }
                if (vodBatch.isNotEmpty()) database.streamDao().insertVodStreams(vodBatch)

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
                        database.streamDao().insertSeriesStreams(seriesBatch); seriesBatch.clear()
                        if (!firstSeriesBatchLoaded) { withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }; firstSeriesBatchLoaded = true }
                    }
                }
                if (seriesBatch.isNotEmpty()) database.streamDao().insertSeriesStreams(seriesBatch)

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
                    if (liveBatch.size >= 100) { database.streamDao().insertLiveStreams(liveBatch); liveBatch.clear() }
                }
                if (liveBatch.isNotEmpty()) database.streamDao().insertLiveStreams(liveBatch)

                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task -> }
    }

    override fun onResume() {
        super.onResume()
        try {
            sortearBannerUnico()
            carregarContinuarAssistindoLocal()
            atualizarNotificacaoDownload()
            setupBottomNavigation()
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    private fun atualizarNotificacaoDownload() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("active_downloads_count", 0)
        if (count > 0) {
            binding.bottomNavigation?.getOrCreateBadge(R.id.nav_downloads)?.apply { isVisible = true; number = count }
        } else {
            binding.bottomNavigation?.removeBadge(R.id.nav_downloads)
        }
    }

    private fun setupClicks() {
        fun isTelevisionDevice(): Boolean {
            return packageManager.hasSystemFeature("android.hardware.type.television") ||
                   packageManager.hasSystemFeature("android.software.leanback") ||
                   (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        }

        // FIX: Uso seguro do findViewById para evitar crash de compilação
        val cardLiveTv = findViewById<CardView>(R.id.cardLiveTv)
        val cardMovies = findViewById<CardView>(R.id.cardMovies)
        val cardSeries = findViewById<CardView>(R.id.cardSeries)
        val cardKids = findViewById<CardView>(R.id.cardKids)
        val bannerViewPager = findViewById<ViewPager2>(R.id.bannerViewPager)

        // Verificação de nulidade para evitar erro se as views não existirem
        val cards = listOfNotNull(cardLiveTv, cardMovies, cardSeries, cardKids)
        
        cards.forEach { card ->
            card.isFocusable = true; card.isClickable = true
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                else card.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
            }
            card.setOnClickListener {
                when (card.id) {
                    R.id.cardLiveTv -> startActivity(Intent(this, LiveTvActivity::class.java).apply { putExtra("SHOW_PREVIEW", true); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardMovies -> startActivity(Intent(this, VodActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardSeries -> startActivity(Intent(this, SeriesActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardKids -> startActivity(Intent(this, KidsActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", "Kids") })
                }
            }
        }
        
        if (isTelevisionDevice()) {
            cardLiveTv?.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { cardMovies?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { bannerViewPager?.requestFocus(); true } else false }
            cardMovies?.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { cardLiveTv?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { cardSeries?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { bannerViewPager?.requestFocus(); true } else false }
            cardSeries?.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { cardMovies?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { cardKids?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { bannerViewPager?.requestFocus(); true } else false }
            cardKids?.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { cardSeries?.requestFocus(); true } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { bannerViewPager?.requestFocus(); true } else false }
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair").setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }); finish()
            }.setNegativeButton("Não", null).show()
    }

    private fun carregarBannerAlternado() {
        val prefs = getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE)
        val ultimoTipo = prefs.getString("ultimo_tipo_banner", "tv") ?: "tv"
        val tipoAtual = if (ultimoTipo == "tv") "movie" else "tv"
        prefs.edit().putString("ultimo_tipo_banner", tipoAtual).apply()
        val urlString = "https://api.themoviedb.org/3/trending/$tipoAtual/day?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonTxt = URL(urlString).readText(); val results = JSONObject(jsonTxt).getJSONArray("results")
                if (results.length() > 0) {
                    val item = results.getJSONObject(Random.nextInt(results.length()))
                    val backdropPath = item.getString("backdrop_path")
                    if (backdropPath != "null" && backdropPath.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            try {
                                val imgBannerView = findViewById<ImageView>(R.id.imgBanner)
                                if (imgBannerView != null) { Glide.with(this@HomeActivity).load("https://image.tmdb.org/t/p/original$backdropPath").centerCrop().dontAnimate().format(DecodeFormat.PREFER_RGB_565).into(imgBannerView); imgBannerView.visibility = View.VISIBLE }
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { if (keyCode == KeyEvent.KEYCODE_BACK) { mostrarDialogoSair(); return true }; return super.onKeyDown(keyCode, event) }

    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                val vodItems = mutableListOf<VodItem>(); val seriesMap = mutableMapOf<String, Boolean>(); val seriesJaAdicionadas = mutableSetOf<String>()

                for (item in historyList) {
                    var finalId = item.stream_id.toString(); var finalName = item.name; var finalIcon = item.icon ?: ""; val isSeries = item.is_series
                    if (isSeries) {
                        try {
                            var cleanName = item.name.replace(Regex("(?i)^(S\\d+E\\d+|T\\d+E\\d+|\\d+x\\d+|E\\d+)\\s*(-|:)?\\s*"), "")
                            if (cleanName.contains(":")) cleanName = cleanName.substringBefore(":")
                            cleanName = cleanName.replace(Regex("(?i)\\s+(S\\d+|T\\d+|E\\d+|Ep\\d+|Temporada|Season|Episode|Capitulo|\\d+x\\d+).*"), "").trim()
                            val cursor = database.openHelper.writableDatabase.query("SELECT series_id, name, cover FROM series_streams WHERE name LIKE ? LIMIT 1", arrayOf("%$cleanName%"))
                            if (cursor.moveToFirst()) {
                                val realSeriesId = cursor.getInt(0).toString()
                                if (seriesJaAdicionadas.contains(realSeriesId)) { cursor.close(); continue }
                                finalId = realSeriesId; finalName = cursor.getString(1); finalIcon = cursor.getString(2); seriesJaAdicionadas.add(realSeriesId)
                            }
                            cursor.close()
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    vodItems.add(VodItem(finalId, finalName, finalIcon)); seriesMap[finalId] = isSeries
                }
                withContext(Dispatchers.Main) {
                    val tvTitle = findViewById<TextView>(R.id.tvContinueWatching)
                    val rvCont = findViewById<RecyclerView>(R.id.rvContinueWatching)
                    if (vodItems.isNotEmpty()) { tvTitle?.visibility = View.VISIBLE; rvCont?.visibility = View.VISIBLE; rvCont?.adapter = HomeRowAdapter(vodItems) { selected ->
                        val isSer = seriesMap[selected.id] ?: false
                        val intent = if (isSer) Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", selected.id.toIntOrNull() ?: 0) } else Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", selected.id.toIntOrNull() ?: 0) }
                        intent.putExtra("name", selected.name); intent.putExtra("icon", selected.streamIcon); intent.putExtra("PROFILE_NAME", currentProfile); startActivity(intent)
                    } } else { tvTitle?.visibility = View.GONE; rvCont?.visibility = View.GONE }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    inner class BannerAdapter(private var items: List<Any>) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {
        fun updateList(newItems: List<Any>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder = BannerViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false))
        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) { if (items.isNotEmpty()) holder.bind(items[0]) }
        override fun getItemCount(): Int = items.size
        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View = itemView.findViewById(R.id.btnBannerPlay)
            fun bind(item: Any) {
                var title = ""; var icon = ""; var id = 0; var isSeries = false; var logoSalva: String? = null
                if (item is VodEntity) { title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false; logoSalva = item.logo_url }
                else if (item is SeriesEntity) { title = item.name; icon = item.cover ?: ""; id = item.series_id; isSeries = true; logoSalva = item.logo_url }
                val cleanTitle = limparNomeParaTMDB(title); tvTitle.text = cleanTitle; tvTitle.visibility = View.VISIBLE; imgLogo.visibility = View.GONE
                if (!logoSalva.isNullOrEmpty()) { tvTitle.visibility = View.GONE; imgLogo.visibility = View.VISIBLE; try { Glide.with(itemView.context).load(logoSalva).into(imgLogo) } catch (e: Exception) {} }
                buscarImagemBackgroundTMDB(cleanTitle, isSeries, icon, id, imgBanner, imgLogo, tvTitle)
                btnPlay.setOnClickListener { val intent = if (isSeries) Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) } else Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                     intent.putExtra("name", title); intent.putExtra("icon", icon); intent.putExtra("PROFILE_NAME", currentProfile); intent.putExtra("is_series", isSeries); startActivity(intent)
                }
                itemView.setOnClickListener { btnPlay.performClick() }
            }
        }
    }
}
