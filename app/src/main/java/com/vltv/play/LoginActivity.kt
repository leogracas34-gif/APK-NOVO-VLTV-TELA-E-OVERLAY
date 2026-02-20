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

    // ✅ AJUSTADO: Timeouts aumentados e Retry habilitado para aceitar conexões mais rígidas
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ✅ AJUSTADO: User-Agent para que todos os DNS aceitem a conexão do app
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ✅ 1. CHECAGEM SILENCIOSA (Antes de desenhar a tela para evitar a piscada)
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            // Se já está logado, chama a verificação e não executa o resto do onCreate desta tela
            verificarEIniciar(savedDns!!, savedUser!!, savedPass!!)
            // Não colocamos o setContentView aqui para a tela não piscar
        } else {
            // ✅ 2. SÓ DESENHA A TELA SE NÃO TIVER LOGIN (Evita carregar recursos à toa)
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

    // Verifica se precisa carregar dados antes de abrir a Home (Auto-Login)
    private fun verificarEIniciar(dns: String, user: String, pass: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val temFilmes = db.streamDao().getVodCount() > 0
            
            if (temFilmes) {
                // Se já tem filmes no banco, entra NA HORA. Não deixa o cliente esperando.
                withContext(Dispatchers.Main) { abrirHomeDireto() }
            } else {
                // Se o banco tá vazio (ex: primeira vez ou limpou dados), aí sim mostra o carregamento
                withContext(Dispatchers.Main) { 
                    // Como não chamamos o setContentView no onCreate, precisamos chamar agora 
                    // apenas se precisarmos mostrar o progresso
                    binding = ActivityLoginBinding.inflate(layoutInflater)
                    setContentView(binding.root)
                    binding.progressBar.visibility = View.VISIBLE
                    Toast.makeText(this@LoginActivity, "Atualizando conteúdo...", Toast.LENGTH_SHORT).show()
                }
                preCarregarConteudoInicial(dns, user, pass)
                withContext(Dispatchers.Main) { abrirHomeDireto() }
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
                // 1. CORRIDA DE DNS (Agora identificando qual servidor aceita este login específico)
                val deferreds = SERVERS.map { url -> async { testarConexaoIndividual(url, user, pass) } }

                var dnsVencedor: String? = null
                val startTime = System.currentTimeMillis()
                
                // Aguarda até 15 segundos para dar tempo de todos os servidores responderem
                while (System.currentTimeMillis() - startTime < 15000) {
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
                    salvarCredenciais(dnsVencedor!!, user, pass)
                    
                    // 2. PRÉ-CARREGAMENTO (A Mágica acontece aqui)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Preparando seu ambiente...", Toast.LENGTH_LONG).show()
                    }
                    
                    // Baixa os primeiros itens ANTES de abrir a Home
                    preCarregarConteudoInicial(dnsVencedor!!, user, pass)
                    
                    withContext(Dispatchers.Main) {
                        abrirHomeDireto()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        mostrarErro("Login inválido ou servidor fora do ar.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarErro("Erro: ${e.message}")
                }
            }
        }
    }

    // 🔥 PRE-CARREGAMENTO: Baixa 60 itens de cada para a Home abrir cheia
    private suspend fun preCarregarConteudoInicial(dns: String, user: String, pass: String) {
        try {
            val db = AppDatabase.getDatabase(this)
            
            // --- FILMES (Pega os primeiros 60) ---
            try {
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                
                // ✅ AJUSTADO: Usando OkHttp com User-Agent em vez de URL.readText() para evitar bloqueios
                val request = Request.Builder().url(vodUrl).header("User-Agent", USER_AGENT).build()
                val responseBody = client.newCall(request).execute().body?.string() ?: ""
                
                val jsonArray = JSONArray(responseBody)
                val batch = mutableListOf<VodEntity>()
                
                // Limita a 60 para ser rápido (apenas para o Banner e primeiras listas)
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

            // --- SÉRIES (Pega as primeiras 60) ---
            try {
                val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                
                // ✅ AJUSTADO: Usando OkHttp com User-Agent para séries também
                val request = Request.Builder().url(seriesUrl).header("User-Agent", USER_AGENT).build()
                val responseBody = client.newCall(request).execute().body?.string() ?: ""
                
                val jsonArray = JSONArray(responseBody)
                val batch = mutableListOf<SeriesEntity>()
                
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
            e.printStackTrace()
        }
    }

    private fun testarConexaoIndividual(baseUrl: String, user: String, pass: String): String? {
        val urlLimpa = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val apiLogin = "$urlLimpa/player_api.php?username=$user&password=$pass"

        return try {
            // ✅ AJUSTADO: Adicionado User-Agent no teste de login para os outros 5 DNS não bloquearem
            val request = Request.Builder()
                .url(apiLogin)
                .header("User-Agent", USER_AGENT)
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Se o servidor retornar user_info, significa que este usuário e senha pertencem a ESTE DNS
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

    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
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
