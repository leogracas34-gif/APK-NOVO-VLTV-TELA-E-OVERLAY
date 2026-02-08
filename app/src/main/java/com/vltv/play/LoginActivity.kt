package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vltv.play.databinding.ActivityLoginBinding
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URL
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    // SEUS 6 SERVIDORES XTREAM (MANTIDOS)
    private val SERVERS = listOf(
        "http://tvblack.shop",
        "http://redeinternadestiny.top",
        "http://fibercdn.sbs",
        "http://blackstartv.shop",
        "http://blackdns.shop",
        "http://blackdeluxe.shop"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO IMERSIVO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        // Se já tem login, tenta abrir direto. 
        // A Home vai lidar se precisar carregar mais coisas.
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            abrirHomeDireto()
            return
        }

        setupTouchAndDpad()

        binding.btnLogin.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            } else {
                iniciarLoginTurbo(user, pass)
            }
        }
    }

    private fun iniciarLoginTurbo(user: String, pass: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        binding.etUsername.isEnabled = false
        binding.etPassword.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. CORRIDA DE DNS (Acha o servidor mais rápido)
                val tarefas = SERVERS.map { url ->
                    async { testarConexaoIndividual(url, user, pass) }
                }

                var dnsVencedor: String? = null
                val inicio = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - inicio < 10000) { 
                    val concluidos = tarefas.filter { it.isCompleted }
                    for (job in concluidos) {
                        val result = job.getCompleted()
                        if (result != null) {
                            dnsVencedor = result
                            break
                        }
                    }
                    if (dnsVencedor != null) break
                    delay(100)
                }
                tarefas.forEach { if (it.isActive) it.cancel() }

                if (dnsVencedor != null) {
                    salvarCredenciais(dnsVencedor!!, user, pass)
                    
                    // 2. PRÉ-CARREGAMENTO (O Segredo para a Home não "pular")
                    // Baixa apenas os primeiros itens para a Home abrir bonita
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Preparando seu ambiente...", Toast.LENGTH_SHORT).show()
                    }
                    preCarregarConteudoInicial(dnsVencedor!!, user, pass)
                    
                    withContext(Dispatchers.Main) {
                        abrirHomeDireto()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        mostrarErro("Falha ao conectar. Verifique seus dados.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarErro("Erro: ${e.message}")
                }
            }
        }
    }

    // Baixa apenas 60 Filmes e 60 Séries para garantir que a Home abra cheia
    private suspend fun preCarregarConteudoInicial(dns: String, user: String, pass: String) {
        try {
            val db = AppDatabase.getDatabase(this)
            
            // --- FILMES (Pega só os primeiros 60) ---
            try {
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val response = URL(vodUrl).readText()
                val jsonArray = JSONArray(response)
                val batch = mutableListOf<VodEntity>()
                
                // ✅ CORREÇÃO AQUI: jsonArray.length() com parênteses
                val limit = if (jsonArray.length() > 60) 60 else jsonArray.length()
                
                for (i in 0 until limit) {
                    val obj = jsonArray.getJSONObject(i)
                    batch.add(VodEntity(
                        stream_id = obj.optInt("stream_id"),
                        name = obj.optString("name"),
                        title = obj.optString("name"),
                        stream_icon = obj.optString("stream_icon"),
                        container_extension = obj.optString("container_extension"),
                        rating = obj.optString("rating"),
                        category_id = obj.optString("category_id"),
                        added = obj.optLong("added")
                    ))
                }
                if (batch.isNotEmpty()) {
                    db.streamDao().insertVodStreams(batch)
                }
            } catch (e: Exception) { e.printStackTrace() }

            // --- SÉRIES (Pega só as primeiras 60) ---
            try {
                val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                val response = URL(seriesUrl).readText()
                val jsonArray = JSONArray(response)
                val batch = mutableListOf<SeriesEntity>()
                
                // ✅ CORREÇÃO AQUI: jsonArray.length() com parênteses
                val limit = if (jsonArray.length() > 60) 60 else jsonArray.length()
                
                for (i in 0 until limit) {
                    val obj = jsonArray.getJSONObject(i)
                    batch.add(SeriesEntity(
                        series_id = obj.optInt("series_id"),
                        name = obj.optString("name"),
                        cover = obj.optString("cover"),
                        rating = obj.optString("rating"),
                        category_id = obj.optString("category_id"),
                        last_modified = obj.optLong("last_modified")
                    ))
                }
                if (batch.isNotEmpty()) {
                    db.streamDao().insertSeriesStreams(batch)
                }
            } catch (e: Exception) { e.printStackTrace() }

        } catch (e: Exception) {
            // Se der erro no pré-carregamento, abre a Home igual (ela tenta baixar depois)
            e.printStackTrace()
        }
    }

    private fun testarConexaoIndividual(baseUrl: String, user: String, pass: String): String? {
        val urlLimpa = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val apiLogin = "$urlLimpa/player_api.php?username=$user&password=$pass"

        return try {
            val request = Request.Builder().url(apiLogin).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("\"auth\":1") || (body.contains("user_info") && body.contains("server_info"))) {
                        return urlLimpa
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        XtreamApi.setBaseUrl(if (dns.endsWith("/")) dns else "$dns/")
    }

    private fun abrirHomeDireto() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun mostrarErro(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnLogin.isEnabled = true
        binding.etUsername.isEnabled = true
        binding.etPassword.isEnabled = true
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun setupTouchAndDpad() {
        val premiumFocusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                v.isSelected = true
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                v.isSelected = false
            }
        }

        binding.etUsername.onFocusChangeListener = premiumFocusListener
        binding.etPassword.onFocusChangeListener = premiumFocusListener
        binding.btnLogin.onFocusChangeListener = premiumFocusListener
        
        binding.btnLogin.isFocusable = true
        binding.btnLogin.isFocusableInTouchMode = true
        
        binding.etUsername.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                binding.etPassword.requestFocus()
                true
            } else false
        }
        binding.etPassword.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                binding.btnLogin.performClick()
                true
            } else false
        }
        binding.etUsername.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
