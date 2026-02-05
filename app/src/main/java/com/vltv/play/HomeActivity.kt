package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.LiveStreamEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
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

    // ✅ INSTÂNCIA DO BANCO DE DADOS ROOM (MANTIDA)
    private val database by lazy { AppDatabase.getDatabase(this) }

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
        
        // ✅ 1. CARREGAMENTO IMEDIATO (INSTANTÂNEO DO BANCO)
        carregarDadosLocaisImediato()
        
        // ✅ 2. INICIA DOWNLOAD SILENCIOSO EM BACKGROUND (TURBINADO)
        sincronizarConteudoSilenciosamente()

        // ✅ 3. CARREGA O HISTÓRICO LOCAL
        carregarContinuarAssistindoLocal()

        // ✅ LÓGICA KIDS: Verifica se o perfil selecionado foi o Kids
        val isKidsMode = intent.getBooleanExtra("IS_KIDS_MODE", false)
        if (isKidsMode) {
            currentProfile = "Kids" // Garante o nome do perfil Kids
            binding.root.postDelayed({
                binding.cardKids.performClick()
                Toast.makeText(this, "Modo Kids Ativado", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }

    // ✅ LÊ DO BANCO DE DADOS PARA A TELA (ZERO DELAY)
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
                            abrirDetalhesFilme(selectedItem)
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        binding.rvRecentSeries.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            abrirDetalhesSerie(selectedItem)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ SINCRONIZAÇÃO EM SEGUNDO PLANO (OTIMIZADA COM XTREAM API + GZIP)
    private fun sincronizarConteudoSilenciosamente() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

                // --- 1. Sincroniza Filmes (VOD) usando API Rápida ---
                val responseVod = XtreamApi.service.getAllVodStreams(user, pass).execute()
                if (responseVod.isSuccessful && responseVod.body() != null) {
                    val listApi = responseVod.body()!!
                    val vodEntities = listApi.filter { vod ->
                        !palavrasProibidas.any { vod.name.uppercase().contains(it) }
                    }.map { vod ->
                        VodEntity(
                            stream_id = vod.stream_id,
                            name = vod.name,
                            title = vod.name,
                            stream_icon = vod.stream_icon,
                            container_extension = vod.container_extension,
                            rating = vod.rating,
                            category_id = "0", // Otimização
                            added = System.currentTimeMillis() // Salva data atual
                        )
                    }
                    database.streamDao().insertVodStreams(vodEntities)
                }

                // --- 2. Sincroniza Séries usando API Rápida ---
                val responseSeries = XtreamApi.service.getAllSeries(user, pass).execute()
                if (responseSeries.isSuccessful && responseSeries.body() != null) {
                    val listApi = responseSeries.body()!!
                    val seriesEntities = listApi.filter { serie ->
                        !palavrasProibidas.any { serie.name.uppercase().contains(it) }
                    }.map { serie ->
                        SeriesEntity(
                            series_id = serie.series_id,
                            name = serie.name,
                            cover = serie.cover,
                            rating = serie.rating,
                            category_id = "0",
                            last_modified = System.currentTimeMillis()
                        )
                    }
                    database.streamDao().insertSeriesStreams(seriesEntities)
                }

                // --- 3. Sincroniza Canais (LIVE) ---
                // Para canais, se a lista for muito grande, o GET padrão resolve bem
                val responseLive = XtreamApi.service.getLiveStreams(user, pass, categoryId = "").execute() // Pega tudo se a API permitir vazio ou usar getAll se tiver
                // Se a API não tiver getAll para live, mantemos a lógica antiga ou usamos getLiveStreams generico
                if (responseLive.isSuccessful && responseLive.body() != null) {
                    val listApi = responseLive.body()!!
                    val liveEntities = listApi.map { canal ->
                         LiveStreamEntity(
                            stream_id = canal.stream_id,
                            name = canal.name,
                            stream_icon = canal.stream_icon,
                            epg_channel_id = canal.epg_channel_id,
                            category_id = "0" // Ajuste conforme necessário
                        )
                    }
                    database.streamDao().insertLiveStreams(liveEntities)
                }

                // --- 4. Atualiza a tela sem travar ---
                withContext(Dispatchers.Main) {
                    carregarDadosLocaisImediato()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ AUXILIARES DE NAVEGAÇÃO
    private fun abrirDetalhesFilme(item: VodItem) {
        val intent = Intent(this, DetailsActivity::class.java)
        intent.putExtra("stream_id", item.id.toIntOrNull() ?: 0)
        intent.putExtra("name", item.name)
        intent.putExtra("icon", item.streamIcon)
        intent.putExtra("PROFILE_NAME", currentProfile)
        intent.putExtra("is_series", false)
        startActivity(intent)
    }

    private fun abrirDetalhesSerie(item: VodItem) {
        val intent = Intent(this, SeriesDetailsActivity::class.java)
        intent.putExtra("series_id", item.id.toIntOrNull() ?: 0)
        intent.putExtra("name", item.name)
        intent.putExtra("icon", item.streamIcon)
        intent.putExtra("PROFILE_NAME", currentProfile)
        intent.putExtra("is_series", true)
        startActivity(intent)
    }

    // ✅ ESTRUTURA PARA BANNER DINÂMICO (MANTIDA ORIGINAL)
    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val bannerUrl = remoteConfig.getString("url_banner_promocional")
                val bannerTitle = remoteConfig.getString("titulo_banner_promocional")
                
                if (bannerUrl.isNotEmpty()) {
                    runOnUiThread {
                        binding.tvBannerTitle.visibility = View.VISIBLE
                        binding.tvBannerTitle.text = bannerTitle.ifEmpty { "Destaque VLTV" }
                        binding.tvBannerOverview.text = ""
                        binding.imgBannerLogo.visibility = View.GONE
                        
                        Glide.with(this@HomeActivity)
                            .load(bannerUrl)
                            .centerCrop()
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .into(binding.imgBanner)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        carregarBannerAlternado()

        try {
            binding.etSearch.setText("")
            binding.etSearch.clearFocus()
            binding.etSearch.background = null 
            binding.etSearch.animate().scaleX(1f).scaleY(1f).setDuration(0).start()

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

            binding.cardBanner.requestFocus()
            
            // RECARREGA O CONTINUAR ASSISTINDO
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
                card.scaleX = if (hasFocus) 1.05f else 1f
                card.scaleY = if (hasFocus) 1.05f else 1f
                card.elevation = if (hasFocus) 20f else 5f
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
                    R.id.cardBanner -> { /* ação banner */ }
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
                intent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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

    // CARREGA CONTINUAR ASSISTINDO (MANTIDO)
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
                
                if (isSeriesStored) {
                    abrirDetalhesSerie(selectedItem)
                } else {
                    abrirDetalhesFilme(selectedItem)
                }
            }
        } else {
            binding.rvContinueWatching.visibility = View.GONE
        }
    }
}
