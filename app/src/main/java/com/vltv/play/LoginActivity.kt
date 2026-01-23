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
import com.vltv.play.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    // SEUS 6 SERVIDORES XTREAM (MANTIDOS)
    private val SERVERS = listOf(
        "http://tvblack.shop",
        "http://redeinternadestiny.top",
        "http://fibercdn.sbs",
        "http://vupro.shop",
        "http://blackdns.shop",
        "http://blackdeluxe.shop"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val windowInsetsController =
        WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            startHomeActivity()
            return
        }

        // ✅ Config D-Pad TV com Animação Premium
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

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var lastError: String? = null

            for (server in SERVERS) {
                val base = if (server.endsWith("/")) server.dropLast(1) else server
                val urlString = "$base/player_api.php?username=$user&password=$pass"

                try {
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("dns", base)
                            putString("username", user)
                            putString("password", pass)
                            apply()
                        }

                        XtreamApi.setBaseUrl("$base/")

                        success = true
                        break
                    } else {
                        lastError = "Servidor $base retornou código $responseCode"
                    }
                } catch (e: Exception) {
                    lastError = "Servidor $server: ${e.message}"
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    startHomeActivity()
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Erro de Login em todos os servidores.\n$lastError",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                }
            }
        }
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

    // ✅ D-Pad TV PREMIUM (COM ZOOM SUAVE) ✅
    private fun setupTouchAndDpad() {
        // Listener de animação para os campos e botão
        val premiumFocusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                // Aumenta levemente e suavemente quando focado
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                v.isSelected = true
            } else {
                // Volta ao normal quando perde o foco
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                v.isSelected = false
            }
        }

        // Aplica o efeito nos campos e no botão
        binding.etUsername.onFocusChangeListener = premiumFocusListener
        binding.etPassword.onFocusChangeListener = premiumFocusListener
        binding.btnLogin.onFocusChangeListener = premiumFocusListener
        
        binding.btnLogin.isFocusable = true
        binding.btnLogin.isFocusableInTouchMode = true
        
        // Campos Enter = próximo campo (TV + teclado celular)
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
        
        // Foco inicial no campo de usuário
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
