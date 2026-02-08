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
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
        .connectTimeout(5, TimeUnit.SECONDS) // Timeout curto para testar rápido
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // Não perde tempo tentando reconectar em servidor morto
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO IMERSIVO (Tela Cheia)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // VERIFICA LOGIN SALVO
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            // Se já tem tudo, pula direto. A Home que se vire para validar se expirou.
            abrirHomeDireto()
            return
        }

        setupUI()
    }

    private fun setupUI() {
        // Configuração de Foco e Teclado (TV e Celular)
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
        
        // Foco inicial
        binding.etUsername.requestFocus()
    }

    private fun iniciarLoginTurbo(user: String, pass: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        binding.etUsername.isEnabled = false
        binding.etPassword.isEnabled = false

        // 🚀 LÓGICA "RACE" (CORRIDA): O primeiro que responder ganha, os outros são cancelados.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cria uma lista de tarefas (Jobs) para cada servidor
                val deferreds = SERVERS.map { url ->
                    async {
                        testarConexaoIndividual(url, user, pass)
                    }
                }

                // Aguarda o PRIMEIRO resultado válido
                // (Isso é muito mais rápido que esperar todos falharem)
                var dnsVencedor: String? = null
                
                // Loop de verificação rápida
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 10000) { // Tenta por 10 segundos no máximo
                    val completed = deferreds.filter { it.isCompleted }
                    for (job in completed) {
                        val result = job.getCompleted()
                        if (result != null) {
                            dnsVencedor = result
                            break
                        }
                    }
                    if (dnsVencedor != null) break
                    delay(100) // Verifica a cada 100ms
                }

                // Cancela os outros que ainda estão rodando para economizar bateria
                deferreds.forEach { if (it.isActive) it.cancel() }

                withContext(Dispatchers.Main) {
                    if (dnsVencedor != null) {
                        salvarCredenciais(dnsVencedor!!, user, pass)
                        abrirHomeDireto()
                    } else {
                        mostrarErro("Nenhum servidor respondeu. Verifique sua internet ou credenciais.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarErro("Erro de conexão: ${e.message}")
                }
            }
        }
    }

    // Testa um único servidor e retorna a URL se funcionar, ou NULL se falhar
    private fun testarConexaoIndividual(baseUrl: String, user: String, pass: String): String? {
        val urlLimpa = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        // Usa a API simples de autenticação do Xtream
        val apiLogin = "$urlLimpa/player_api.php?username=$user&password=$pass"

        return try {
            val request = Request.Builder().url(apiLogin).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Verifica se o JSON tem dados de usuário (prova de login sucesso)
                    if (body.contains("user_info") && body.contains("server_info")) {
                        return urlLimpa // SUCESSO! Retorna esse DNS
                    }
                }
            }
            null
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
        val intent = Intent(this, HomeActivity::class.java)
        // Limpa a pilha para o usuário não voltar pro login com o botão voltar
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish() // Mata o LoginActivity
    }

    private fun mostrarErro(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnLogin.isEnabled = true
        binding.etUsername.isEnabled = true
        binding.etPassword.isEnabled = true
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
