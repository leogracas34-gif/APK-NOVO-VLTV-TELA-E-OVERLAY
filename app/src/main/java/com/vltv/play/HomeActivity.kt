package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import kotlin.random.Random

class HomeActivity : AppCompatActivity() {

    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    
    // Views declaradas para uso com findViewById
    private lateinit var etSearch: EditText
    private lateinit var imgBanner: ImageView
    private lateinit var imgBannerLogo: ImageView
    private lateinit var tvBannerTitle: TextView
    private lateinit var tvBannerOverview: TextView
    private lateinit var cardBanner: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Modo Full Screen Imersivo
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        DownloadHelper.registerReceiver(this)

        inicializarViews()
        setupClicks()
    }

    private fun inicializarViews() {
        etSearch = findViewById(R.id.etSearch)
        imgBanner = findViewById(R.id.imgBanner)
        imgBannerLogo = findViewById(R.id.imgBannerLogo)
        tvBannerTitle = findViewById(R.id.tvBannerTitle)
        tvBannerOverview = findViewById(R.id.tvBannerOverview)
        cardBanner = findViewById(R.id.cardBanner)
    }

    override fun onResume() {
        super.onResume()
        carregarBannerAlternado()
        
        // Reset da busca ao voltar
        etSearch.setText("")
        etSearch.clearFocus()
        cardBanner.requestFocus()
    }

    private fun setupClicks() {
        // Efeito visual de foco
        fun aplicarEfeitoPremium(view: View, scale: Float = 1.15f) {
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(scale).scaleY(scale).translationZ(15f).setDuration(250).start()
                } else {
                    v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(250).start()
                }
            }
        }

        val cardLive = findViewById<View>(R.id.cardLiveTv)
        val cardMovies = findViewById<View>(R.id.cardMovies)
        val cardSeries = findViewById<View>(R.id.cardSeries)
        val cardKids = findViewById<View>(R.id.cardKids)
        val btnSettings = findViewById<View>(R.id.btnSettings)

        aplicarEfeitoPremium(cardLive)
        aplicarEfeitoPremium(cardMovies)
        aplicarEfeitoPremium(cardSeries)
        aplicarEfeitoPremium(cardKids)
        aplicarEfeitoPremium(cardBanner, 1.03f)
        aplicarEfeitoPremium(btnSettings)
        aplicarEfeitoPremium(etSearch, 1.05f)

        cardLive.setOnClickListener { startActivity(Intent(this, LiveTvActivity::class.java).putExtra("SHOW_PREVIEW", true)) }
        cardMovies.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        cardSeries.setOnClickListener { startActivity(Intent(this, SeriesActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        cardKids.setOnClickListener { startActivity(Intent(this, KidsActivity::class.java).putExtra("SHOW_PREVIEW", false)) }
        
        // ✅ MATANDO A TELA FANTASMA
        etSearch.setOnClickListener {
            // Esconde o teclado da Home antes de abrir a busca
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
            etSearch.clearFocus()

            val intent = Intent(this, SearchActivity::class.java)
            intent.putExtra("initial_query", "")
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Transição sem vulto
            startActivity(intent)
        }

        btnSettings.setOnClickListener {
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
                val results = JSONObject(jsonTxt).getJSONArray("results")

                if (results.length() > 0) {
                    val item = results.getJSONObject(Random.nextInt(results.length()))
                    val titulo = item.optString("title", item.optString("name", "Destaque"))
                    val overview = item.optString("overview", "")
                    val backdrop = item.optString("backdrop_path", "")
                    val tmdbId = item.getString("id")

                    withContext(Dispatchers.Main) {
                        tvBannerTitle.text = titulo
                        tvBannerOverview.text = overview
                        Glide.with(this@HomeActivity)
                            .load("https://image.tmdb.org/p/original$backdrop")
                            .centerCrop()
                            .into(imgBanner)
                        
                        // Reseta visibilidade antes de buscar a logo
                        tvBannerTitle.visibility = View.VISIBLE
                        imgBannerLogo.visibility = View.GONE
                    }
                    buscarLogoOverlayHome(tmdbId, tipoAtual)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun buscarLogoOverlayHome(tmdbId: String, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,en,null"
                val logos = JSONObject(URL(url).readText()).optJSONArray("logos")

                if (logos != null && logos.length() > 0) {
                    val path = logos.getJSONObject(0).getString("file_path")
                    withContext(Dispatchers.Main) {
                        tvBannerTitle.visibility = View.GONE
                        imgBannerLogo.visibility = View.VISIBLE
                        Glide.with(this@HomeActivity).load("https://image.tmdb.org/p/w500$path").into(imgBannerLogo)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair?")
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
