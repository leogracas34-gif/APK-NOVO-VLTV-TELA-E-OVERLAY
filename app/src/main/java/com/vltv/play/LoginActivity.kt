package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vltv.play.databinding.ActivityLoginBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // Cliente OkHttp otimizado para performance e dados móveis
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS) // Timeout rápido para pular servidores lentos
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true) // ✅ Interceptor nativo para evitar queda em dados móveis
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuração de tela cheia (Imersivo)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            startHomeActivity()
            return
        }

        setupTouchAndDpad()

        binding.btnLogin.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            } else {
                realizarLoginMultiServidor(user, pass)
            }
        }
    }

    private fun realizarLoginMultiServidor(user: String, pass: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        // ✅ CORRIDA DE SERVIDORES (O segredo da velocidade)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Dispara todas as requisições em paralelo
                val jobs = SERVERS.map { server ->
                    async {
                        testarServidor(server, user, pass)
                    }
                }

                // Espera o PRIMEIRO que retornar sucesso
                val dnsVencedor = selectFirstSuccess(jobs)

                withContext(Dispatchers.Main) {
                    if (dnsVencedor != null) {
                        salvarDadosLogin(dnsVencedor, user, pass)
                        startHomeActivity()
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Falha em todos os servidores. Verifique seus dados.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun testarServidor(server: String, user: String, pass: String): String? {
        val base = if (server.endsWith("/")) server.dropLast(1) else server
        val urlString = "$base/player_api.php?username=$user&password=$pass"

        return try {
            val request = Request.Builder().url(urlString).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) base else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun selectFirstSuccess(jobs: List<Deferred<String?>>): String? {
        // Esta função aguarda as Coroutines e retorna a primeira que não for nula
        val result = jobs.map { it.await() }
        return result.firstOrNull { it != null }
    }

    private fun salvarDadosLogin(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        XtreamApi.setBaseUrl("$dns/")
    }

    private fun startHomeActivity() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedDns = prefs.getString("dns", null)
        if (!savedDns.isNullOrBlank()) {
            XtreamApi.setBaseUrl(if (savedDns.endsWith("/")) savedDns else "$savedDns/")
        }
        
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
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
