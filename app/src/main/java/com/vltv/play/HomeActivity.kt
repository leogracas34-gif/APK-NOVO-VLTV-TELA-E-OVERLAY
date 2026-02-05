package com.vltv.play

import android.content.Context
import android.content.Intent
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
import java.util.Calendar
import java.util.Date
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
    // ✅ Job para cancelar requisições antigas do TMDB (Isso resolve o nome preso)
    private var tmdbJob: Job? = null
    private var currentBannerIndex = 0

    // ✅ LISTA DE CATEGORIAS BLOQUEADAS (NOVELAS)
    private val categoriasNovelas = listOf("15", "20", "30")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ RECUPERA O PERFIL SELECIONADO
        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        DownloadHelper.registerReceiver(this)

        setupClicks()
        setupFirebaseRemoteConfig() // ✅ Chamada ativada para o Firebase

        // ✅ CARREGAMENTO INICIAL DO BANCO DE DADOS
        carregarDadosLocaisImediato()

        // ✅ INICIA DOWNLOAD SILENCIOSO EM BACKGROUND
        sincronizarConteudoSilenciosamente()

        carregarListasDaHome()

        // ✅ LÓGICA KIDS
        val isKidsMode = intent.getBooleanExtra("IS_KIDS_MODE", false)
        if (isKidsMode) {
            currentProfile = "Kids"
            binding.root.postDelayed({
                binding.cardKids.performClick()
                Toast.makeText(this, "Modo Kids Ativado", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }

    // ✅ BUSCA NO BANCO DE DADOS LOCAL IMEDIATAMENTE
    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Busca os itens salvos no banco Room
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        binding.rvRecentlyAdded.adapter = HomeRowAdapter(movieItems) { selectedItem ->
                            abrirDetalhesReal(selectedItem.id.toIntOrNull() ?: 0, selectedItem.name, selectedItem.streamIcon, false)
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        binding.rvRecentSeries.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            abrirDetalhesReal(selectedItem.id.toIntOrNull() ?: 0, selectedItem.name, selectedItem.streamIcon, true)
                        }
                    }
                    
                    // --- AQUI ENTRA A CORREÇÃO DO BANNER ---
                    prepararBannerDosRecentes(localMovies, localSeries)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- NOVA LÓGICA DO BANNER (SEM TRAVAR NOME E SEM NOVELAS) ---

    private fun prepararBannerDosRecentes(filmes: List<VodEntity>, series: List<SeriesEntity>) {
        // ✅ CORREÇÃO: Filtra novelas usando .trim() para evitar erros de espaço no ID
        val seriesFiltradas = series.filter { 
            val catId = it.category_id?.trim() ?: ""
            catId !in categoriasNovelas 
        }
        
        // Mistura e seleciona
        val mixLançamentos = (filmes.take(5) + seriesFiltradas.take(5)).shuffled()
        
        if (mixLançamentos.isNotEmpty()) {
            listaBannerItems = mixLançamentos
            iniciarCicloBanner()
        } else {
            // Se não tiver dados locais, usa o método antigo (TMDB aleatório)
            carregarBannerAlternado()
        }
    }

    private fun iniciarCicloBanner() {
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            while (true) {
                if (listaBannerItems.isNotEmpty()) {
                    exibirItemNoBanner(listaBannerItems[currentBannerIndex])
                    delay(10000) // 10 segundos
                    currentBannerIndex = (currentBannerIndex + 1) % listaBannerItems.size
                } else {
                    break
                }
            }
        }
    }

    private fun exibirItemNoBanner(item: Any) {
        // ✅ CRUCIAL: Cancela o carregamento da imagem anterior
        tmdbJob?.cancel()

        val titulo: String
        val id: Int
        val rating: String
        val icon: String
        val timestamp: Long
        val isSeries: Boolean

        if (item is VodEntity) {
            titulo = item.name
            id = item.stream_id
            rating = item.rating ?: "0.0"
            icon = item.stream_icon ?: ""
            timestamp = item.added
            isSeries = false
        } else if (item is SeriesEntity) {
            titulo = item.name
            id = item.series_id
            rating = item.rating ?: "0.0"
            icon = item.cover ?: ""
            timestamp = item.last_modified
            isSeries = true
        } else return

        // Extrai o ano
        val cal = Calendar.getInstance()
        cal.time = Date(timestamp * 1000L)
        val ano = cal.get(Calendar.YEAR).toString()

        // ✅ LIMPEZA DE TELA OBRIGATÓRIA (Resolve o nome "preso")
        binding.imgBannerLogo.setImageDrawable(null) 
        binding.imgBannerLogo.visibility = View.GONE 
        binding.tvBannerTitle.visibility = View.VISIBLE 
        binding.tvBannerTitle.text = titulo // Força o texto novo imediatamente

        binding.tvBannerOverview.text = if (isSeries) "Série em Destaque" else "Filme em Destaque"
        
        // Configura Nota e Botão (se existirem no XML)
        try {
            val tvRating = findViewById<TextView>(R.id.tvBannerRating)
            tvRating?.text = "⭐ $rating  |  📅 $ano"
            tvRating?.visibility = View.VISIBLE
            
            val btnPlay = findViewById<View>(R.id.btnBannerPlay)
            btnPlay?.visibility = View.VISIBLE
            btnPlay?.setOnClickListener { abrirDetalhesReal(id, titulo, icon, isSeries) }
        } catch (e: Exception) {}

        // Busca imagens no TMDB
        buscarImagemBackgroundTMDB(titulo, isSeries, icon)

        // Clique do Banner
        binding.cardBanner.setOnClickListener {
            abrirDetalhesReal(id, titulo, icon, isSeries)
        }
    }

    private fun abrirDetalhesReal(id: Int, nome: String, icon: String, isSeries: Boolean) {
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

        // ✅ Job controlado para evitar sobreposição
        tmdbJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(url).readText()
                val results = JSONObject(response).getJSONArray("results")
                if (results.length() > 0) {
                    val backdropPath = results.getJSONObject(0).optString("backdrop_path")
                    val tmdbId = results.getJSONObject(0).optString("id")
                    
                    withContext(Dispatchers.Main) {
                        Glide.with(this@HomeActivity)
                            .load("https://image.tmdb.org/t/p/original$backdropPath")
                            .centerCrop()
                            .placeholder(binding.imgBanner.drawable)
                            .into(binding.imgBanner)
                        
                        buscarLogoOverlayHome(tmdbId, tipo, nome)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@HomeActivity).load(fallback).centerCrop().into(binding.imgBanner)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Glide.with(this@HomeActivity).load(fallback).centerCrop().into(binding.imgBanner)
                }
            }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, rawName: String) {
        // Segue no mesmo job do pai ou cria sub-job
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                val imagesJson = URL(imagesUrl).readText()
                val imagesObj = JSONObject(imagesJson)

                if (imagesObj.has("logos")) {
                    val logos = imagesObj.getJSONArray("logos")
                    if (logos.length() > 0) {
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

                        withContext(Dispatchers.Main) {
                            binding.tvBannerTitle.visibility = View.GONE
                            binding.imgBannerLogo.visibility = View.VISIBLE
                            Glide.with(this@HomeActivity).load(fullLogoUrl).into(binding.imgBannerLogo)
                        }
                        return@launch
                    }
                }
                
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.visibility = View.VISIBLE
                    binding.imgBannerLogo.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.visibility = View.VISIBLE
                    binding.imgBannerLogo.visibility = View.GONE
                }
            }
        }
    }

    // ✅ SINCRONIZAÇÃO EM SEGUNDO PLANO (MANTIDA ORIGINAL)
    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Sincroniza Filmes (VOD)
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

                // Sincroniza Séries
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

                // Sincroniza Canais (LIVE)
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

                // Atualiza a tela sem travar
                withContext(Dispatchers.Main) {
                    carregarDadosLocaisImediato()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ FIREBASE REMOTE CONFIG (MANTIDA ORIGINAL)
    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val bannerUrl = remoteConfig.getString("url_banner_promocional")
                val bannerTitle = remoteConfig.getString("titulo_banner_promocional")
                if (bannerUrl.isNotEmpty()) {
                    // Pausa o banner automático se tiver promo
                    bannerJob?.cancel()
                    runOnUiThread {
                        binding.tvBannerTitle.visibility = View.VISIBLE
                        binding.tvBannerTitle.text = bannerTitle.ifEmpty { "Destaque VLTV" }
                        binding.tvBannerOverview.text = ""
                        binding.imgBannerLogo.visibility = View.GONE
                        Glide.with(this@HomeActivity).load(bannerUrl).centerCrop().into(binding.imgBanner)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // O TMDB carrega primeiro (fallback) se não tiver itens locais
        if (listaBannerItems.isEmpty()) {
            carregarBannerAlternado()
        }
        
        try {
            binding.etSearch.setText("")
            binding.etSearch.clearFocus()
            binding.etSearch.background = null
            binding.etSearch.animate().scaleX(1f).scaleY(1f).setDuration(0).start()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            binding.cardBanner.requestFocus()
            carregarContinuarAssistindoLocal()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupClicks() {
        fun isTelevisionDevice(): Boolean {
            return packageManager.hasSystemFeature("android.hardware.type.television") ||
                    packageManager.hasSystemFeature("android.software.leanback") ||
                    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        }

        binding.etSearch.isFocusable = true
        binding.etSearch.isFocusableInTouchMode = false
        binding.etSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            intent.putExtra("PROFILE_NAME", currentProfile)
            startActivity(intent)
        }
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.etSearch.setBackgroundResource(R.drawable.bg_login_input_premium)
                binding.etSearch.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
            } else {
                binding.etSearch.background = null
                binding.etSearch.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
        binding.btnSettings.isFocusable = true
        binding.btnSettings.isFocusableInTouchMode = true
        binding.btnSettings.setOnFocusChangeListener { _, hasFocus ->
            binding.btnSettings.scaleX = if (hasFocus) 1.15f else 1f
            binding.btnSettings.scaleY = if (hasFocus) 1.15f else 1f
            binding.btnSettings.setColorFilter(if (hasFocus) 0xFF00C6FF.toInt() else 0xFFFFFFFF.toInt())
        }
        val cards = listOf(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardKids, binding.cardBanner)
        cards.forEach { card ->
            card.isFocusable = true
            card.isClickable = true
            card.setOnFocusChangeListener { _, hasFocus ->
                // ✅ Ajuste de foco que evita sobreposição
                if (hasFocus) {
                   card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                   if (card.id == R.id.cardBanner) {
                       try { findViewById<View>(R.id.btnBannerPlay)?.alpha = 1.0f } catch (e: Exception) {}
                   }
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
                    R.id.cardBanner -> { /* Ação já configurada no exibirItemNoBanner */ }
                }
            }
        }
        if (isTelevisionDevice()) {
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.etSearch.requestFocus()
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
                    binding.etSearch.requestFocus()
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
                    binding.btnSettings.requestFocus()
                    true
                } else false
            }
            binding.cardKids.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.btnSettings.requestFocus()
                    true
                } else false
            }
            binding.etSearch.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        val intent = Intent(this, SearchActivity::class.java)
                        intent.putExtra("initial_query", "")
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        startActivity(intent)
                    }
                    true
                } else false
            }
        }
        binding.btnSettings.setOnClickListener {
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
        binding.cardBanner.requestFocus()
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
                    val tituloOriginal = if (item.has("title")) item.getString("title") else if (item.has("name")) item.getString("name") else "Destaque"
                    val overview = if (item.has("overview")) item.getString("overview") else ""
                    val backdropPath = item.getString("backdrop_path")
                    val prefixo = if (tipoAtual == "movie") "Filme em Alta: " else "Série em Alta: "
                    val tmdbId = item.getString("id")
                    if (backdropPath != "null" && backdropPath.isNotBlank()) {
                        val imageUrl = "https://image.tmdb.org/t/p/original$backdropPath"
                        withContext(Dispatchers.Main) {
                            binding.tvBannerTitle.text = "$prefixo$tituloOriginal"
                            binding.tvBannerOverview.text = overview
                            Glide.with(this@HomeActivity)
                                .load(imageUrl)
                                .centerCrop()
                                .into(binding.imgBanner)
                        }
                        buscarLogoOverlayHome(tmdbId, tipoAtual, tituloOriginal)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String, rawName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                val imagesJson = URL(imagesUrl).readText()
                val imagesObj = JSONObject(imagesJson)
                if (imagesObj.has("logos")) {
                    val logos = imagesObj.getJSONArray("logos")
                    if (logos.length() > 0) {
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
                        withContext(Dispatchers.Main) {
                            binding.tvBannerTitle.visibility = View.GONE
                            binding.imgBannerLogo.visibility = View.VISIBLE
                            Glide.with(this@HomeActivity).load(fullLogoUrl).into(binding.imgBannerLogo)
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.visibility = View.VISIBLE
                    binding.imgBannerLogo.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.visibility = View.VISIBLE
                    binding.imgBannerLogo.visibility = View.GONE
                }
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
        // ✅ CHAMADA PARA O HISTÓRICO LOCAL
        carregarContinuarAssistindoLocal()
        // ✅ CHAMADA PARA FILMES RECENTES
        carregarFilmesRecentes()
        // ✅ CHAMADA PARA SÉRIES RECENTES
        carregarSeriesRecentes()
    }

    private fun carregarFilmesRecentes() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                val urlString = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val response = URL(urlString).readText()
                val jsonArray = org.json.JSONArray(response)
                val rawList = mutableListOf<JSONObject>()
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val nome = obj.optString("name").uppercase()
                    val eAdulto = palavrasProibidas.any { nome.contains(it) }
                    if (!eAdulto) {
                        rawList.add(obj)
                    }
                }
                val sortedList = rawList.sortedWith(compareByDescending<JSONObject> { it.optLong("added") }.thenByDescending { it.optInt("stream_id") })
                val finalRecentList = mutableListOf<VodItem>()
                val limit = if (sortedList.size > 20) 20 else sortedList.size
                for (i in 0 until limit) {
                    val obj = sortedList[i]
                    finalRecentList.add(VodItem(
                        id = obj.optString("stream_id"),
                        name = obj.optString("name"),
                        streamIcon = obj.optString("stream_icon")
                    ))
                }
                withContext(Dispatchers.Main) {
                    binding.rvRecentlyAdded.adapter = HomeRowAdapter(finalRecentList) { selectedItem ->
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
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                val urlString = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                val response = URL(urlString).readText()
                val jsonArray = org.json.JSONArray(response)
                val rawList = mutableListOf<JSONObject>()
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val nome = obj.optString("name").uppercase()
                    val eAdulto = palavrasProibidas.any { nome.contains(it) }
                    if (!eAdulto) {
                        rawList.add(obj)
                    }
                }
                val sortedList = rawList.sortedByDescending { it.optLong("last_modified") }.take(20)
                val finalSeriesList = mutableListOf<VodItem>()
                for (obj in sortedList) {
                    finalSeriesList.add(VodItem(
                        id = obj.optString("series_id"),
                        name = obj.optString("name"),
                        streamIcon = obj.optString("cover")
                    ))
                }
                withContext(Dispatchers.Main) {
                    binding.rvRecentSeries.adapter = HomeRowAdapter(finalSeriesList) { selectedItem ->
                        val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                        intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                        intent.putExtra("name", selectedItem.name)
                        intent.putExtra("icon", selectedItem.streamIcon)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("is_series", true) // ✅ SEMPRE TRUE PARA SÉRIES
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun carregarContinuarAssistindoLocal() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val historyList = mutableListOf<VodItem>()
        val keyHistory = "${currentProfile}_local_history_ids"
        val savedIdsSet = prefs.getStringSet(keyHistory, emptySet()) ?: emptySet()
        for (id in savedIdsSet) {
            val name = prefs.getString("${currentProfile}_history_name_$id", "") ?: ""
            val icon = prefs.getString("${currentProfile}_history_icon_$id", "") ?: ""
            if (name.isNotEmpty()) {
                historyList.add(VodItem(id = id, name = name, streamIcon = icon))
            }
        }
        if (historyList.isNotEmpty()) {
            binding.rvContinueWatching.visibility = View.VISIBLE
            binding.rvContinueWatching.adapter = HomeRowAdapter(historyList.reversed()) { selectedItem ->
                val isSeriesStored = prefs.getBoolean("${currentProfile}_history_is_series_${selectedItem.id}", false)
                val intent = if (isSeriesStored) {
                    Intent(this, SeriesDetailsActivity::class.java).apply {
                        putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                    }
                } else {
                    Intent(this, DetailsActivity::class.java).apply {
                        putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                    }
                }
                intent.putExtra("name", selectedItem.name)
                intent.putExtra("icon", selectedItem.streamIcon)
                intent.putExtra("PROFILE_NAME", currentProfile)
                intent.putExtra("is_series", isSeriesStored)
                startActivity(intent)
            }
        } else {
            binding.rvContinueWatching.visibility = View.GONE
        }
    }
}
