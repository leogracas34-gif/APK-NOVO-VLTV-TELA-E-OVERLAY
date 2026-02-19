package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vltv.play.databinding.ActivityHomeBinding
import com.google.android.gms.cast.framework.CastContext
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    
    // Variáveis de Perfil
    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            configurarOrientacaoAutomatica()
            
            binding = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // ✅ CONFIGURAÇÃO DA NAVEGAÇÃO
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val navController = navHostFragment?.navController
            if (navController != null) {
                // CORREÇÃO LINHA 50: Adicionado ?. para evitar Unresolved reference/Nullability error
                binding.bottomNavigation?.setupWithNavController(navController)
            }

            // Recupera dados do Perfil
            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON")

            // Configuração da Barra de Status
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false 

            DownloadHelper.registerReceiver(this)

            // Inicializa o contexto do Cast (Chromecast)
            try { 
                CastContext.getSharedInstance(this) 
            } catch (e: Exception) {
                e.printStackTrace()
            }

            setupBottomNavigation()
            setupFirebaseRemoteConfig()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun configurarOrientacaoAutomatica() {
        if (isTVDevice()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun isTVDevice(): Boolean {
        return try {
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == 
            Configuration.UI_MODE_TYPE_TELEVISION
        } catch (e: Exception) {
            false
        }
    }

    private fun setupBottomNavigation() {
        // CORREÇÃO LINHA 101: Adicionado ?. para tratar o componente como nullable
        binding.bottomNavigation?.let { nav ->
            val profileItem = nav.menu.findItem(R.id.nav_profile)
            profileItem?.title = currentProfile

            if (!currentProfileIcon.isNullOrEmpty()) {
                Glide.with(this)
                    .asBitmap()
                    .load(currentProfileIcon)
                    .circleCrop()
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            profileItem?.icon = BitmapDrawable(resources, resource)
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }
    }

    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.fetchAndActivate()
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair").setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
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
