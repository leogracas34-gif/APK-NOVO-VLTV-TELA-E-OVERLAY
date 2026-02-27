package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

    // SEUS 6 SERVIDORES XTREAM
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
        
        // ✅ 1. CHECAGEM SILENCIOSA (Antes de desenhar a tela)
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            verificarEIniciar(savedDns!!, savedUser!!, savedPass!!)
        } else {
            // ✅ 2. SÓ DESENHA SE NÃO TIVER LOGIN
            binding = ActivityLoginBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // MODO IMERSIVO
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
            
            setupUI()
        }
    }

    private fun setupUI() {
        binding.etUsername.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etPassword.requestFocus()
                true
            } else false
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                binding.btnLogin.performClick()
                true
            } else false
        }

        binding.btnLogin.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha usuário e senha!", Toast.LENGTH_SHORT).show()
            } else {
                iniciarLoginTurbo(user, pass)
            }
        }
        
        binding.etUsername.requestFocus()
    }

    // Auto-login se já tiver credenciais salvas
    private fun verificarEIniciar(dns: String, user: String, pass: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val temFilmes = db.streamDao().getVodCount() > 0
            
            if (temFilmes) {
                withContext(Dispatchers.Main) { decidirProximaTela() }
            } else {
                withContext(Dispatchers.Main) { 
                    binding = ActivityLoginBinding.inflate(layoutInflater)
                    setContentView(binding.root)
                    binding.progressBar.visibility = View.VISIBLE
                    Toast.makeText(this@LoginActivity, "Atualizando conteúdo...", Toast.LENGTH_SHORT).show()
                }
                preCarregarConteudoInicial(dns, user, pass)
                withContext(Dispatchers.Main) { decidirProximaTela() }
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
                // 🔥 CORRIDA DE DNS (acha o mais rápido)
                val deferreds = SERVERS.map { url -> async { testarConexaoIndividual(url, user, pass) } }
                var dnsVencedor: String? = null
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < 10000) {
                    val completed = deferreds.filter { it.isCompleted }
                    for (job in completed) {
                        val result = job.getCompleted()
                        if (result != null) {
                            dnsVencedor = result
                            break
                        }
                    }
                    if (dnsVencedor != null) break
                    delay(100)
                }

                deferreds.forEach { if (it.isActive) it.cancel() }

                if (dnsVencedor != null) {
                    // 🔥 SALVA E CONFIGURA API PARA MULTI-SERVIDOR
                    salvarCredenciais(dnsVencedor!!, user, pass)
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Conectando à VLTV+", Toast.LENGTH_LONG).show()
                    }
                    
                    preCarregarConteudoInicial(dnsVencedor!!, user, pass)
                    withContext(Dispatchers.Main) { decidirProximaTela() }
                } else {
                    withContext(Dispatchers.Main) {
                        mostrarErro("Nenhum servidor respondeu. Verifique dados.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarErro("Erro: ${e.message}")
                }
            }
        }
    }

    // 🔥 PRÉ-CARREGA 60 ITENS DE CADA PARA HOME RÁPIDA
    private suspend fun preCarregarConteudoInicial(dns: String, user: String, pass: String) {
        try {
            val db = AppDatabase.getDatabase(this)
            
            // FILMES
            try {
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val response = URL(vodUrl).readText()
                val jsonArray = JSONArray(response)
                val batch = mutableListOf<VodEntity>()
                val limit = minOf(60, jsonArray.length())
                
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
                if (batch.isNotEmpty()) db.streamDao().insertVodStreams(batch)
            } catch (e: Exception) { e.printStackTrace() }

            // SÉRIES
            try {
                val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                val response = URL(seriesUrl).readText()
                val jsonArray = JSONArray(response)
                val batch = mutableListOf<SeriesEntity>()
                val limit = minOf(60, jsonArray.length())
                
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
                if (batch.isNotEmpty()) db.streamDao().insertSeriesStreams(batch)
            } catch (e: Exception) { e.printStackTrace() }

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun testarConexaoIndividual(baseUrl: String, user: String, pass: String): String? {
        val urlLimpa = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val apiLogin = "$urlLimpa/player_api.php?username=$user&password=$pass"

        return try {
            val request = Request.Builder().url(apiLogin).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("user_info") && body.contains("server_info")) {
                        return urlLimpa
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // 🔥 CORRIGIDO: Configura XtreamApi para multi-servidor
    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        
        // 🔥 CHAVE DA CORREÇÃO: Configura API para o DNS vencedor
        XtreamApi.setBaseUrl("$dns/")
    }

    private fun decidirProximaTela() {
        // Detecta se é TV (Geralmente não possui tela de toque)
        val isTV = !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

        if (isTV) {
            // Lógica para TV: Pula Perfis e vai para Home
            val intent = Intent(this, HomeActivity::class.java)
            intent.putExtra("PROFILE_NAME", "TV_Box") // Perfil fixo padrão para TV
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } else {
            // Lógica para Celular: Vai para seleção de perfis
            val intent = Intent(this, ProfilesActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun abrirHomeDireto() {
        val intent = Intent(this, ProfilesActivity::class.java)
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
}
