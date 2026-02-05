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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import kotlin.random.Random

// ✅ FIREBASE
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    private var currentProfile: String = "Padrao"
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"
        
        // Tela cheia imersiva
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setupClicks()
        setupFirebaseRemoteConfig()
        
        // 1. Carrega o que já existe no banco (Instantâneo)
        carregarDadosLocaisImediato()
        
        // 2. Sincroniza com a API sem travar a aba de Filmes
        sincronizarTurbinado()
        
        carregarContinuarAssistindoLocal()

        // Lógica Kids
        if (intent.getBooleanExtra("IS_KIDS_MODE", false)) {
            currentProfile = "Kids"
            binding.root.postDelayed({ binding.cardKids.performClick() }, 500)
        }
    }

    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            val localMovies = database.streamDao().getRecentVods(40)
            val localSeries = database.streamDao().getRecentSeries(40)

            withContext(Dispatchers.Main) {
                if (localMovies.isNotEmpty()) {
                    val items = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }
                    binding.rvRecentlyAdded.adapter = HomeRowAdapter(items) { abrirDetalhesFilme(it) }
                }
                if (localSeries.isNotEmpty()) {
                    val items = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }
                    binding.rvRecentSeries.adapter = HomeRowAdapter(items) { abrirDetalhesSerie(it) }
                }
            }
        }
    }

    private fun sincronizarTurbinado() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        if (user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

                // ✅ FILMES: Carregamento Assíncrono para não travar
                val responseVod = XtreamApi.service.getAllVodStreams(user, pass).execute()
                if (responseVod.isSuccessful && responseVod.body() != null) {
                    val listaTotal = responseVod.body()!!
                    
                    // Filtra e prepara os dados
                    val filtrados = listaTotal.filter { vod -> 
                        !palavrasProibidas.any { vod.name.uppercase().contains(it) } 
                    }

                    // 🔥 MOSTRA NA TELA IMEDIATAMENTE (Primeiros 40)
                    withContext(Dispatchers.Main) {
                        val visualItems = filtrados.take(40).map { 
                            VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") 
                        }
                        binding.rvRecentlyAdded.adapter = HomeRowAdapter(visualItems) { abrirDetalhesFilme(it) }
                    }

                    // Salva no banco em "silêncio" (Background pesado)
                    val entities = filtrados.map { 
                        VodEntity(it.stream_id, it.name, it.name, it.stream_icon, it.container_extension, it.rating, "0", System.currentTimeMillis()) 
                    }
                    database.streamDao().insertVodStreams(entities)
                }

                // ✅ SÉRIES: Mesma lógica
                val responseSeries = XtreamApi.service.getAllSeries(user, pass).execute()
                if (responseSeries.isSuccessful && responseSeries.body() != null) {
                    val listaTotal = responseSeries.body()!!
                    val filtradas = listaTotal.filter { s -> !palavrasProibidas.any { s.name.uppercase().contains(it) } }

                    withContext(Dispatchers.Main) {
                        val visualItems = filtradas.take(40).map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }
                        binding.rvRecentSeries.adapter = HomeRowAdapter(visualItems) { abrirDetalhesSerie(it) }
                    }

                    val entities = filtradas.map { 
                        SeriesEntity(it.series_id, it.name, it.cover, it.rating, "0", System.currentTimeMillis()) 
                    }
                    database.streamDao().insertSeriesStreams(entities)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- MANTIDAS TODAS AS FUNÇÕES DE BANNER, CLICKS E NAVEGAÇÃO ---
    private fun abrirDetalhesFilme(item: VodItem) {
        startActivity(Intent(this, DetailsActivity::class.java).apply {
            putExtra("stream_id", item.id.toIntOrNull() ?: 0)
            putExtra("name", item.name)
            putExtra("icon", item.streamIcon)
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("is_series", false)
        })
    }

    private fun abrirDetalhesSerie(item: VodItem) {
        startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
            putExtra("series_id", item.id.toIntOrNull() ?: 0)
            putExtra("name", item.name)
            putExtra("icon", item.streamIcon)
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("is_series", true)
        })
    }

    private fun setupFirebaseRemoteConfig() {
        val rc = Firebase.remoteConfig
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 60 })
        rc.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val url = rc.getString("url_banner_promocional")
                val title = rc.getString("titulo_banner_promocional")
                if (url.isNotEmpty()) {
                    runOnUiThread {
                        binding.tvBannerTitle.text = title.ifEmpty { "Destaque VLTV" }
                        Glide.with(this).load(url).centerCrop().into(binding.imgBanner)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        carregarBannerAlternado()
        carregarContinuarAssistindoLocal()
    }

    private fun setupClicks() {
        binding.etSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) })
        }

        val cards = listOf(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardKids)
        cards.forEach { card ->
            card.setOnClickListener {
                val intent = when(card.id) {
                    R.id.cardLiveTv -> Intent(this, LiveTvActivity::class.java).apply { putExtra("SHOW_PREVIEW", true) }
                    R.id.cardMovies -> Intent(this, VodActivity::class.java)
                    R.id.cardSeries -> Intent(this, SeriesActivity::class.java)
                    R.id.cardKids -> Intent(this, KidsActivity::class.java).apply { putExtra("PROFILE_NAME", "Kids") }
                    else -> null
                }
                intent?.let { 
                    it.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(it) 
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Opções").setItems(arrayOf("Trocar Perfil", "Downloads", "Configurações", "Sair")) { _, i ->
                when(i) {
                    0 -> finish()
                    1 -> startActivity(Intent(this, DownloadsActivity::class.java))
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                    3 -> mostrarDialogoSair()
                }
            }.show()
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this).setTitle("Sair").setMessage("Deseja sair?").setPositiveButton("Sim") { _, _ ->
            getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
            finish()
        }.setNegativeButton("Não", null).show()
    }

    private fun carregarBannerAlternado() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.themoviedb.org/3/trending/all/day?api_key=$TMDB_API_KEY&language=pt-BR"
                val json = JSONObject(URL(url).readText())
                val item = json.getJSONArray("results").getJSONObject(Random.nextInt(10))
                val title = if (item.has("title")) item.getString("title") else item.getString("name")
                val path = item.getString("backdrop_path")
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.text = title
                    Glide.with(this@HomeActivity).load("https://image.tmdb.org/t/p/original$path").into(binding.imgBanner)
                }
            } catch(e: Exception) {}
        }
    }

    private fun carregarContinuarAssistindoLocal() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val historyList = mutableListOf<VodItem>()
        val savedIdsSet = prefs.getStringSet("${currentProfile}_local_history_ids", emptySet()) ?: emptySet()
        for (id in savedIdsSet) {
            val name = prefs.getString("${currentProfile}_history_name_$id", "") ?: ""
            val icon = prefs.getString("${currentProfile}_history_icon_$id", "") ?: ""
            if (name.isNotEmpty()) historyList.add(VodItem(id, name, icon))
        }
        binding.rvContinueWatching.visibility = if (historyList.isNotEmpty()) View.VISIBLE else View.GONE
        if (historyList.isNotEmpty()) {
            binding.rvContinueWatching.adapter = HomeRowAdapter(historyList.reversed()) { item ->
                val isSeries = prefs.getBoolean("${currentProfile}_history_is_series_${item.id}", false)
                if (isSeries) abrirDetalhesSerie(item) else abrirDetalhesFilme(item)
            }
        }
    }
}
