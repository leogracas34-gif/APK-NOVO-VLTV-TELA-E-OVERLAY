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
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS) // Aumentado um pouco para DNS lentos
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", "") ?: ""

        // Se já tem login, inicia o motor de pré-carga e entra
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            iniciarMotorEEntrar(savedDns, savedUser, savedPass)
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

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ✅ CORRIDA DE DNS: Dispara todos e pega o primeiro sucesso
                val dnsVencedor = withContext(Dispatchers.IO) {
                    SERVERS.map { server ->
                        async { testarServidor(server, user, pass) }
                    }.awaitAll().firstOrNull { it != null }
                }

                withContext(Dispatchers.Main) {
                    if (dnsVencedor != null) {
                        salvarDadosLogin(dnsVencedor, user, pass)
                        iniciarMotorEEntrar(dnsVencedor, user, pass)
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Falha na conexão. Verifique seus dados.", Toast.LENGTH_LONG).show()
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
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Verifica se a resposta contém o sucesso da autenticação Xtream
                    if (body.contains("\"auth\":1") || body.contains("user_info")) base else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun iniciarMotorEEntrar(dns: String, user: String, pass: String) {
        // Configura a URL base para o Retrofit/XtreamApi
        XtreamApi.setBaseUrl(if (dns.endsWith("/")) dns else "$dns/")
        
        // 🚀 PRÉ-CARGA TURBO: Baixa enquanto o Android abre a outra tela
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val responseVod = XtreamApi.service.getAllVodStreams(user, pass).execute()
                if (responseVod.isSuccessful && responseVod.body() != null) {
                    val entities = responseVod.body()!!.take(500).map { // Pega os primeiros 500 para não travar
                        VodEntity(it.stream_id, it.name, it.name, it.stream_icon, it.container_extension, it.rating, "0", System.currentTimeMillis()) 
                    }
                    AppDatabase.getDatabase(this@LoginActivity).streamDao().insertVodStreams(entities)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun salvarDadosLogin(dns: String, user: String, pass: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
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
