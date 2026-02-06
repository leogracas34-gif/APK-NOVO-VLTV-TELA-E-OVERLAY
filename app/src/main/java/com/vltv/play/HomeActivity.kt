package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.bumptech.glide.Glide
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

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

    // --- VARIÁVEIS DO BANNER INTERATIVO ---
    private var listaBannerItems: List<Any> = emptyList()
    private var bannerJob: Job? = null
    private var currentBannerIndex = 0
    
    // IDs de categorias que são NOVELAS para não aparecerem no banner
    private val categoriasNovelas = listOf("15", "20", "30") 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ LÓGICA PROFISSIONAL: Diferencia Celular de TV
        configurarOrientacaoDispositivo()

        // ✅ RECUPERA O PERFIL SELECIONADO
        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        DownloadHelper.registerReceiver(this)

        setupClicks()
        setupFirebaseRemoteConfig() 
        
        // ✅ CARREGAMENTO INICIAL DO BANCO DE DADOS
        carregarDadosLocaisImediato()
        
        // ✅ INICIA DOWNLOAD SILENCIOSO EM BACKGROUND
        sincronizarConteudoSilenciosamente()

        carregarListasDaHome()

        // ✅ ATUALIZA O AVATAR NO RODAPÉ
        atualizarAvatarRodape()

        // ✅ LÓGICA KIDS
        val isKidsMode = intent.getBooleanExtra("IS_KIDS_MODE", false)
        if (isKidsMode) {
            currentProfile = "Kids"
            binding.root.postDelayed({
                binding.cardKids?.performClick()
                Toast.makeText(this, "Modo Kids Ativado", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }

    // ✅ NOVA FUNÇÃO: Trava orientação dependendo do dispositivo (Estilo Disney+)
    private fun configurarOrientacaoDispositivo() {
        val isTV = packageManager.hasSystemFeature("android.hardware.type.television") ||
                   packageManager.hasSystemFeature("android.software.leanback") ||
                   (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

        if (isTV) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // ✅ NOVA FUNÇÃO: Carrega o avatar que o cliente escolheu para o perfil
    private fun atualizarAvatarRodape() {
        try {
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val avatarName = prefs.getString("${currentProfile}_avatar_key", "avatar_default") ?: "avatar_default"
            val resID = resources.getIdentifier(avatarName, "drawable", packageName)
            
            if (resID != 0) {
                binding.imgNavProfile?.setImageResource(resID)
            }
            binding.tvNavProfileName?.text = currentProfile
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ✅ BUSCA NO BANCO DE DADOS LOCAL IMEDIATAMENTE
    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        binding.rvRecentlyAdded?.adapter = HomeRowAdapter(movieItems) { selectedItem ->
                            abrirDetalhes(selectedItem.id.toIntOrNull() ?: 0, selectedItem.name, selectedItem.streamIcon, false)
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        binding.rvRecentSeries?.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            abrirDetalhes(selectedItem.id.toIntOrNull() ?: 0, selectedItem.name, selectedItem.streamIcon, true)
                        }
                    }
                    
                    prepararBannerDosRecentes(localMovies, localSeries)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- FUNÇÕES DO NOVO BANNER INTERATIVO ---

    private fun prepararBannerDosRecentes(filmes: List<VodEntity>, series: List<SeriesEntity>) {
        val seriesSemNovelas = series.filter { it.category_id?.trim() !in categoriasNovelas }
        
        val mixLançamentos = (filmes.take(5) + seriesSemNovelas.take(5)).shuffled()
        
        if (mixLançamentos.isNotEmpty()) {
            listaBannerItems = mixLançamentos
            iniciarCicloBanner()
        } else {
            carregarBannerAlternado()
        }
    }

    private fun iniciarCicloBanner() {
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            while (true) {
                if (listaBannerItems.isNotEmpty()) {
                    exibirItemNoBanner(listaBannerItems[currentBannerIndex])
                    delay(8000) 
                    currentBannerIndex = (currentBannerIndex + 1) % listaBannerItems.size
                } else {
                    break
                }
            }
        }
    }

    private fun exibirItemNoBanner(item: Any) {
        val titulo: String
        val id: Int
        val rating: String
        val icon: String
        val isSeries: Boolean

        if (item is VodEntity) {
            titulo = item.name
            id = item.stream_id
            rating = item.rating ?: "0.0"
            icon = item.stream_icon ?: ""
            isSeries = false
        } else if (item is SeriesEntity) {
            titulo = item.name
            id = item.series_id
            rating = item.rating ?: "0.0"
            icon = item.cover ?: ""
            isSeries = true
        } else return

        // ADICIONADO ?. PARA EVITAR CRASH
        binding.imgBannerLogo?.setImageDrawable(null)
        binding.imgBannerLogo?.visibility = View.GONE
        binding.tvBannerTitle?.visibility = View.VISIBLE
        binding.tvBannerTitle?.text = titulo
        
        binding.tvBannerOverview?.text = if (isSeries) "Série em Destaque" else "Filme em Destaque"
        
        try {
            val tvRating = findViewById<TextView>(R.id.tvBannerRating)
            tvRating?.text = "⭐ $rating"
            val btnPlay = findViewById<View>(R.id.btnBannerPlay)
            btnPlay?.visibility = View.VISIBLE
        } catch (e: Exception) {}

        buscarImagemBackgroundTMDB(titulo, isSeries, icon)

        // ADICIONADO ?. PARA EVITAR CRASH
        binding.cardBanner?.setOnClickListener {
            abrirDetalhes(id, titulo, icon, isSeries)
        }
    }

    private fun abrirDetalhes(id: Int, nome: String, icon: String, isSeries: Boolean) {
        val intent = if (isSeries) {
            Intent(this, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
        } else {
            Intent(this, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
        }
        intent.putExtra("name", nome)
        intent.putExtra("icon", icon)
        intent.putExtra("PROFILE_NAME", currentProfile)
        intent.putExtra("is_series", isSeries)
        startActivity(intent)
    }

    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String) {
        val tipo = if (isSeries) "tv" else "movie"
        val query = URLEncoder.encode(nome, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY&query=$query&language=pt-BR"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(url).readText()
                val results = JSONObject(response).getJSONArray("results")
                if (results.length() > 0) {
                    val backdropPath = results.getJSONObject(0).optString("backdrop_path")
                    val tmdbId = results.getJSONObject(0).optString("id")
                    
                    withContext(Dispatchers.Main) {
                        // ADICIONADO ?. PARA EVITAR CRASH
                        if (binding.imgBanner != null) {
                            Glide.with(this@HomeActivity)
                                .load("https://image.tmdb.org/t/p/original$backdropPath")
                                .centerCrop()
                                .placeholder(binding.imgBanner?.drawable)
                                .into(binding.imgBanner!!)
                        }
                        
                        buscarLogoOverlayHome(tmdbId, tipo, nome)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                         if (binding.imgBanner != null) {
                            Glide.with(this@HomeActivity).load(fallback).centerCrop().into(binding.imgBanner!!)
                         }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                     if (binding.imgBanner != null) {
                        Glide.with(this@HomeActivity).load(fallback).centerCrop().into(binding.imgBanner!!)
                     }
                }
            }
        }
    }

    // ✅ SINCRONIZAÇÃO SILENCIOSA (MANTIDA)
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
                val proibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

                for (i in 0 until vodArray.length()) {
                    val obj = vodArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!proibidas.any { nome.uppercase().contains(it) }) {
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
                    if (!proibidas.any { nome.uppercase().contains(it) }) {
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

                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ✅ FIREBASE REMOTE CONFIG (MANTIDA)
    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val bannerUrl = remoteConfig.getString("url_banner_promocional")
                val bannerTitle = remoteConfig.getString("titulo_banner_promocional")
                
                if (bannerUrl.isNotEmpty()) {
                    bannerJob?.cancel() 
                    runOnUiThread {
                        binding.tvBannerTitle?.visibility = View.VISIBLE
                        binding.tvBannerTitle?.text = bannerTitle.ifEmpty { "Destaque VLTV" }
                        binding.tvBannerOverview?.text = "" 
                        binding.imgBannerLogo?.visibility = View.GONE
                        
                        // ADICIONADO ?. PARA EVITAR CRASH
                        if (binding.imgBanner != null) {
                            Glide.with(this@HomeActivity)
                                .load(bannerUrl)
                                .centerCrop()
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(binding.imgBanner!!)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (listaBannerItems.isEmpty()) { carregarBannerAlternado() }
        
        atualizarAvatarRodape()

        try {
            binding.etSearch?.setText("")
            binding.etSearch?.clearFocus()
            binding.etSearch?.background = null 
            binding.etSearch?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(0)?.start()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch?.windowToken, 0)
            binding.cardBanner?.requestFocus()
            carregarContinuarAssistindoLocal()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setupClicks() {
        // ✅ Clicks do Rodapé (ADICIONADOS APENAS AQUI PARA O MOBILE)
        binding.navHome?.setOnClickListener { carregarListasDaHome() }
        binding.navSearch?.setOnClickListener { binding.etSearch?.performClick() }
        binding.navDownloads?.setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        binding.navProfile?.setOnClickListener { abrirMenuOpcoes() }

        binding.etSearch?.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("PROFILE_NAME", currentProfile)
            startActivity(intent)
        }

        binding.btnSettings?.setOnClickListener { abrirMenuOpcoes() }

        val cards = listOf(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardKids, binding.cardBanner)
        cards.forEach { card ->
            card?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                } else {
                    card.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            
            card?.setOnClickListener {
                when (card.id) {
                    R.id.cardLiveTv -> startActivity(Intent(this, LiveTvActivity::class.java).apply { putExtra("SHOW_PREVIEW", true); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardMovies -> startActivity(Intent(this, VodActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardSeries -> startActivity(Intent(this, SeriesActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
                    R.id.cardKids -> startActivity(Intent(this, KidsActivity::class.java).apply { putExtra("PROFILE_NAME", "Kids") })
                }
            }
        }
        
        // Navegação DPAD (TV)
        binding.cardLiveTv?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { binding.cardMovies?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { binding.etSearch?.requestFocus(); true }
            else false
        }
        
        binding.cardMovies?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { binding.cardSeries?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { binding.cardLiveTv?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { binding.etSearch?.requestFocus(); true }
            else false
        }

        binding.cardSeries?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { binding.cardKids?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { binding.cardMovies?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { binding.etSearch?.requestFocus(); true }
            else false
        }

        binding.cardKids?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { binding.cardSeries?.requestFocus(); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { binding.etSearch?.requestFocus(); true }
            else false
        }
    }

    private fun abrirMenuOpcoes() {
        val itens = arrayOf("Trocar Perfil", "Meus downloads", "Configurações", "Sair")
        AlertDialog.Builder(this)
            .setTitle("Opções - $currentProfile")
            .setItems(itens) { _, which ->
                when (which) {
                    0 -> finish() 
                    1 -> startActivity(Intent(this, DownloadsActivity::class.java))
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                    3 -> mostrarDialogoSair()
                }
            }
            .show()
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this).setTitle("Sair").setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                finish()
            }.setNegativeButton("Não", null).show()
    }

    private fun carregarBannerAlternado() {
        val prefs = getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE)
        val tipoAtual = if (prefs.getString("ultimo_tipo_banner", "tv") == "tv") "movie" else "tv"
        prefs.edit().putString("ultimo_tipo_banner", tipoAtual).apply()
        val urlString = "https://api.themoviedb.org/3/trending/$tipoAtual/day?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonTxt = URL(urlString).readText()
                val json = JSONObject(jsonTxt)
                val results = json.getJSONArray("results")
                if (results.length() > 0) {
                    val item = results.getJSONObject(Random.nextInt(results.length()))
                    val titulo = if (item.has("title")) item.getString("title") else item.optString("name", "Destaque")
                    val tmdbId = item.getString("id")
                    withContext(Dispatchers.Main) {
                        binding.tvBannerTitle?.text = titulo
                        if (binding.imgBanner != null) {
                            Glide.with(this@HomeActivity).load("https://image.tmdb.org/t/p/original${item.getString("backdrop_path")}").centerCrop().into(binding.imgBanner!!)
                        }
                        buscarLogoOverlayHome(tmdbId, tipoAtual, titulo)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, rawName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagesJson = URL("https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY").readText()
                val logos = JSONObject(imagesJson).optJSONArray("logos")
                if (logos != null && logos.length() > 0) {
                    val fullLogoUrl = "https://image.tmdb.org/t/p/w500${logos.getJSONObject(0).getString("file_path")}"
                    withContext(Dispatchers.Main) {
                        binding.tvBannerTitle?.visibility = View.GONE
                        binding.imgBannerLogo?.visibility = View.VISIBLE
                        Glide.with(this@HomeActivity).load(fullLogoUrl).into(binding.imgBannerLogo!!)
                    }
                } else {
                    withContext(Dispatchers.Main) { binding.tvBannerTitle?.visibility = View.VISIBLE; binding.imgBannerLogo?.visibility = View.GONE }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.tvBannerTitle?.visibility = View.VISIBLE; binding.imgBannerLogo?.visibility = View.GONE }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { mostrarDialogoSair(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun carregarListasDaHome() {
        carregarContinuarAssistindoLocal()
        carregarFilmesRecentes()
        carregarSeriesRecentes()
    }

    private fun carregarFilmesRecentes() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                val response = URL("$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams").readText()
                val jsonArray = org.json.JSONArray(response)
                val rawList = mutableListOf<JSONObject>()
                val proibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (!proibidas.any { obj.optString("name").uppercase().contains(it) }) rawList.add(obj)
                }
                val finalRecentList = rawList.sortedByDescending { it.optLong("added") }.take(20).map {
                    VodItem(it.optString("stream_id"), it.optString("name"), it.optString("stream_icon"))
                }
                withContext(Dispatchers.Main) {
                    // ADICIONADO ?. PARA EVITAR CRASH
                    binding.rvRecentlyAdded?.adapter = HomeRowAdapter(finalRecentList) { item ->
                        abrirDetalhes(item.id.toIntOrNull() ?: 0, item.name, item.streamIcon, false)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarSeriesRecentes() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                val response = URL("$dns/player_api.php?username=$user&password=$pass&action=get_series").readText()
                val jsonArray = org.json.JSONArray(response)
                val rawList = mutableListOf<JSONObject>()
                val proibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (!proibidas.any { obj.optString("name").uppercase().contains(it) }) rawList.add(obj)
                }
                val finalSeriesList = rawList.sortedByDescending { it.optLong("last_modified") }.take(20).map {
                    VodItem(it.optString("series_id"), it.optString("name"), it.optString("cover"))
                }
                withContext(Dispatchers.Main) {
                    // ADICIONADO ?. PARA EVITAR CRASH
                    binding.rvRecentSeries?.adapter = HomeRowAdapter(finalSeriesList) { item ->
                        abrirDetalhes(item.id.toIntOrNull() ?: 0, item.name, item.streamIcon, true)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarContinuarAssistindoLocal() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val historyList = mutableListOf<VodItem>()
        val savedIds = prefs.getStringSet("${currentProfile}_local_history_ids", emptySet()) ?: emptySet()
        for (id in savedIds) {
            val name = prefs.getString("${currentProfile}_history_name_$id", "") ?: ""
            val icon = prefs.getString("${currentProfile}_history_icon_$id", "") ?: ""
            if (name.isNotEmpty()) historyList.add(VodItem(id, name, icon))
        }
        if (historyList.isNotEmpty()) {
            binding.rvContinueWatching?.visibility = View.VISIBLE
            // ADICIONADO ?. PARA EVITAR CRASH
            binding.rvContinueWatching?.adapter = HomeRowAdapter(historyList.reversed()) { item ->
                val isSeries = prefs.getBoolean("${currentProfile}_history_is_series_${item.id}", false)
                abrirDetalhes(item.id.toIntOrNull() ?: 0, item.name, item.streamIcon, isSeries)
            }
        } else { binding.rvContinueWatching?.visibility = View.GONE }
    }
}
