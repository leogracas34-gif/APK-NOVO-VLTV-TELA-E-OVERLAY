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
        .connectTimeout(5, TimeUnit.SECONDS) // Timeout curto para testar rápido
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // Não perde tempo reconectando se falhar
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO IMERSIVO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // VERIFICA SE JÁ TEM LOGIN COMPLETO SALVO
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        // Se tem usuário, senha e DNS, entra direto (sem testar internet, a Home resolve isso)
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

        // 🚀 LÓGICA "CORRIDA DE DNS": Dispara todos, o primeiro que responder ganha.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cria uma tarefa para cada servidor
                val tarefas = SERVERS.map { url ->
                    async { testarConexaoIndividual(url, user, pass) }
                }

                // Monitora quem termina primeiro com sucesso
                var dnsVencedor: String? = null
                val inicio = System.currentTimeMillis()
                
                // Tenta por no máximo 10 segundos
                while (System.currentTimeMillis() - inicio < 10000) { 
                    val concluidos = tarefas.filter { it.isCompleted }
                    for (job in concluidos) {
                        val resultado = job.getCompleted()
                        if (resultado != null) {
                            dnsVencedor = resultado
                            break
                        }
                    }
                    if (dnsVencedor != null) break
                    delay(100) // Verifica a cada 100ms
                }

                // Cancela os outros para liberar memória e internet
                tarefas.forEach { if (it.isActive) it.cancel() }

                withContext(Dispatchers.Main) {
                    if (dnsVencedor != null) {
                        salvarCredenciais(dnsVencedor!!, user, pass)
                        abrirHomeDireto()
                    } else {
                        mostrarErro("Falha ao conectar. Verifique seus dados ou internet.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarErro("Erro: ${e.message}")
                }
            }
        }
    }

    // Testa um servidor por vez (chamado em paralelo pelo async)
    private fun testarConexaoIndividual(baseUrl: String, user: String, pass: String): String? {
        val urlLimpa = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val apiLogin = "$urlLimpa/player_api.php?username=$user&password=$pass"

        return try {
            val request = Request.Builder().url(apiLogin).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Se o servidor confirmou que é um usuário válido
                    if (body.contains("\"auth\":1") || (body.contains("user_info") && body.contains("server_info"))) {
                        return urlLimpa // SUCESSO!
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
        
        // Configura o Retrofit para as próximas telas
        XtreamApi.setBaseUrl(if (dns.endsWith("/")) dns else "$dns/")
    }

    private fun abrirHomeDireto() {
        // 🔥 AQUI ESTÁ A MÁGICA: Apenas abre a Home.
        // O download pesado foi removido daqui e será feito pela Home em lotes de 50.
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
