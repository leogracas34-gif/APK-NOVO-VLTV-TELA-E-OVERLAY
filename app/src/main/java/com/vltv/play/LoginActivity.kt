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

    // SEUS 6 SERVIDORES XTREAM (INTACTOS)
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
        
        // ✅ 1. CHECAGEM SILENCIOSA (Auto-Login instantâneo)
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            // Se já tem salvo, ignora tudo e abre a tela de perfis direto!
            abrirHomeDireto()
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

    // ✅ FUNÇÕES DE CACHE DE DNS INTELIGENTE (Sua Ideia)
    private fun getDnsForCredentials(user: String, pass: String): String? {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val key = "${user.trim()}_${pass.trim()}_dns"
        return prefs.getString(key, null)
    }

    private fun saveDnsForCredentials(user: String, pass: String, dns: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val key = "${user.trim()}_${pass.trim()}_dns"
        prefs.edit().putString(key, dns).apply()
    }

    private fun iniciarLoginTurbo(user: String, pass: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        binding.etUsername.isEnabled = false
        binding.etPassword.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var dnsVencedor: String? = null

                // 🔥 1. TENTA O DNS SALVO ESPECÍFICO DESTE CLIENTE PRIMEIRO
                val cachedDns = getDnsForCredentials(user, pass)
                if (cachedDns != null) {
                    val validUrl = testarConexaoIndividual(cachedDns, user, pass)
                    if (validUrl != null) {
                        dnsVencedor = validUrl
                    }
                }

                // 🔥 2. SE O CACHE FALHOU OU NÃO EXISTE, FAZ A SUA CORRIDA DE DNS (100% INTACTA)
                if (dnsVencedor == null) {
                    val deferreds = SERVERS.map { url -> async { testarConexaoIndividual(url, user, pass) } }
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
                }

                if (dnsVencedor != null) {
                    // Salva o DNS vitorioso atrelado a este usuário e senha
                    saveDnsForCredentials(user, pass, dnsVencedor)
                    
                    // Salva as credenciais globais e o baseUrl
                    salvarCredenciais(dnsVencedor, user, pass)
                    
                    // 🚀 VAI DIRETO PARA A TELA DE PERFIS (Sem travar baixando banco de dados)
                    withContext(Dispatchers.Main) { abrirHomeDireto() }
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

    // 🔥 CONFIGURA XtreamApi para multi-servidor
    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        
        // Configura API para o DNS vencedor
        XtreamApi.setBaseUrl("$dns/")
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
