package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.vltv.play.databinding.ActivityHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import kotlin.random.Random

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Modo Full Screen (Imersivo)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        DownloadHelper.registerReceiver(this)

        setupClicks()
    }

    override fun onResume() {
        super.onResume()
        carregarBannerAlternado()
        
        // Reset do estado da busca ao voltar para a Home
        binding.etSearch.setText("")
        binding.etSearch.clearFocus()
        binding.cardBanner.requestFocus()
    }

    private fun setupClicks() {
        // Função para aplicar o efeito PREMIUM de foco
        fun aplicarEfeitoPremium(view: View, scale: Float = 1.15f) {
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .translationZ(15f)
                        .setDuration(250)
                        .start()
                } else {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(250)
                        .start()
                }
            }
        }

        // Aplicando efeito visual nos componentes
        aplicarEfeitoPremium(binding.cardLiveTv)
        aplicarEfeitoPremium(binding.cardMovies)
        aplicarEfeitoPremium(binding.cardSeries)
        aplicarEfeitoPremium(binding.cardKids)
        aplicarEfeitoPremium(binding.cardBanner, 1.03f) 
        aplicarEfeitoPremium(binding.btnSettings)
        aplicarEfeitoPremium(binding.etSearch, 1.05f)

        // Cliques das categorias
        binding.cardLiveTv.setOnClickListener { startActivity(Intent(this, LiveTvActivity::class.java).putExtra("SHOW_PREVIEW", true)) }
        binding.cardMovies.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        binding.cardSeries.setOnClickListener { startActivity(Intent(this, SeriesActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        binding.cardKids.setOnClickListener { startActivity(Intent(this, KidsActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        
        // ✅ CORREÇÃO DA TELA FANTASMA: Escondendo o teclado e limpando foco antes de abrir a busca
        binding.etSearch.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            binding.etSearch.clearFocus()

            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Evita sobreposição visual na transição
            startActivity(intent)
        }

        binding.btnSettings.setOnClickListener {
            val itens = arrayOf("Meus downloads", "Configurações", "Sair")
            AlertDialog.Builder(this)
                .setTitle("Opções")
                .setItems(itens) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, DownloadsActivity::class.java))
                        1 -> startActivity(Intent(this, SettingsActivity::class.java))
                        2 -> mostrarDialogoSair()
                    }
                }.show()
        }
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
                    val tituloOriginal = item.optString("title", item.optString("name", "Destaque"))
                    val overview = item.optString("overview", "")
                    val backdropPath = item.optString("backdrop_path", "")
                    val tmdbId = item.getString("id")

                    if (backdropPath.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            binding.tvBannerTitle.text = tituloOriginal
                            binding.tvBannerOverview.text = overview
                            Glide.with(this@HomeActivity)
                                .load("https://image.tmdb.org/p/original$backdropPath")
                                .centerCrop()
                                .into(binding.imgBanner)
                        }
                        buscarLogoOverlayHome(tmdbId, tipoAtual)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                val imagesObj = JSONObject(URL(imagesUrl).readText())
                val logos = imagesObj.optJSONArray("logos")

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

                    withContext(Dispatchers.Main) {
                        binding.tvBannerTitle.visibility = View.GONE
                        binding.imgBannerLogo.visibility = View.VISIBLE
                        Glide.with(this@HomeActivity).load("https://image.tmdb.org/p/w500$logoPath").into(binding.imgBannerLogo)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.tvBannerTitle.visibility = View.VISIBLE
                        binding.imgBannerLogo.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvBannerTitle.visibility = View.VISIBLE
                    binding.imgBannerLogo.visibility = View.GONE
                }
            }
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair e desconectar?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }.setNegativeButton("Não", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mostrarDialogoSair()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
